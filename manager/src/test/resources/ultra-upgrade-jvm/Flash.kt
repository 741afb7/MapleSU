package com.maplesu.core.ui.util
import dev.maplesu.manager.runtime.ultra.Fixture
import dev.maplesu.manager.installer.InstallTargetMode
import dev.maplesu.manager.installer.LenovoSigningOptions
enum class FlashOutcome { WRITTEN, RECOVERY_REQUIRED, NOT_WRITTEN, FAILED }
data class FlashResult(val code: Int = 0, val err: String = "", val showReboot: Boolean = false,
                  val outcome: FlashOutcome = FlashOutcome.WRITTEN)
val ksuApp get() = Fixture.context
fun installStockTarget(target: InstallTargetMode, partition: String, bootUri: Any?, taskId: String,
    restoreOriginal: Boolean, originalManager: Any?, conflictAccepted: Boolean, variantId: Any?,
    normalOptions: Any?, kmiCatalog: Any?, onStdout: (String) -> Unit, onStderr: (String) -> Unit,
    onStage: (Any) -> Unit, selectedImageOnly: Boolean, lenovoSigning: LenovoSigningOptions,
    initBootArtifact: Any?, verifyScope: () -> Unit): FlashResult {
    Fixture.calls += "kernel-install"; Fixture.kernelAttempts += partition to taskId
    verifyScope(); Fixture.onKernelInstall?.invoke()
    return if (Fixture.kernelOutcomes.isEmpty()) Fixture.kernelOutcome else Fixture.kernelOutcomes.removeFirst()
}
