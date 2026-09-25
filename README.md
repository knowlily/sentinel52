# 计算器（Sentinel52）

一份由使用者自己维护的包名名单：名单里的应用一旦被安装，就会被自动卸载。适用于反复被装回来的应用——预装机型的
推广组件、相互拉活的应用、更新后行为异常的应用。

已签名的 APK 放在 [Releases](https://github.com/knowlily/sentinel52/releases)，自行构建见「构建」。本应用会
真实卸载应用且不保留备份，安装前请先读一遍文末的「免责声明」。

> **关于名字**：装到手机上它显示为「计算器」，包名是 `com.miui.calculator`，这是有意的伪装，为的是让它出现在桌面
> 和 `pm list packages` 的输出里时不引人注意。代码里的包结构（namespace）仍是 `com.fiftytwo.sentinel`，那只影响
> dex 中的类名。代价是同一台设备不能与真正的 `com.miui.calculator` 共存，安装前需确认是否要替换掉系统自带计算器。

## 工作原理

1. 添加规则：完整的包名（`com.example.app`），或子包通配（`com.tencent.*`，只匹配子包，不匹配 `com.tencent` 本身）。
2. 打开监控后启动一个前台服务，从两条通道检查：**安装广播**（`PACKAGE_INSTALL` / `PACKAGE_ADDED`，安装完成的瞬间
   触发）与**定时轮询**（默认 3 秒，可选 1 / 3 / 5 / 10 / 30 秒，实际列举已安装包名，广播被 ROM 拦截时也能发现）。
3. 命中后由当前可用的特权后端执行卸载，随后再查询一次确认目标不存在，才在日志中记录「已卸载」。实测从命中到目标
   消失约 1 秒。
4. 结果分两处：主页的「卸载统计」（累计 / 今日 / 本次监控 / 失败 + 最近一次的包名与时间，单独记账并单独持久化，
   不复用日志条数，因此不受日志条数上限影响；「今日」按本地自然日归零），以及独立日志页（主页统计卡点「查看日志」
   进入，可按 全部 / 已卸载 / 命中 / 失败 / 其它 筛选）。

## 三个特权后端

静默卸载没有纯应用可以走的路径：`DELETE_PACKAGES` 的权限级别实测为 `signature|privileged|role`，
`adb shell pm grant` 也无法授予（返回 `Permission ... is managed by role`）。因此只有三种身份可行：

| 后端 | 身份 | 卸载如何执行 | 代价 |
| --- | --- | --- | --- |
| **Root**（首选） | uid 0 | `su -c "pm uninstall --user 0 '<包名>'"` | 设备需已 root（Magisk / KernelSU / APatch），并在 su 管理器里允许本应用 |
| **[Dhizuku](https://github.com/iamr0s/Dhizuku)** | 设备所有者 | 本应用的代码被 Dhizuku 加载进它自己的进程，用它的 Context 调 `PackageInstaller.uninstall` | 需在电脑上执行一次 `dpm set-device-owner`；设置时设备上不能有任何账号 |
| **[Stellar](https://github.com/roro2239/Stellar-API)** | ADB / Shell（uid 2000） | 起一个服务身份的进程执行 `pm uninstall` | 服务重启后需在应用内重新授权一次 |

选择顺序是**就绪优先于已安装，同级之间按 Root → Dhizuku → Stellar**（即枚举的声明顺序）。未授权的后端只会在界面上
提示还需要做什么，绝不会被用于执行卸载。三者都可用时选 Root：权限最高（系统应用也能卸载），不依赖常驻服务，也没有
设备所有者那套限制。

几处实现上的必要说明：

- **Dhizuku 必须在它自己的进程里执行**：`canSilentlyInstallPackage` 检查的是调用者 uid 对应的包是否为设备所有者，
  bind 之前的 `mAppOps.checkPackage` 又要求包名属于该 uid，本应用的进程两条都不满足，所以走 Dhizuku 的 UserService
  机制（它把 Context 传给被加载的类，代码于是运行在它的进程里）。对应实现是 `ISentinelDeviceAdmin.aidl`，其 AIDL
  事务码从 **20** 起编号——Dhizuku 服务端把 `FIRST_CALL_TRANSACTION+1/+2` 用作生命周期信号，从 1 开始会撞号。
- **不再反射隐藏 API**：原做法是反射 `IPackageManager.deletePackageVersioned` / `deletePackageAsUser`，实测在
  Android 15 / targetSdk 36 上签名在 API 35 变过，且即使签名完全一致，`Class.getMethod` 也会抛
  `NoSuchMethodException`——隐藏 API 限制使这些非 SDK 成员表现得「不存在」。该路径已删除。
- **root 的「是否已授权」只能实际执行一次**：Dhizuku / Stellar 的授权可用 binder 查询，su 的授权只有跑过才知道，
  而第一次执行会弹授权框。因此后台探测只做不执行任何操作的检查（可以 3 秒一次刷新），授权由使用者点按钮触发
  （执行 `su -c id`，最长 20 秒），已授权后每次探测复核一次（5 秒超时），被撤权则自动降级。「是否装了 su」也不能只看
  文件：KernelSU 打开「传统 SU 命令支持」后由内核拦截 `execve("/system/bin/su")` 提供，磁盘上可能没有这个文件，
  因此装了 su 管理器也算这条路存在，状态报「待授权」。失败时把异常原文写进日志（如 `error=2, No such file or
  directory`），真机上只能靠这行文字诊断。

## 使用前提

- Android 8.0（API 26）及以上。
- 三个后端中至少配置好一个：**Root**（设备已 root，在本应用点「申请 root」并在 su 授权框里允许）；**Dhizuku**
  （`com.rosan.dhizuku`，执行 `adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver` 设为
  设备所有者，该命令要求设备上没有任何账号，设置完成后系统显示「由组织管理」且 Dhizuku 自身无法被卸载）；或
  **Stellar**（`roro.stellar.manager`，按它自己的引导启动服务，Android 11+ 可在本机用无线调试启动）。
- 在本应用内点对应后端的授权按钮并允许。Root 与 Dhizuku 授权一次长期有效；Stellar 服务每次重启后需重新授权。

## 安全阀

| 机制 | 作用 |
| --- | --- |
| **内置保护名单** | `android`、`com.android.systemui`、`com.android.settings`、`com.android.shell`、`roro.stellar.manager`、`com.rosan.dhizuku`、`me.weishu.kernelsu` / `com.topjohnwu.magisk` / `me.bmax.apatch` 等关键包即使命中规则也不处理 |
| **自身保护** | 规则写成本应用自身时跳过 |
| **仅记录（演练模式）** | 命中只写日志、不执行卸载；确认名单无误后再关闭 |
| **规则可停用** | 每条规则有独立开关，不必删掉再加 |
| **未就绪时不硬上** | 三个后端都没就绪时只记日志说明原因，不会退化成「静默地什么都没做」；同时更换常驻通知文案并弹出一条可点击去授权的提醒（同一批包 10 分钟内只提醒一次，避免刷屏） |
| **忽略电池优化** | 界面上可直接申请加入白名单，避免 Doze 把轮询压到几十分钟一次 |

## 省电

监控是常驻行为，每一轮扫描的开销需要逐项核清。改造前，默认 3 秒一轮的循环里每轮都会 fork 一次 `su -c id` 复核
root、进行 3~4 次跨进程探测（Dhizuku 的 ContentProvider 与权限查询、Stellar 的 binder），并拉取一次整机包列表。
改造后：后端探测结果缓存 60 秒（`Privileged.PROBE_TTL_MS`，点「重新检测」、授权结束、卸载失败时强制重探）；root
授权复核缓存 5 分钟（`RootService.VERIFY_TTL_MS`，启动 root 进程是最贵的一步）；名单全是精确包名时只查询这几条，
出现通配规则才需要全量；优先本地 `getPackageInfo`（清单里已声明 `QUERY_ALL_PACKAGES`），特权服务只作兜底；屏幕
关闭时兜底轮询放宽到 30 秒（`PollingPolicy`，实时性由安装广播保证）；通知文案没变就不调用 `notify`，降级提醒没挂出
过就不调用 `cancel`。

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

图标由脚本生成，不是手工修改的。更换图标时替换源图（`design/ic_launcher_source.png`）再执行一次：

```bash
"D:/python/python.exe" tools/make_icons.py design/ic_launcher_source.png
```

### 依赖

```kotlin
implementation("io.github.iamr0s:Dhizuku-API:2.5.3")   // Maven Central / 阿里云镜像
implementation("com.github.roro2239:Stellar-API:1.0.3") // 只在 JitPack
```

Dhizuku 的版本**不能升到 2.5.4 及以上**：从该版本起制品是 Java 21 字节码（class file 65），本机 JDK 17 编译链无法
读取；2.5.3 是 Java 8 字节码，事务码与 2.12.0 版 Dhizuku 服务端一致，已在 Android 15 真机验证。Stellar 只在 JitPack
上发布，`settings.gradle.kts` 里已声明并按 group 收窄；本机还需在 `$GRADLE_USER_HOME/init.d/aliyun-mirror.gradle`
里放行一次，因为那个脚本会把工程里的仓库列表换成阿里云镜像（这台机器取不到 `dl.google.com`），而阿里云的 jitpack
镜像是 401。

### release 签名

release 走 R8（`isMinifyEnabled` + `isShrinkResources`），必须签名才能安装，因此仓库根有一个 `keystore.properties`
（**不进版本库**，`.gitignore` 已排除）指向自签密钥（`storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项）。
没有这个文件时 release 会退回 debug 签名，只是为了让 clone 下来也能直接出包。

```bash
keytool -genkeypair -keystore keystore/sentinel-release.jks -alias sentinel \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass <密码> -keypass <密码> \
  -dname "CN=Calculator, OU=Personal, O=Personal, C=CN"
```

**更换密钥后必须卸载重装**，签名不同的包不能覆盖安装。`proguard-rules.pro` 保留了三类不会被 R8 裁掉的东西：
`roro.stellar.**`、`DhizukuUserService` 及其实现的 AIDL 桩（Dhizuku 按类名反射加载它，混淆或裁掉即失效）、实现
`IInterface` 的本地桩。改完发布版请在真机上完整走一遍——release 包 `debuggable=false`，`run-as` 读不到它自己的
prefs，取证需从界面日志读取。

## 代码结构

```
core/         纯 Kotlin，无 Android 依赖，全部有 JVM 单测
  RuleMatcher            包名 vs pattern（精确 / 子包通配）
  PackageNameValidator   手输包名的校验与错误文案
  UninstallPlanner       谁该被卸载：命中、保护名单、自身、停用规则
  UninstallCommand       卸载命令拼装 + 包名引号转义（Root 与 Stellar 共用）
  RuleCodec / EventCodec 规则与日志的落盘编解码（坏行只丢那一行）
  PrivilegedState        后端状态判定 + BackendSelector（就绪优先，同级按枚举顺序）
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
data/SentinelStore     规则 / 日志 / 开关 / 卸载统计的唯一数据源
service/MonitorService 前台服务：轮询 + 安装广播，双通道扫描
service/BootReceiver   开机把监控接回去（上次是开着的才接）
ui/                    Compose 界面
docs/third-party/      Stellar 上游文档原文（不是本项目的文档）
tools/make_icons.py    从 design/ic_launcher_source.png 生成各密度图标
```

判定逻辑刻意留在 `core/`：它决定使用者的设备上会被删掉什么，而且完全不需要 Android 环境，具备可测试性。

## 已知限制

- **Stellar 的 shell 身份卸不掉系统应用**（`pm uninstall` 对它们同样无效），日志里会记为失败，需要改用 Root 或
  Dhizuku 的身份。
- **Root 依赖设备已 root**。部分 su 实现（KernelSU 的某些构建）的 `su` 只在内核或它自己的命名空间里可见，应用进程
  可能执行不到，此时日志里会留下 `error=2, No such file or directory` 这样的原文。
- **Dhizuku 设置门槛**：要求设备上没有任何账号，且设置后 Dhizuku 自身不能被卸载、系统设置里出现「由组织管理」；
  要撤销只能清除设备所有者（以 root 删除 `/data/system/device_owner*.xml` 与 `device_policies.xml` 里的相关条目后
  重启，或恢复出厂设置）。
- **监控状态有两个来源，需要对齐**：应用被更新或被系统杀死后，持久化的「监控中」已无对应服务在运行，而 Android 12+
  不允许后台广播（`MY_PACKAGE_REPLACED`）直接拉起前台服务，因此每次打开界面都会补一次服务启动
  （`MainActivity.alignMonitoringState`），开机场景走 `BOOT_COMPLETED`。
- 部分 ROM 会限制前台服务与自启动，需要在系统设置里把本应用的「自启动」「后台运行」放行。

## 免责声明

- 本应用在取得 root、设备所有者或 ADB 级权限后，会按使用者自己维护的名单真实卸载应用。卸载由系统执行，被删掉的
  应用及其数据通常无法由本应用恢复，请在开启监控前确认名单。
- 内置保护名单只覆盖删掉后会直接导致设备或后端失效的关键包，不代表名单绝对安全；名单内容与监控开关由使用者自行负责。
- 「计算器」这一名称和 `com.miui.calculator` 这一包名是为在个人设备上不显眼而做的伪装，与小米及任何厂商无关，也
  不代表任何官方应用。请勿用它冒充他人应用，或诱导他人安装。
- 作者不对使用本应用造成的设备异常、数据丢失、保修失效或其它后果承担责任。请只在你拥有、或已获明确授权管理的设备
  上使用。
- 本项目以 GPL-3.0 发布，不提供任何担保，详见 `LICENSE` 第 15、16 节。

## 许可证

GPL-3.0，全文见 [LICENSE](LICENSE)。用到的第三方库：Dhizuku-API（[GPL-3.0](https://github.com/iamr0s/Dhizuku)）、
Stellar-API（[roro2239/Stellar-API](https://github.com/roro2239/Stellar-API)，Shizuku 的分支，上游 Stellar 采用
MPL-2.0）、Shizuku（[Apache-2.0](https://github.com/RikkaApps/Shizuku)）。
`docs/third-party/Stellar-API-INTEGRATION_GUIDE.md` 是 Stellar 上游那份接入文档的原文，里面「许可证」一节说的是
它自己那个项目，不是本项目。`keystore.properties` 与 `keystore/` 不在版本库里，因此 clone 下来只能出 debug 签名的
包；Release 里那份是按上面「release 签名」一节的命令自签的。
