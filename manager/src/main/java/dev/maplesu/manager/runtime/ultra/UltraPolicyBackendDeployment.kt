package dev.maplesu.manager.runtime.ultra

import android.content.Context
import com.maplesu.core.BuildConfig
import dev.maplesu.manager.modules.ModuleToolDeployment
import dev.maplesu.manager.runtime.UltraRootCommandReceipt
import java.io.File
import java.util.concurrent.CancellationException

internal object UltraPolicyBackendDeployment {
    // Executed only before submitting a new helper. Page reads and startup
    // probes never publish a backend or hash the library.
    fun prepare(context: Context, client: UltraRuntimeClient): String? {
        // A Boot submission may reboot immediately. Existing module scripts must
        // receive this build's standard policy gate before the new helper starts.
        val toolsScript = try {
            ModuleToolDeployment.script(context)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return "MODULE_TOOLS_ASSET_UNAVAILABLE"
        }
        // Keep the scoped SDK identity used by installation preparation. The full
        // asset script exceeds su's 4096-byte -c contract even when id -u succeeds.
        // Select this transport before publication; never replay uncertain writes.
        val toolsFailure = prepareTools(toolsScript, client::runRootCommand, client::decodeText)
        if (toolsFailure != null) return toolsFailure
        val expected = BuildConfig.ULTRA_POLICY_BACKEND_SHA256
        val size = BuildConfig.ULTRA_POLICY_BACKEND_BYTES
        if (expected.isEmpty() && size == 0L) return null
        return try {
            val source = File(context.applicationInfo.nativeLibraryDir, "libmaplesu_policy_boot.so")
            val bytes = UltraPolicyBackendArtifact.read(source, expected, size)
            val receipt = client.publishPolicyBackend(expected, bytes)
            if (receipt.succeeded && receipt.string("sha256") == expected) null
            else "POLICY_BACKEND_PUBLICATION_FAILED"
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is InterruptedException) Thread.currentThread().interrupt()
            "POLICY_BACKEND_ASSET_UNVERIFIED"
        }
    }

    internal fun prepareTools(script: String, run: (String) -> NativeReceipt,
                              decode: (String?) -> String?): String? {
        try {
            val command = UltraRootCommandReceipt(script)
            var tools = run(command.command)
            var admissionRetries = 0
            // Busy is returned before SDK submission. Never replay an executed or uncertain script.
            while (tools.sdkError == "MAPLESU_SDK_BUSY" && admissionRetries < 2 &&
                !Thread.currentThread().isInterrupted) {
                admissionRetries++
                tools = run(command.command)
            }
            if (!tools.succeeded) {
                // Preserve scalar transport facts without logging commands, paths or output.
                // A shell marker never repairs failed transport/session cleanup.
                val failure = command.decodeSdk(tools, decode)
                return "MODULE_TOOLS_TRANSPORT:${tools.sdkError}" +
                    tools.int("exitCode")?.let { ":exitCode=$it" }.orEmpty() +
                    tools.bool("timedOut")?.let { ":timedOut=$it" }.orEmpty() +
                    (if (tools.obj("rootCommand") != null) ":${failure.detail}" else "")
            }
            val output = decode(tools.string("result")) ?: return "MODULE_TOOLS_RECEIPT_MISSING"
            val completed = command.decode(output)
            if (!completed.commandCompleted) return "MODULE_TOOLS_RESULT_UNCONFIRMED"
            if (!completed.ok) {
                val stage = Regex("(?m)^MAPLESU_TOOL_FAILURE:([a-z][a-z0-9.-]{0,63}):${completed.code}$")
                    .findAll(completed.output).lastOrNull()?.groupValues?.get(1)
                return "MODULE_TOOLS_EXIT:${completed.code}" + stage?.let { ":$it" }.orEmpty()
            }
            try {
                ModuleToolDeployment.parse(completed.output.lines())
            } catch (_: IllegalStateException) {
                return "MODULE_TOOLS_RECEIPT_INVALID"
            } catch (_: IllegalArgumentException) {
                return "MODULE_TOOLS_RECEIPT_INVALID"
            }
            if (!completed.output.contains("MAPLESU_TOOL_SEPOLICY:updated ") ||
                !completed.output.contains("MAPLESU_TOOL_KSUD:updated ")) return "MODULE_POLICY_TOOLS_NOT_REPORTED"
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                return "MAPLESU_SDK_INTERRUPTED"
            }
            return "MODULE_TOOLS_PUBLICATION_FAILED"
        }
        return null
    }
}
