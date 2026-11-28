package dev.maplesu.manager.workmode.service

import android.content.Context
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.workmode.detect.UltraState
import dev.maplesu.manager.workmode.switch.HookResult
import dev.maplesu.manager.workmode.switch.UltraEnvironmentPort
import dev.maplesu.manager.workmode.switch.UltraEnvSwitchCoordinator
import dev.maplesu.manager.workmode.switch.UltraSwitchCodes

/**
 * P0 Ultra 环境生产端口：**只读**现有只读探针（[UltraEnvironmentProbe]）。
 *
 * `suspend()/restore()` 的具体实现归 Ultra runtime owner（激活/停用序列不在本任务范围）。
 * 在 owner 提供能力之前，两个动作都返回**明确错误**（`ULTRA_SUSPEND_UNSUPPORTED` /
 * `ULTRA_RESTORE_UNSUPPORTED`）：A→B 会被 `UltraEnvSwitchPolicy` 的 MustSuspendUltraFirst 先阻断，
 * 不会走到 suspend；B→A 的 restore 失败 → facade 返回 `RECOVERY_REQUIRED` 而不是成功。
 */
class AndroidUltraEnvironmentPort(
    context: Context,
    private val readProbeState: () -> UltraEnvironmentProbe.State = { UltraEnvironmentProbe.currentSnapshot(context.applicationContext).state },
) : UltraEnvironmentPort {

    private val appContext = context.applicationContext

    override fun state(): UltraState = runCatching { map(readProbeState()) }
        .getOrDefault(UltraState.UNKNOWN)

    override fun kernelRootVerified(): Boolean = UltraEnvironmentProbe.currentSnapshot(appContext).kernelRootVerified

    override fun componentFailure(): HookResult? {
        val snapshot = UltraEnvironmentProbe.currentSnapshot(appContext)
        val identified = snapshot.managerOwnerIdentity != null || snapshot.kernelRootVerified ||
            snapshot.state == UltraEnvironmentProbe.State.Running || snapshot.state == UltraEnvironmentProbe.State.Fault
        if (!identified) return null
        val code = when {
            snapshot.state == UltraEnvironmentProbe.State.Fault -> UltraSwitchCodes.ULTRA_ENV_FAULT
            snapshot.state != UltraEnvironmentProbe.State.Running && !snapshot.kernelRootVerified ->
                UltraSwitchCodes.ULTRA_ENV_RUNTIME_UNAVAILABLE
            else -> return null
        }
        return HookResult.failed(code, snapshot.detail.orEmpty())
    }

    private fun map(state: UltraEnvironmentProbe.State): UltraState = when (state) {
        UltraEnvironmentProbe.State.Running -> UltraState.RUNNING
        UltraEnvironmentProbe.State.NotInstalled -> UltraState.NOT_INSTALLED
        UltraEnvironmentProbe.State.Fault -> UltraState.FAULT
        UltraEnvironmentProbe.State.NoKey -> UltraState.NO_KEY
        UltraEnvironmentProbe.State.Unknown -> UltraState.UNKNOWN
    }

    override fun suspend(): HookResult = HookResult.failed(
        UltraSwitchCodes.ULTRA_SUSPEND_UNSUPPORTED,
        "Ultra suspend/stop is owned by Ultra runtime (not wired); A→B must stay blocked",
    )

    override fun restore(): HookResult = HookResult.failed(
        UltraSwitchCodes.ULTRA_RESTORE_UNSUPPORTED,
        "Ultra restore/installEnvironment(Boot) is owned by Ultra runtime (not wired)",
    )

    /** 仅诊断用：主动刷新只读探针（后台线程）。 */
    fun refresh(): UltraState = runCatching {
        UltraEnvironmentProbe.refresh(appContext)
        state()
    }.getOrDefault(UltraState.UNKNOWN)
}

/** 生产装配；构造失败返回 null（不给假判定）。 */
fun createProductionUltraEnvCoordinator(context: Context): UltraEnvSwitchCoordinator? = runCatching {
    UltraEnvSwitchCoordinator(AndroidUltraEnvironmentPort(context))
}.getOrNull()
