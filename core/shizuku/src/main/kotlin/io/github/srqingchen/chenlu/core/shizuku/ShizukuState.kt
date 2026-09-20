package io.github.srqingchen.chenlu.core.shizuku

/** Shizuku 通道状态（驱动引擎状态与 UI 引导）。 */
sealed interface ShizukuState {
    /** 未安装 Shizuku 应用。 */
    data object NotInstalled : ShizukuState

    /** 已安装但 server 未运行（重启后需重新启动）。 */
    data object NotRunning : ShizukuState

    /** server 运行中，等待用户授权本应用。 */
    data object AwaitingPermission : ShizukuState

    /** 正在拉起 UserService。 */
    data object Connecting : ShizukuState

    /** 注入服务就绪。 */
    data class Ready(val uid: Int) : ShizukuState

    /** 明确失败（版本过旧、绑定失败等）。 */
    data class Failed(val reason: String) : ShizukuState
}
