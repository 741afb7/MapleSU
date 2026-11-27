package dev.maplesu.manager.runtime.ultra

import android.content.Context
import dev.maplesu.manager.runtime.ultra.bridge.NativeBridge
import dev.maplesu.manager.parasite.ParasiteNativeCall
import dev.maplesu.manager.parasite.ParasiteUltraApiAdapter
import dev.maplesu.manager.parasite.ParasiteSdkPort
import dev.maplesu.manager.parasite.UltraAvailability
import dev.maplesu.manager.parasite.UltraLinkedParasiteSdkPort
import dev.maplesu.manager.parasite.UnavailableParasiteSdkPort
import dev.maplesu.manager.parasite.selectProductionParasitePort
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate

/**
 * 同代 Ultra SDK 的免 Key 寄生接线，root 通道使用当前任务身份。
 * 旧库缺符号时仍映射 ULTRA_API_UNLINKED，不把安装回执等同于宿主已加载。
 */
class NativeParasiteCall : ParasiteNativeCall {
    override fun precheck(targetPidCmdline: String): String? =
        NativeBridge.parasitePrecheckApp(targetPidCmdline)

    override fun implantApp(targetPidCmdline: String, soPath: String): String? =
        NativeBridge.parasiteImplantApp(targetPidCmdline, soPath)

    override fun implantSuEnv(
        targetPidCmdline: String,
        soPath: String,
        suDir: String,
    ): String? = NativeBridge.parasiteImplantSuEnv(targetPidCmdline, soPath, suDir)
}

/** Ultra 运行态 → 寄生端口可用性（纯映射，主机可测）。 */
object ParasiteRuntimeAvailability {
    fun current(): UltraAvailability = fromUltraState(UltraEnvironmentProbe.current())

    fun fromUltraState(state: UltraEnvironmentProbe.State): UltraAvailability = when (state) {
        UltraEnvironmentProbe.State.Running -> UltraAvailability.RUNNING
        UltraEnvironmentProbe.State.NotInstalled -> UltraAvailability.NOT_RUNNING
        UltraEnvironmentProbe.State.Fault -> UltraAvailability.NOT_RUNNING
        UltraEnvironmentProbe.State.Unknown, UltraEnvironmentProbe.State.NoKey -> UltraAvailability.UNLINKED
    }
}

/**
 * 生产端口：当前任务的 Ultra SDK 回执 → 结构化结果。
 */
fun productionParasiteSdkPort(
    native: ParasiteNativeCall = NativeParasiteCall(),
    availability: () -> UltraAvailability = { ParasiteRuntimeAvailability.current() },
    quiescing: () -> Boolean = { LiteImplantQuiesceGate.isBlocked() },
): ParasiteSdkPort = UltraLinkedParasiteSdkPort(
    api = ParasiteUltraApiAdapter(native),
    availabilityProvider = availability,
    quiescing = quiescing,
)

/**
 * A（Ultra / LITE）走免 Key Ultra SDK；KSU/未判定保持不可用。
 * 当前任务身份由免 Key SDK 核验。
 */
fun productionParasiteSdkPort(
    context: Context,
    mode: WorkMode? = ModeRuntime.active,
): ParasiteSdkPort {
    return selectProductionParasitePort(
        mode = mode,
        litePort = {
            productionParasiteSdkPort()
        },
        unavailablePort = { UnavailableParasiteSdkPort(UltraAvailability.RUNNING) },
    )
}
