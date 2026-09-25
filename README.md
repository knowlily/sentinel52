# 计算器（Sentinel52）

一份由使用者自己维护的包名名单：名单里的应用一旦被安装，就会被自动卸载。

适用于反复被装回来的应用——预装机型的推广组件、相互拉活的应用、更新后行为异常的应用。

已经签名的 APK 放在 [Releases](https://github.com/knowlily/sentinel52/releases)，自行构建见「构建」一节。
本应用会真实卸载应用，且不保留备份，安装之前请先读一遍文末的「免责声明」。

> **关于名字**
>
> 装到手机上它显示为「计算器」，包名是 `com.miui.calculator`。这是有意的伪装，目的是让它出现在桌面和
> `pm list packages` 的输出里时不引人注意。代码里的包结构（namespace）仍是 `com.fiftytwo.sentinel`，
> 那只影响 dex 中的类名，不出现在系统可见的标识里。
>
> 代价是同一台设备上不能与真正的 `com.miui.calculator` 共存（同包名互斥）。如果那是系统自带的计算器，
> 安装前需要想清楚是否要替换掉它。

## 目录

- [工作原理](#工作原理)
- [三个特权后端](#三个特权后端)
- [使用前提](#使用前提)
- [安全阀](#安全阀)
- [省电](#省电)
- [构建](#构建)
- [代码结构](#代码结构)
- [已知限制](#已知限制)
- [免责声明](#免责声明)
- [许可证](#许可证)

## 工作原理

1. 在界面上添加规则：完整的包名（`com.example.app`），或子包通配（`com.tencent.*`，只匹配子包，不匹配
   `com.tencent` 本身）。
2. 打开监控后启动一个前台服务，同时从两条通道检查：
   - **安装广播**（`PACKAGE_INSTALL` / `PACKAGE_ADDED`）：安装完成的瞬间即触发；
   - **定时轮询**（默认 3 秒，可选 1 / 3 / 5 / 10 / 30 秒）：实际列举一遍已安装的包名，广播被 ROM 拦截时也能发现。
3. 命中规则后，用当前可用的特权后端执行卸载，随后再查询一次确认目标确实不存在，才在日志中记录「已卸载」。
   实测从命中到目标消失约 1 秒。
4. 结果分两处呈现：
   - **主页的「卸载统计」**：累计 / 今日 / 本次监控 / 失败，以及最近一次的包名与时间。这些数字单独记账、
     单独持久化（`UninstallStats`），不复用日志条数——日志有条数上限，超过上限后统计会失真。「今日」按本地
     自然日归零，依据存下来的日期戳判断，不依赖任何定时任务。
   - **独立日志页**：从主页统计卡点「查看日志」进入（系统返回键退回主页），可按 全部 / 已卸载 / 命中 / 失败 /
     其它 筛选，条数实时统计。

## 三个特权后端

静默卸载没有纯应用可以走的路径。AOSP 中 `PackageInstaller.uninstall()` 的判定如下：

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

`DELETE_PACKAGES` 实测的权限级别是 `signature|privileged|role`，`adb shell pm grant` 也无法授予
（返回 `Permission ... is managed by role`）。因此只有三种身份可行：自己就是 root、自己是设备所有者、
借用带 shell 身份的特权服务。本应用三种都接：

| 后端 | 身份 | 卸载如何执行 | 代价 |
| --- | --- | --- | --- |
| **Root**（首选） | uid 0 | `su -c "pm uninstall --user 0 '<包名>'"` | 设备需已 root（Magisk / KernelSU / APatch），并在 su 管理器里允许本应用 |
| **[Dhizuku](https://github.com/iamr0s/Dhizuku)** | 设备所有者 | 本应用的代码被 Dhizuku 加载进它自己的进程，用它的 Context 调 `PackageInstaller.uninstall` | 需在电脑上执行一次 `dpm set-device-owner`；设置时设备上不能有任何账号 |
| **[Stellar](https://github.com/roro2239/Stellar-API)** | ADB / Shell（uid 2000） | 起一个服务身份的进程执行 `pm uninstall` | 服务重启后需在应用内重新授权一次 |

选择顺序是**就绪优先于已安装，同级之间按 Root → Dhizuku → Stellar**（即枚举的声明顺序）。「就绪」表示该后端
已取得授权；未授权的后端只会在界面上提示还需要做什么，绝不会被用于执行卸载。三者都可用时选 Root：它的权限
最高（系统应用也能卸载），不依赖常驻服务，也不需要设备所有者那套限制。界面上三个后端各占一行，当前使用哪一个、
各自还缺什么，都可直接看到。

### Root 后端的两处细节

- **「是否装了 su」不能只看文件**。Magisk 会把 `su` 放在 `/system/bin/su`，但 KernelSU 打开「传统 SU 命令支持」
  后，是由内核拦截 `execve("/system/bin/su")`（或把 su 挂在自己的用户态目录里）来提供的，磁盘上可能根本没有
  这个文件，`ls` / `which su` 都找不到。因此本应用把「装了 su 管理器（KernelSU / Magisk / APatch）」也算作这条路
  存在，状态报成**待授权**而不是**未安装**，这样使用者才知道应该点「申请 root」。
- **授权状态查不到，只能实际执行一次**。Dhizuku / Stellar 的授权随时可以用 binder 查询，su 的授权只有执行过
  才知道，而第一次执行会弹出 su 管理器的授权框。因此这里拆成两件事：后台探测只做「不执行任何操作」的检查
  （所以可以 3 秒一次地刷新），授权由使用者点按钮触发（执行 `su -c id`，最长等待 20 秒）。已授权之后，每次探测会
  顺带复核一次（5 秒超时），在 su 管理器里被撤权时会自动降级。
- 失败时**把异常原文写入日志**（例如 `error=2, No such file or directory`）。这类问题在真机上只能靠这行文字诊断。

### Dhizuku 为什么必须在它自己的进程里执行

`canSilentlyInstallPackage` 检查的是**调用者 uid** 对应的包是否为设备所有者，而 bind 之前的
`mAppOps.checkPackage(callingUid, callerPackageName)` 又要求 `callerPackageName` 属于该 uid，本应用的进程两条
都不满足。Dhizuku 提供的 UserService 机制正好解决这一点：它把自己的 Context 传给被加载的类
（`createPackageContext(..., INCLUDE_CODE)` 之后反射构造），于是代码运行在它的进程里、取得它的身份、使用它的
包名。

对应实现：`ISentinelDeviceAdmin.aidl`（AIDL 事务码从 **20** 起编号——Dhizuku 服务端把
`FIRST_CALL_TRANSACTION+1/+2` 用作 UserService 的生命周期信号，默认从 1 开始会和它撞号）、
`DhizukuUserService`（运行在 Dhizuku 进程里的那个类）、`DhizukuService`（客户端：探测 / 授权 / 绑定 / 转发）。

### 为什么不再反射隐藏 API

最初的做法是反射 `IPackageManager.deletePackageVersioned` / `deletePackageAsUser`。实测（Android 15 /
targetSdk 36）表明：签名在 API 35 变过（`userId` 从 `UserHandle` 变为 `int`，参数顺序也换过），而且**即使签名
完全一致，`Class.getMethod` 也会抛 `NoSuchMethodException`**——隐藏 API 限制使这些非 SDK 成员表现得「不存在」。
这条路径已经删除，保留它只会产生误导性的日志。

## 使用前提

- Android 8.0（API 26）及以上。
- **三个后端中至少配置好一个**：
  - **Root**：设备已 root（Magisk / KernelSU / APatch），在本应用点「申请 root」，并在 su 授权框里允许；
  - **Dhizuku**（`com.rosan.dhizuku`），并把它设为设备所有者：

    ```bash
    adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver
    ```

    该命令要求设备上没有任何账号；设置完成后系统会显示「由组织管理」，Dhizuku 作为设备所有者也无法被卸载；
  - 或 **Stellar** 管理器（`roro.stellar.manager`），按它自己的引导启动服务
    （Android 11+ 可在本机用无线调试启动，也可以连接电脑用 adb 执行它给出的命令）。
- 在本应用内点对应后端的授权按钮并允许。Root 与 Dhizuku 授权一次长期有效；Stellar 服务每次重启后需要重新授权。

## 安全阀

| 机制 | 作用 |
| --- | --- |
| **内置保护名单** | `android`、`com.android.systemui`、`com.android.settings`、`com.android.shell`、`roro.stellar.manager`、`com.rosan.dhizuku`、`me.weishu.kernelsu` / `com.topjohnwu.magisk` / `me.bmax.apatch` 等关键包即使命中规则也不处理——删掉其中任何一个，设备或后端自身就会失效 |
| **自身保护** | 规则写成本应用自身时跳过（卸载自己会让服务中断在半途） |
| **仅记录（演练模式）** | 命中时只写入日志、不执行卸载；确认名单无误后再关闭 |
| **规则可停用** | 每条规则有独立开关，不必删掉再加 |
| **未就绪时不硬上** | 三个后端都没就绪时只记日志说明原因，不会退化成「静默地什么都没做」 |
| **未就绪的降级提示** | 三个后端都没就绪时，命中不卸载，但会写日志、更换常驻通知文案，并额外弹出一条「命中但未执行卸载」的提醒（点击可前往授权）。提醒有节流：同一批包 10 分钟内只提醒一次，集合发生变化时立即提醒，没有命中时收回提醒——轮询默认 3 秒一轮，不节流会造成通知刷屏 |
| **忽略电池优化** | 界面上可直接申请加入白名单，避免 Doze 把轮询压到几十分钟一次 |

## 省电

监控本身是常驻行为，因此每一轮扫描的开销需要逐项核清。改造之前，默认 3 秒一轮的循环里每轮都会：fork 一次
`su -c id` 复核 root、进行 3~4 次跨进程探测（Dhizuku 的 ContentProvider 与权限查询、Stellar 的 binder），
以及拉取一次整机的包列表。改造之后：

- **后端探测结果缓存 60 秒**（`Privileged.PROBE_TTL_MS`）。授权与服务重启都是由人触发的慢变量，轮询没有必要
  每 3 秒重问一遍；使用者点「重新检测」、授权流程结束、卸载失败时会强制重新探测。
- **root 授权复核缓存 5 分钟**（`RootService.VERIFY_TTL_MS`）。启动 root 进程是整套探测里最贵的一步。
- **名单全是精确包名时只查询这几条**，不再拉取整机列表；出现通配规则时才需要全量查询（`com.tencent.*` 要靠它
  枚举子包）。
- **优先本地查询**。清单里声明了 `QUERY_ALL_PACKAGES`，本地 `getPackageInfo` 即可，少一次 binder；特权服务
  只作兜底。
- **屏幕关闭时兜底轮询放宽到 30 秒**（`PollingPolicy`）。「装上就被摘掉」的实时性靠安装广播保证，定时轮询只是
  广播被 ROM 拦截时的兜底。
- **去掉每轮必经的无效调用**。通知文案没变就不调用 `notify`；降级提醒没有挂出过就不调用 `cancel`（各是一次 IPC）。

实测数据（MuMu / Android 15：应用退到后台、名单 1 条、监控开启，读取 `/proc/<pid>/stat` 的 utime+stime）：

| 场景 | 改造前 · 120 秒 | 改造后 · 120 秒 |
| --- | --- | --- |
| 屏幕亮着 | 870 ms | **60 ms** |
| 屏幕熄灭（真 Asleep） | 同样是 870 ms 量级（没有熄屏适配） | **10 ms** |

这里只统计应用自身的 CPU，不含 system_server 代它做 IPC 的开销，因此真实收益比表中更大。

## 构建

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"
export ANDROID_HOME="D:/Android/Sdk"
export GRADLE_USER_HOME="D:/gradle-home"
./gradlew :app:testDebugUnitTest          # 100 个单测
./gradlew :app:assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease            # app/build/outputs/apk/release/app-release.apk
```

图标不是手工修改的：`tools/make_icons.py` 从 `design/ic_launcher_source.png` 生成各密度的传统图标、自适应图标
的前景层（源图缩到 75% 居中，保证符号落在遮罩的安全区里）和单色层（供主题图标使用），底色取源图边缘颜色写入
`values/colors.xml`。更换图标时替换源图再执行一次：

```bash
"D:/python/python.exe" tools/make_icons.py design/ic_launcher_source.png
```

### 依赖

```kotlin
implementation("io.github.iamr0s:Dhizuku-API:2.5.3")   // Maven Central / 阿里云镜像
implementation("com.github.roro2239:Stellar-API:1.0.3") // 只在 JitPack
```

Dhizuku 的版本**不能升到 2.5.4 及以上**：从该版本起制品是 Java 21 字节码（class file 65），本机 JDK 17 的编译链
无法读取。2.5.3 是 Java 8 字节码，且它的事务码（`bindUserService=12` / `unbindUserService=13`）与 2.12.0 版
Dhizuku 服务端一致，已在 Android 15 真机上验证可用。

Stellar 方面：`settings.gradle.kts` 里已声明 JitPack 并按 group 收窄。**本机还需要在
`$GRADLE_USER_HOME/init.d/aliyun-mirror.gradle` 里放行一次**——那个脚本会 `clear()` 掉工程里的仓库列表并换成
阿里云镜像（这台机器取不到 `dl.google.com`），而阿里云的 jitpack 镜像是 401，只能直连 jitpack.io。

### release 签名

release 走 R8（`isMinifyEnabled` + `isShrinkResources`），必须签名才能安装，因此仓库根有一个
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

没有 `keystore.properties` 时，release 会退回 debug 签名（只是为了让 clone 下来也能直接出包）。
**更换密钥后必须卸载重装**——签名不同的包不能覆盖安装。

R8 会裁掉没有被引用的代码，`proguard-rules.pro` 里保留了三类：`roro.stellar.**`、**`DhizukuUserService` 及其
实现的 AIDL 桩**（Dhizuku 按类名反射加载它、按构造签名实例化，一旦被混淆或裁掉这条路径就失效），以及实现
`IInterface` 的本地桩。

改完发布版请在真机上完整走一遍，不要只依据编译通过：release 包 `debuggable=false`，`run-as` 读不到它自己的
prefs，取证需要从界面日志读取。

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
docs/third-party/      Stellar 上游文档原文（不是本项目的文档）
tools/make_icons.py    从 design/ic_launcher_source.png 生成各密度图标（传统 / 自适应前景 / 单色层）
```

判定逻辑刻意留在 `core/`：它决定使用者的设备上会被删掉什么，而且完全不需要 Android 环境，具备可测试性。

## 已知限制

- **Stellar 的 shell 身份卸不掉系统应用**（`pm uninstall` 对它们同样无效），日志里会记为失败。要卸载系统应用，
  需要使用 Root 或 Dhizuku 的身份。
- **Root 这条路径依赖设备已 root**，并且要在 su 管理器里允许本应用。部分 su 实现（KernelSU 的某些构建）的 `su`
  只在内核或它自己的命名空间里可见，应用进程可能执行不到，此时日志里会留下
  `error=2, No such file or directory` 这样的原文，便于判断这是环境问题而不是应用逻辑问题。su 管理器
  （KernelSU / Magisk / APatch）都在保护名单里：规则误写到它们也不会被卸载。
- **Dhizuku 的设置门槛**：`dpm set-device-owner` 要求设备上没有任何账号；设置完成后 Dhizuku 自身不能被卸载，
  系统设置里会出现「由组织管理」。要撤销只能清除设备所有者（以 root 删除 `/data/system/device_owner*.xml`
  以及 `device_policies.xml` 里的相关条目后重启，或恢复出厂设置）。
- **监控状态有两个来源，需要对齐**：应用被更新或被系统杀死之后，持久化的「监控中」已经没有对应的服务在运行，
  而 Android 12+ 不允许后台广播（`MY_PACKAGE_REPLACED`）直接拉起前台服务，启动请求会被系统拒绝并静默失败。
  因此每次打开界面都会补一次服务启动（`MainActivity.alignMonitoringState`）；开机场景走 `BOOT_COMPLETED`
  （在豁免名单里）。
- 部分 ROM 会限制前台服务与自启动，需要在系统设置里把本应用的「自启动」「后台运行」放行。

## 免责声明

- 本应用在取得 root、设备所有者或 ADB 级权限后，会**按使用者自己维护的名单真实卸载应用**。卸载由系统执行，
  被删掉的应用及其数据通常无法由本应用恢复，请在开启监控前确认名单。
- 内置保护名单只覆盖删掉后会直接导致设备或后端失效的关键包，不代表名单绝对安全；名单内容与监控开关由使用者
  自行负责。
- 「计算器」这一名称和 `com.miui.calculator` 这一包名，是为了在个人设备上不显眼而做的伪装，与小米及任何厂商
  无关，也不代表任何官方应用。请勿用它冒充他人应用，或诱导他人安装。
- 作者不对使用本应用造成的设备异常、数据丢失、保修失效或其它后果承担责任。请只在你拥有、或已获明确授权管理的
  设备上使用。
- 本项目以 GPL-3.0 发布，不提供任何担保，详见 `LICENSE` 第 15、16 节。

## 许可证

GPL-3.0，全文见 [LICENSE](LICENSE)。

用到的第三方库：Dhizuku-API（[GPL-3.0](https://github.com/iamr0s/Dhizuku)）、
Stellar-API（[roro2239/Stellar-API](https://github.com/roro2239/Stellar-API)，Shizuku 的分支，上游 Stellar 采用 MPL-2.0）、
Shizuku（[Apache-2.0](https://github.com/RikkaApps/Shizuku)）。
`docs/third-party/Stellar-API-INTEGRATION_GUIDE.md` 是 Stellar 上游那份接入文档的原文，里面「许可证」一节说的
是它自己那个项目，不是本项目。

`keystore.properties` 和 `keystore/` 不在版本库里（`.gitignore` 已排除），因此 clone 下来只能出 debug 签名的包；
Release 里那份是按上面「release 签名」一节的命令自签的。
