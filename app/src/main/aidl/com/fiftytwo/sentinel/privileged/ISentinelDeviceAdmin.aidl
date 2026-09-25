// 我们的代码会被 Dhizuku 加载进**它自己的进程**（设备所有者进程）里执行，
// 所以接口要独立成 AIDL：客户端（本应用）通过它把卸载请求发给那个进程。
//
// 事务码从 20 起：Dhizuku 服务端用 FIRST_CALL_TRANSACTION+1/+2 当用户服务的
// 生命周期信号（见 dhizuku-server_api 的 UserService.transact），AIDL 默认从 1 起编号，
// 会和它撞号。
package com.fiftytwo.sentinel.privileged;

interface ISentinelDeviceAdmin {
    /** 在 Dhizuku 进程里调 PackageInstaller.uninstall —— 调用者是设备所有者，所以静默。 */
    boolean uninstall(String packageName, boolean allUsers) = 20;

    /** 回一句执行身份（uid + 进程用的 Context），日志里用它证明「确实跑在 DO 进程里」。 */
    String whoAmI() = 21;
}
