package dev.maplesu.manager.installer
import android.content.Context
import dev.maplesu.manager.runtime.ultra.Fixture
enum class InstallTargetMode { ULTRA }
class LenovoSigningOptions
data class InstallerSnapshot(val slotSuffix: String = "_a", val kernelRelease: String = "6.6.89-android15-8",
    val firmware: String = "fixture-firmware", val partitions: Map<String, String> = mapOf("boot" to "boot-sha"))
class TaskStatus(private val state: String) { fun getString(key: String): String? = if (key == "state") state else null }
data class InstalledUltraCarrier(val bootId: String, val updateRequired: Boolean = false)
object UltraKernelEnvironmentUpgrade {
    fun observeInstalled(context: Context): InstalledUltraCarrier? = null
    fun required(context: Context): Boolean { Fixture.calls += "carrier"; Fixture.onCarrier?.invoke(); return Fixture.kernelRequired }
    fun signingOptions(context: Context): LenovoSigningOptions {
        Fixture.calls += "kernel-signing"; Fixture.onKernelSigning?.invoke(); return LenovoSigningOptions()
    }
}
object InstallerServiceClient {
    fun observeBootChain(context: Context): InstallerSnapshot {
        Fixture.calls += "kernel-observe"; Fixture.onKernelObserve?.invoke(); return Fixture.kernelSnapshot
    }
    fun installedCarrierPartition(context: Context, observed: InstallerSnapshot): String? = Fixture.carrierPartition
    fun installationStatus(context: Context, taskId: String, rootTarget: InstallTargetMode): TaskStatus {
        Fixture.calls += "kernel-status"; Fixture.onKernelStatus?.invoke(); return TaskStatus(Fixture.kernelTaskState)
    }
    fun capturePairedBoot(context: Context, expected: InstallerSnapshot, destination: java.io.File): java.io.File {
        Fixture.calls += "paired-boot"
        destination.parentFile.mkdirs(); destination.writeText("fixture-paired-boot")
        Fixture.pairedCaptured = destination
        return destination
    }
    fun rebootAfterInstallation(context: Context, taskId: String): Boolean { Fixture.rebootTask = taskId; return true }
}
fun rethrowInstallerCancellation(error: Throwable) {
    val cancelled = generateSequence(error) { it.cause }.take(8).firstOrNull {
        it is java.util.concurrent.CancellationException || it is InterruptedException
    } ?: return
    if (cancelled is InterruptedException) Thread.currentThread().interrupt()
    throw cancelled
}
fun installerFailureExitCode(error: Throwable): Int = 1
