# Stellar：权限回调、provider 与远程 binder 都靠类名/接口名，不能被混淆或裁掉
-keep class roro.stellar.** { *; }
-keep class com.stellar.** { *; }
-dontwarn roro.stellar.**
-dontwarn com.stellar.**
# Stellar 的 aidl 模块里还留着 Shizuku 时代的服务端接口名，混淆器看不到实现会报警告
-dontwarn moe.shizuku.**

# Dhizuku：用户服务类是 Dhizuku 用 createPackageContext + 反射从我们的 APK 里
# loadClass 出来的（构造函数也要能被 getConstructor 找到），所以类名和构造不能动。
# 它同时还按包名/类名认我们的 AIDL 桩，一并保住。
-keep class com.fiftytwo.sentinel.privileged.DhizukuUserService { *; }
-keep class com.fiftytwo.sentinel.privileged.ISentinelDeviceAdmin { *; }
-keep class com.fiftytwo.sentinel.privileged.ISentinelDeviceAdmin$Stub { *; }
-keep class com.rosan.dhizuku.** { *; }
-dontwarn com.rosan.dhizuku.**

# 我们靠反射调的隐藏 API 全在 framework 里，不需要 keep；但实现 IInterface 的本地桩要保住
-keepclassmembers class * implements android.os.IInterface {
    <methods>;
}
