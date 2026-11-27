package dev.maplesu.manager.runtime.ultra

import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UltraPolicyBackendDeploymentTest {
    private val reports = """
        printf 'MAPLESU_TOOL_HOST:updated hash\n'
        printf 'MAPLESU_TOOL_POLICY:updated hash\n'
        printf 'MAPLESU_TOOL_BUSYBOX:updated hash\n'
        printf 'MAPLESU_TOOL_SEPOLICY:updated hash\n'
        printf 'MAPLESU_TOOL_KSUD:updated hash\n'
        printf 'MAPLESU_TOOL_LINK:resetprop resetprop 1.0\n'
    """.trimIndent()

    @Test fun completePublicationRequiresAConfirmedSuccessfulShell() {
        assertNull(prepare(reports))
    }

    @Test fun sdkSuccessNeverOverridesARealShellFailureAfterTheToolReports() {
        assertEquals("MODULE_TOOLS_EXIT:7", prepare("$reports\nexit 7"))
    }

    @Test fun missingTailReceiptDoesNotReplayPublication() {
        var calls = 0
        val failure = UltraPolicyBackendDeployment.prepareTools("write-once", {
            calls++
            NativeReceipt.rootCommand(SuExecResult(0, "interrupted"))
        }, ::decode)
        assertEquals("MODULE_TOOLS_RESULT_UNCONFIRMED", failure)
        assertEquals(1, calls)
    }

    @Test fun interruptedPublicationRetainsCancellationWithoutReplaying() {
        var calls = 0
        try {
            val failure = UltraPolicyBackendDeployment.prepareTools("write-once", {
                calls++
                throw InterruptedException("publication interrupted")
            }, ::decode)
            assertEquals("MAPLESU_SDK_INTERRUPTED", failure)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, calls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun cancelledPublicationKeepsTheOriginalCancellation() {
        val cancelled = java.util.concurrent.CancellationException("publication cancelled")
        val observed = assertThrows(java.util.concurrent.CancellationException::class.java) {
            UltraPolicyBackendDeployment.prepareTools("write-once", { throw cancelled }, ::decode)
        }
        assertSame(cancelled, observed)
    }

    @Test fun incompleteToolReportsRemainAFailureAfterAZeroExit() {
        assertEquals("MODULE_TOOLS_RECEIPT_INVALID", prepare("printf 'partial\\n'"))
    }

    @Test fun successfulExitWithoutPolicyEntriesCannotCommitTheHelper() {
        assertEquals("MODULE_POLICY_TOOLS_NOT_REPORTED", prepare(reports.lines()
            .filterNot { it.contains("MAPLESU_TOOL_KSUD:") }.joinToString("\n")))
    }

    @Test fun transportExitRetainsTheActualScalarFailureWithoutReplaying() {
        var calls = 0
        val failure = UltraPolicyBackendDeployment.prepareTools("write-once", {
            calls++
            NativeReceipt.rootCommand(SuExecResult(2, "su: command is too long\n"))
        }, ::decode)
        assertEquals("MODULE_TOOLS_TRANSPORT:MAPLESU_ROOT_COMMAND_EXIT:exitCode=2:timedOut=false", failure)
        assertEquals(1, calls)
    }

    @Test fun validZeroShellMarkerNeverRepairsAnSdkTransportFailure() {
        var calls = 0
        val failure = UltraPolicyBackendDeployment.prepareTools("write-once", { script ->
            calls++
            val completed = sdk(script.replace("write-once", reports))
            completed.copy(ok = false, sdkError = "FIXTURE_SESSION_CLOSE", sdkErrorCode = -9,
                data = org.json.JSONObject(completed.data.toString()).put("rootCommand", org.json.JSONObject()
                    .put("phase", 11).put("systemError", 5).put("receiptComplete", true)))
        }, ::decode)
        assertTrue(failure!!.startsWith("MODULE_TOOLS_TRANSPORT:FIXTURE_SESSION_CLOSE"))
        assertTrue(failure.contains("phase=11"))
        assertTrue(failure.contains("systemError=5"))
        assertEquals(1, calls)
    }

    @Test fun lifecyclePublicationRetainsTheActualDotSuffixedStage() {
        assertEquals("MODULE_TOOLS_EXIT:7:module-run.sh",
            prepare("printf 'MAPLESU_TOOL_FAILURE:module-run.sh:7\\n'\nexit 7"))
    }

    @Test fun actualBundledAssetScriptExceedsSuCapacityAndUsesScopedSdkWiring() {
        val assetDirectory = File("src/main/assets/maplesu/modules")
        val lifecycle = mapOf("module-run.sh" to "run.sh", "module-boot.sh" to "boot.sh",
            "module-mount.sh" to "mount.sh").mapValues { (_, file) -> File(assetDirectory, file).readText() }
        val script = dev.maplesu.manager.modules.ModuleToolDeployment.script("/fixture/native",
            File(assetDirectory, "sepolicy.sh").readText(), File(assetDirectory, "ui-print.sh").readText(), lifecycle)
        assertTrue(script.toByteArray(Charsets.UTF_8).size > 4096)
        System.getProperty("maplesu.repair.fixture.script")?.let { File(it).writeText(script) }
        val production = File("src/main/java/dev/maplesu/manager/runtime/ultra/UltraPolicyBackendDeployment.kt").readText()
        assertTrue(production.contains("prepareTools(toolsScript, client::runRootCommand, client::decodeText)"))
        assertFalse(production.contains("channel.rootUid()"))
    }

    @Test fun sdkRunnerCarriesACompletePublicationLargerThanSuCommandCapacity() {
        val large = "#" + "x".repeat(8192) + "\n" + reports
        assertTrue(large.toByteArray().size > 4096)
        assertNull(prepare(large))
    }

    private fun prepare(script: String) = UltraPolicyBackendDeployment.prepareTools(script, ::sdk, ::decode)

    private fun decode(value: String?): String? = value?.let { URLDecoder.decode(it, "UTF-8") }

    private fun sdk(script: String): NativeReceipt {
        val executable = listOf("/bin/sh", "C:/Program Files/Git/bin/bash.exe")
            .firstOrNull { File(it).canExecute() }
        assumeTrue("A POSIX shell is required for the actual publication regression", executable != null)
        val process = ProcessBuilder(executable!!, "-s").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(script); it.write("\n") }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue("The publication did not complete", process.waitFor(5, TimeUnit.SECONDS))
        // The real prebuilt SDK ignores pclose's status and reports transport success.
        return NativeReceipt.rootCommand(SuExecResult(0, output))
    }
}
