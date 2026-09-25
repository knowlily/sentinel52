# 计算器（Sentinel52）

自己维护一份包名名单，**名单里的应用一被装上就自动卸载。**

打包好的 APK 在 [Releases](https://github.com/knowlily/sentinel52/releases) 里，自己构建看下面的「构建」。

> **关于名字**：装到手机上它显示为「计算器」，包名是 `com.miui.calculator`——这是**故意的伪装**，
> 让它在桌面和 `pm list packages` 里不显眼。代码里的包结构（namespace）仍是 `com.fiftytwo.sentinel`，
> 那只影响 dex 里的类名，不进入系统可见的标识。
> 副作用：**同一个手机上不能和真的 `com.miui.calculator` 共存**（同包名互斥），如果那是系统自带的计算器，
> 装之前先想清楚要不要替换它。

适合治那些「删了又自己装回来」的东西——预装机型推广、互相拉活的兄弟应用、某个一更新就作妖的 App。

## 它到底怎么干活的

1. 界面上加规则：完整包名 `com.example.app`，或子包通配 `com.tencent.*`（只吃子包，不吃 `com.tencent` 本身）。
2. 打开监控 → 起一个前台服务，两条通道同时盯着：
   - **安装广播**（`PACKAGE_INSTALL` / `PACKAGE_ADDED`）——装完的瞬间就动手；
   - **定时轮询**（默认 3 秒，可调 1/3/5/10/30 秒）——真的去列一遍包名，广播被 ROM 拦掉也躲不过。
3. 命中 → 走当前可用的特权后端执行卸载，然后**再查一次**确认真的没了，才在日志里记「已卸载」。
   实测从命中到消失 1 秒上下。
4. 结果分两处看：
   - **主页的「卸载统计」**：累计 / 今日 / 本次监控 / 失败 + 最近一次的包名与时间。
     这几个数是**单独记账、单独持久化**的（`UninstallStats`），不复用日志条数——日志有条数上限，
     一超上限统计就失真。「今日」按本地自然日归零，靠存下来的日期戳判断，不依赖任何定时任务。
   - **独立日志页**：主页统计卡里点「查看日志」进入（系统返回键退回主页），
     可按 全部 / 已卸载 / 命中 / 失败 / 其它 筛选，条数实时统计。

## 三个特权后端

静默卸载没有任何一条「纯应用」的路可走。AOSP 里 `PackageInstaller.uninstall()` 的判定是这样的：

```java
if (checkPermission(DELETE_PACKAGES) == GRANTED) {           // 签名|特权|角色权限，普通应用拿不到
    mPm.deletePackageVersioned(...);                          // 直通
} else if (canSilentlyInstallPackage(callerPackageName, callingUid)) {   // 设备所有者 / 关联工作资料所有者
    Binder.clearCallingIdentity();
    mPm.deletePackageVersioned(...);                          // 静默
} else {
    enforcePermission(REQUEST_DELETE_PACKAGES);               // 只能弹系统确认框
    ...
}
```

`DELETE_PACKAGES` 实测是 `signature|privileged|role`，连 `adb shell pm grant` 都不给（`Permission ... is managed by role`）。
所以只有「自己就是 root」「当设备所有者」「借一个有 shell 身份的特权服务」这几种身份。本应用三个都接：

| 后端 | 身份 | 卸载怎么执行 | 代价 |
| --- | --- | --- | --- |
| **Root**（首选） | uid 0 | `su -c "pm uninstall --user 0 '<包名>'"` | 手机得已经 root（Magisk / KernelSU / APatch），并在 su 管理器里允许本应用 |
| **[Dhizuku](https://github.com/iamr0s/Dhizuku)** | 设备所有者 | 我们的代码被 Dhizuku 加载进**它自己的进程**，用它的 Context 调 `PackageInstaller.uninstall` | 要在电脑上 `dpm set-device-owner` 设一次；设置时设备上不能有任何账号 |
| **[Stellar](https://github.com/roro2239/Stellar-API)** | ADB / Shell（uid 2000） | 起一个服务身份的进程跑 `pm uninstall` | 服务重启后要在应用里重新授权一次 |

**优先级：就绪 > 已装，同级按 Root → Dhizuku → Stellar**（就是这个枚举的声明顺序）。
「就绪」= 这个后端已经拿到授权；没授权的后端只会在界面上提示该做什么，绝不会被拿去执行卸载。
三者都可用时选 Root：权限最高（系统应用也卸得掉），不依赖常驻服务，也不需要设备所有者那套限制。
界面上三个后端各占一行，谁在用、谁缺什么，一眼能看到。

### Root 这条路的两个坑

- **「装没装 su」不能只看文件**：Magisk 会把 `su` 放在 `/system/bin/su`，但 KernelSU 打开「传统 SU 命令支持」
  后是由**内核拦 `execve("/system/bin/su")`**（或把 su 挂在它自己的用户态目录里）提供的，磁盘上可能根本没有这个文件，
  `ls` / `which su` 都找不到。所以本应用把「装了 su 管理器（KernelSU / Magisk / APatch）」也算作「这条路存在」，
  状态报成**待授权**而不是**没装**——用户才知道该点「申请 root」。
- **授权状态查不到，只能真跑一次**：Dhizuku / Stellar 的授权随时能用 binder 查询，su 的授权只有跑一次才知道，
  而第一次跑会弹 su 管理器的授权框。所以拆成两件事：后台探测只做「不执行任何东西」的检查
  （所以能 3 秒一次地刷），授权由用户点按钮触发（`su -c id`，最长等 20 秒）。
  已授权之后每次探测会顺手复核（5 秒超时），su 管理器里被撤权就自动降级。
- 失败时**把异常原文写进日志**（`error=2, No such file or directory` 这种），这类问题在真机上只能靠这行字诊断。

### Dhizuku 这条路为什么必须在它进程里执行

`canSilentlyInstallPackage` 看的是**调用者 uid** 对应的包是不是设备所有者，而 bind 之前的
`mAppOps.checkPackage(callingUid, callerPackageName)` 又要求 `callerPackageName` 属于该 uid——
我们的进程两条都不满足。Dhizuku 提供的 UserService 机制正好解决这件事：它把自己的 Context 传给我们被加载的类
（`createPackageContext(..., INCLUDE_CODE)` 后反射构造），于是代码跑在它的进程、拿它的身份、用它的包名。

对应实现：`ISentinelDeviceAdmin.aidl`（AIDL 事务码从 **20** 起——Dhizuku 服务端拿
`FIRST_CALL_TRANSACTION+1/+2` 当用户服务的生命周期信号，默认从 1 编号会和它撞号）、
`DhizukuUserService`（跑在 Dhizuku 进程里的那个类）、`DhizukuService`（客户端：探测 / 授权 / 绑定 / 转发）。

### 为什么不再反射隐藏 API

原版做法是反射 `IPackageManager.deletePackageVersioned` / `deletePackageAsUser`。实测（Android 15 / targetSdk 36）：
签名在 API 35 变过（`userId` 从 `UserHandle` 变成 `int`，参数顺序也换过），而且**就算签名一字不差，
`Class.getMethod` 也会抛 `NoSuchMethodException`**——隐藏 API 限制让这些非 SDK 成员表现得「不存在」。
这条路已经删掉了，留着只会制造误导性的日志。

## 需要什么

- Android 8.0（API 26）以上
- **三个后端里至少搞定一个**：
  - **Root**：手机已经 root（Magisk / KernelSU / APatch），在本应用点「申请 root」，在 su 授权框里允许；
  - **Dhizuku**（`com.rosan.dhizuku`）并把它设成设备所有者：
    ```bash
    adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver
    ```
    （要求设备上没有任何账号；设好之后系统会显示「由组织管理」，Dhizuku 作为设备所有者也不能被卸载）
  - 或 **Stellar** 管理器（`roro.stellar.manager`），按它自己的引导把服务跑起来
    （Android 11+ 用无线调试就能在本机启动，也可以连电脑用 adb 执行它给出的命令）
- 在本应用里点对应后端的授权按钮并允许。Root 和 Dhizuku 授权一次长期有效；Stellar 服务每次重启后要重新授权。

## 安全阀

| 机制 | 作用 |
| --- | --- |
| **内置保护名单** | `android`、`com.android.systemui`、`com.android.settings`、`com.android.shell`、`roro.stellar.manager`、`com.rosan.dhizuku`、`me.weishu.kernelsu`/`com.topjohnwu.magisk`/`me.bmax.apatch` 等关键包即便命中规则也不动——删掉任何一个，手机或后端自己就废了 |
| **自身保护** | 规则里写到本应用自己时跳过（卸载自己会让服务直接断在半路） |
| **仅记录（演练模式）** | 命中时只写入日志、不执行卸载；确认名单无误后再关闭 |
| **规则可停用** | 每条规则独立开关，不用删掉重加 |
| **未就绪时不硬上** | 三个后端都没就绪时只记日志说明原因，不会退化成「静默什么都没干」 |
| **未就绪的降级提示** | 三个后端都没就绪时，命中不卸载，但会**写日志 + 常驻通知换文案 + 额外弹一条「命中但未执行卸载」的提醒**（点它去授权）。节流：同一批包 10 分钟内只提醒一次、集合变了立刻提醒、一个都不跳时把提醒收回——轮询默认 3 秒一轮，不节流就是刷屏 |
| **忽略电池优化** | 界面上可直接申请加入白名单，避免 Doze 把轮询压到几十分钟一次 |

## 省电

这套东西的天性是「一直盯着」，所以每一轮扫描干了什么必须算清楚。默认 3 秒一轮，动手之前每轮都要：
fork 一次 `su -c id` 复核 root、3~4 次跨进程探测（Dhizuku 的 ContentProvider + 权限查询、Stellar 的 binder）、
再把整机几百个包拉一遍。现在改成：

- **后端探测结果缓存 60 秒**（`Privileged.PROBE_TTL_MS`）——授权、服务重启都是人手触发的慢变量，
  轮询没必要每 3 秒重问一遍；用户点「重新检测」、授权流程结束、卸载失败时会强制重探。
- **root 授权复核缓存 5 分钟**（`RootService.VERIFY_TTL_MS`）——起 root 进程是整套探测里最贵的一步。
- **名单全是精确包名时只查这几条**，不再拉整机列表；出现通配规则才需要全量（`com.tencent.*` 得靠它枚举子包）。
- **本地查询优先**：清单里声明了 `QUERY_ALL_PACKAGES`，本地 `getPackageInfo` 就够，少一次 binder；
  特权服务只作兜底。
- **屏幕关着时兜底轮询抬到 30 秒**（`PollingPolicy`）——「装上就被摘掉」的实时性靠安装广播，
  定时轮询只是广播被 ROM 拦掉时的兜底。
- **每轮都会走到的无脑调用掐掉**：通知文案没变就不 `notify`；降级提醒没挂过就不 `cancel`（各是一次 IPC）。

实测（MuMu / Android 15：应用退到后台、名单 1 条、监控开着，读 `/proc/<pid>/stat` 的 utime+stime）：

| 场景 | 改之前 · 120 秒 | 改之后 · 120 秒 |
| --- | --- | --- |
| 屏幕亮着 | 870 ms | **60 ms** |
| 屏幕熄灭（真 Asleep） | 同样是 870 ms 量级（没有熄屏适配） | **10 ms** |

这里只统计应用自身的 CPU，不含 system_server 替它做 IPC 的开销，所以真实收益比这个表更大。

## 构建

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"
export ANDROID_HOME="D:/Android/Sdk"
export GRADLE_USER_HOME="D:/gradle-home"
./gradlew :app:testDebugUnitTest          # 100 个单测
./gradlew :app:assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease            # app/build/outputs/apk/release/app-release.apk
```

图标不是手改的：`tools/make_icons.py` 从 `design/ic_launcher_source.png` 生成各密度的传统图标、
自适应图标的前景层（源图缩到 75% 居中，保证符号落在遮罩的安全区里）和单色层（主题图标用），
底色取源图边缘颜色写进 `values/colors.xml`。换图标就替换源图再跑一次：

```bash
"D:/python/python.exe" tools/make_icons.py design/ic_launcher_source.png
```

### 依赖

```kotlin
implementation("io.github.iamr0s:Dhizuku-API:2.5.3")   // Maven Central / 阿里云镜像
implementation("com.github.roro2239:Stellar-API:1.0.3") // 只在 JitPack
```

Dhizuku 的版本**不能往上抬到 2.5.4+**：从那个版本起制品是 Java 21 字节码（class file 65），
本机 JDK 17 的编译链读不了。2.5.3 是 Java 8 字节码，而且它的事务码（`bindUserService=12` / `unbindUserService=13`）
与 2.12.0 版 Dhizuku 服务端一致——已在 Android 15 真机上验证能通。

Stellar 那边：`settings.gradle.kts` 里声明了 JitPack 并按 group 收窄。
**本机还要在 `$GRADLE_USER_HOME/init.d/aliyun-mirror.gradle` 里也放行一次**——那个脚本会 `clear()` 掉工程里的仓库列表、
换成阿里云镜像（这台机器取不到 `dl.google.com`），而阿里云的 jitpack 镜像是 401，只能走 jitpack.io 直连。

### release 签名

release 走 R8（`isMinifyEnabled` + `isShrinkResources`），必须签名才能装，所以仓库根有一个
`keystore.properties`（**不进版本库**，`.gitignore` 已排除）指向自签密钥：

```
storeFile=keystore/sentinel-release.jks
storePassword=…
keyAlias=sentinel
keyPassword=…
```

密钥是现生成的个人自签名证书（`CN=Calculator`，RSA 2048，有效期 10000 天）：

```bash
keytool -genkeypair -keystore keystore/sentinel-release.jks -alias sentinel \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass <密码> -keypass <密码> \
  -dname "CN=Calculator, OU=Personal, O=Personal, C=CN"
```

没有 `keystore.properties` 时 release 会退回 debug 签名（只是让 clone 下来也能直接出包）。
**换密钥后必须卸载重装**——签名不同的包不能覆盖安装。

R8 会裁掉没被引用的代码，`proguard-rules.pro` 里保住了三类：`roro.stellar.**`、
**`DhizukuUserService` 和它实现的 AIDL 桩**（Dhizuku 是按类名反射加载它、按构造签名实例化的，一旦被混淆或裁掉这条路就废）、
以及实现 `IInterface` 的本地桩。
改完发布版请按下面的方式在真机上过一遍，别只看编译通过（release 包 `debuggable=false`，
`run-as` 读不到它自己的 prefs，取证要从界面日志读）。

## 代码结构

```
core/         纯 Kotlin，无 Android 依赖，全部有 JVM 单测
  RuleMatcher            包名 vs pattern（精确 / 子包通配）
  PackageNameValidator   手输包名的校验与错误文案
  UninstallPlanner       谁该被卸载：命中、保护名单、自身、停用规则
  UninstallCommand       卸载命令拼装 + 包名引号转义（Root 与 Stellar 共用）
  RuleCodec / EventCodec 规则与日志的落盘编解码（坏行只丢那一行）
  PrivilegedState        后端状态判定 + BackendSelector（就绪优先，同级按枚举顺序）+ uid 身份文案
  DailyCounter           「今日」计数器跨天归零的纯逻辑（读时 / 写时两条规则）
privileged/   特权层
  Privileged             门面：探测三个后端 → 选一个 → 把卸载转过去
  RootService            su -c（uid 0）跑 pm uninstall；探测不执行、授权才执行
  DhizukuService         Dhizuku 客户端：init / 授权 / 绑定用户服务
  DhizukuUserService     跑在 Dhizuku 进程里的那个类（设备所有者身份调 PackageInstaller.uninstall）
  StellarService         Stellar 客户端：探测 / 授权 / newProcess / 打 binder 包
  PackageOps             查包 + 卸载后复查（受理 ≠ 删掉）
  ShellResult            特权进程的退出码 + 输出（两者必须分开看）
  ISentinelDeviceAdmin.aidl  客户端 ↔ Dhizuku 进程里的用户服务
data/SentinelStore     规则 / 日志 / 开关 / 卸载统计的唯一数据源（StateFlow + SharedPreferences）
service/MonitorService 前台服务：轮询 + 安装广播，双通道扫描
service/BootReceiver   开机把监控接回去（上次是开着的才接）
ui/                    Compose 界面
tools/make_icons.py    从 design/ic_launcher_source.png 生成各密度图标（传统 / 自适应前景 / 单色层）
```

判定逻辑刻意留在 `core/`：它决定用户的手机被删掉什么，而且完全不需要 Android 环境——能测就必须测。

## 已知限制

- **Stellar 的 shell 身份卸不掉系统应用**（`pm uninstall` 对它们同样无效），日志里会记成失败；
  想要卸系统应用得用 Root 或 Dhizuku 的身份。
- **Root 那条路依赖手机已经 root**，并且要在 su 管理器里允许本应用；有些 su 实现（KernelSU 的部分构建）
  的 `su` 只在内核/它自己的命名空间里可见，应用进程可能 exec 不到——这时日志里会留下
  `error=2, No such file or directory` 这样的原文，便于判断是环境问题而不是应用逻辑问题。
  su 管理器（KernelSU / Magisk / APatch）都在保护名单里：规则误写到它们也不会被卸掉。
- **Dhizuku 设置门槛**：`dpm set-device-owner` 要求设备上没有任何账号，且设好后 Dhizuku 自己不能被卸载，
  系统设置里会出现「由组织管理」。想撤掉只能清设备所有者（root 删
  `/data/system/device_owner*.xml` 与 `device_policies.xml` 里的条目后重启，或恢复出厂）。
- **监控状态有两个来源，要对齐**：应用被更新或被系统杀死之后，持久化的「监控中」已经没有对应的服务在跑，
  而 Android 12+ 不允许后台广播（`MY_PACKAGE_REPLACED`）直接拉起前台服务——启动请求会被系统拒掉、静默失败。
  所以每次打开界面都会补一次服务启动（`MainActivity.alignMonitoringState`）；开机场景走 `BOOT_COMPLETED`（在豁免名单里）。
- 有些 ROM 会限制前台服务 / 自启动，需要在系统设置里把本应用的「自启动」「后台运行」放行。

## 许可证

GPL-3.0，全文见 [LICENSE](LICENSE)。

用到的第三方库：Dhizuku-API（[GPL-3.0](https://github.com/iamr0s/Dhizuku)）、
Stellar-API（[roro2239/Stellar-API](https://github.com/roro2239/Stellar-API)，Shizuku 的分支，上游 Stellar 采用 MPL-2.0）、
Shizuku（[Apache-2.0](https://github.com/RikkaApps/Shizuku)）。
`INTEGRATION_GUIDE.md` 是 Stellar 上游那份接入文档的原文，里面「许可证」一节说的是它自己那个项目。

`keystore.properties` 和 `keystore/` 不在版本库里（`.gitignore` 已排除），
所以 clone 下来只能出 debug 签名的包；Release 里那份是按上面「release 签名」一节的命令自签的。
