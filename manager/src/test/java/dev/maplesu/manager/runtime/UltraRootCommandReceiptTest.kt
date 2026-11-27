package dev.maplesu.manager.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UltraRootCommandReceiptTest {
    @Test fun realShellPreservesStdoutAndItsNonzeroExitInsteadOfTheSdkStatus() {
        val result = shell("printf 'line\\n\\n'; exit 7")
        assertFalse(result.ok)
        assertEquals(7, result.code)
        assertEquals("line\n\n", result.output)
        assertTrue(result.commandCompleted)
    }

    @Test fun sdkStdoutPipeAlsoReceivesErrorsBeforeTheLastCommand() {
        val result = shell("printf 'early failure' >&2\nexit 7")
        assertEquals(7, result.code)
        assertEquals("early failure", result.output)
        assertTrue(result.commandCompleted)
    }

    @Test fun explicitExecAndSetEStillReturnTheOriginalCommandStatus() {
        assertEquals("replaced", shell("exec printf replaced").output)
        val result = shell("set -e\nprintf before\nfalse\nprintf after")
        assertFalse(result.ok)
        assertEquals(1, result.code)
        assertEquals("before", result.output)
    }

    @Test fun realShellRetainsTheFullExitStatusRange() {
        listOf(0, 1, 2, 127, 255).forEach { code ->
            val result = shell("exit $code")
            assertEquals(code, result.code)
            assertEquals(code == 0, result.ok)
        }
    }

    @Test fun zeroAndEmptyStdoutRetainTheirExactShape() {
        val empty = shell("exit 0")
        assertTrue(empty.ok)
        assertEquals("", empty.output)
        assertEquals("hello", shell("printf hello").output)
        assertEquals("hello\n", shell("printf 'hello\\n'").output)
    }

    @Test fun foreignOrTruncatedReceiptsNeverClaimCommandCompletion() {
        val receipt = UltraRootCommandReceipt("id", "test-call")
        listOf("", "Permission denied", "\nMAPLESU_ROOT_EXIT:another-call:0\n",
            "\nMAPLESU_ROOT_EXIT:test-call:0", "\nMAPLESU_ROOT_EXIT:test-call:0\nextra",
            "\nMAPLESU_ROOT_EXIT:test-call:999\n", "\nMAPLESU_ROOT_EXIT:test-call:00\n").forEach {
            val result = receipt.decode(it)
            assertFalse(result.ok)
            assertFalse(result.commandCompleted)
            assertEquals("ULTRA_ROOT_COMMAND_RESULT_UNCONFIRMED", result.detail)
        }
    }

    @Test fun markerLikeCommandOutputIsPreservedAndDoesNotChangeTheRealStatus() {
        val result = shell("printf 'MAPLESU_ROOT_EXIT:forged:0\\n'; false")
        assertFalse(result.ok)
        assertEquals(1, result.code)
        assertEquals("MAPLESU_ROOT_EXIT:forged:0\n", result.output)
    }

    @Test fun aConfirmedShellFailureKeepsRootAvailabilityAndIsNeverReplayed() {
        var invalidations = 0
        var calls = 0
        val ultra = UltraEnvRootExecutor(
            runner = { calls++; shell(it) }, running = { true }, onFailure = { invalidations++ },
        )
        val legacy = object : RootExecutor {
            override val backend = RootBackend.LITE_KERNEL
            override fun available() = true
            override fun run(script: String): RootExecResult = error("submitted command was replayed")
        }
        val router = RootExecutionRouter(ultra, modeProvider = { dev.maplesu.manager.workmode.WorkMode.LITE })
        val result = router.execute("printf once; exit 7")
        assertFalse(result.ok)
        assertEquals(7, result.code)
        assertEquals("once", result.output)
        assertEquals(1, calls)
        assertEquals(0, invalidations)
        assertTrue(router.capability(forceRefresh = true).available)
    }

    @Test fun anUnconfirmedCommandInvalidatesAvailabilityWithoutReplaying() {
        var calls = 0
        var invalidations = 0
        val ultra = UltraEnvRootExecutor(
            runner = { calls++; UltraRootCommandReceipt(it, "lost").decode("truncated") },
            running = { true }, onFailure = { invalidations++ },
        )
        val result = RootExecutionRouter(ultra).execute("write-once")
        assertFalse(result.ok)
        assertEquals("ULTRA_ROOT_COMMAND_RESULT_UNCONFIRMED", result.detail)
        assertEquals(1, calls)
        assertEquals(1, invalidations)
    }

    @Test fun unsubmittedAdmissionFailuresKeepAvailabilityWithoutReplayingCommands() {
        for (reason in listOf("MAPLESU_SDK_BUSY", "MAPLESU_SDK_INTERRUPTED")) {
            var invalidations = 0
            var calls = 0
            var available = true
            val ultra = UltraEnvRootExecutor(
                runner = { calls++; UltraRootCommandResult(false, -1001, detail = "ULTRA_ROOT_FAILED:$reason") },
                running = { available },
                onFailure = { available = false; invalidations++ },
            )
            val router = RootExecutionRouter(ultra,
                modeProvider = { dev.maplesu.manager.workmode.WorkMode.LITE }, ultraIdentity = { true })
            val result = router.execute("read-only-startup-probe")
            assertFalse(result.ok)
            assertEquals("ULTRA_ROOT_FAILED:$reason", result.detail)
            assertEquals(1, calls)
            assertEquals("Unsubmitted admission failure triggered a re-probe storm", 0, invalidations)
            assertTrue(router.capability(forceRefresh = true).available)
        }
    }

    @Test fun sdkFailureRetainsOutputAndScalarFactsWithoutReasonOrRaw() {
        val receipt = UltraRootCommandReceipt("sensitive command", "failed-call")
        val data = org.json.JSONObject().put("result", "captured%0A")
            .put("rootCommand", org.json.JSONObject().put("phase", 9).put("systemError", 5)
                .put("shellStatus", 0).put("shellError", 0).put("transportPhase", 0)
                .put("transportError", 0).put("childStatus", 0).put("childStatusValid", true)
                .put("shellCloseAttempted", true).put("receiptComplete", true)
                .put("reason", "private credential"))
        val native = dev.maplesu.manager.runtime.ultra.NativeReceipt(1, "maplesu_ultra", 1,
            "run_root_cmd", false, "ERR_MODULE_READ_FILE", -19933, "private reason", data, "private raw")
        val result = receipt.decodeSdk(native) { java.net.URLDecoder.decode(it.orEmpty(), "UTF-8") }
        assertFalse(result.ok)
        assertFalse(result.commandCompleted)
        assertEquals(-19933, result.code)
        assertEquals("captured\n", result.output)
        assertTrue(result.detail.contains("sdkCode=-19933;phase=9;systemError=5"))
        assertTrue(result.detail.contains("receiptComplete=1"))
        assertFalse(result.detail.contains("private"))
        assertFalse(result.detail.contains("sensitive"))
    }

    @Test fun confirmedShellMarkerNeverRepairsAnSdkCleanupFailure() {
        val receipt = UltraRootCommandReceipt("write once", "cleanup-call")
        val output = "written\nMAPLESU_ROOT_EXIT:cleanup-call:0\n"
        val native = dev.maplesu.manager.runtime.ultra.NativeReceipt(1, "maplesu_ultra", 1,
            "run_root_cmd", false, "ERR_MODULE_TEST_CHANNEL", -19999, null,
            org.json.JSONObject().put("result", output), "")
        val result = receipt.decodeSdk(native) { it }
        assertFalse(result.ok)
        assertFalse(result.commandCompleted)
        assertEquals(-19999, result.code)
        assertEquals("written", result.output)
    }

    @Test fun admissionAndMalformedDiagnosticsKeepTheirOriginalFailureProjection() {
        val receipt = UltraRootCommandReceipt("id", "admission-call")
        val busy = dev.maplesu.manager.runtime.ultra.NativeReceipt.bridgeFailure("run_root_cmd",
            "MAPLESU_SDK_BUSY", "private reason")
        assertEquals("ULTRA_ROOT_FAILED:MAPLESU_SDK_BUSY", receipt.decodeSdk(busy) { it }.detail)
        val hostile = busy.copy(sdkError = "not a token; credential", data = org.json.JSONObject()
            .put("result", "partial")
            .put("rootCommand", org.json.JSONObject().put("phase", "credential").put("systemError", 1.5)))
        val result = receipt.decodeSdk(hostile) { it }
        assertEquals("ULTRA_ROOT_FAILED:UNKNOWN;sdkCode=-1001", result.detail)
        assertEquals("partial", result.output)
        assertFalse(result.commandCompleted)
    }

    @Test fun aFailedZeroCodeReceiptStaysFailureAtTheRouterBoundary() {
        val native = dev.maplesu.manager.runtime.ultra.NativeReceipt(1, "maplesu_ultra", 1,
            "run_root_cmd", false, "OK", 0, "Root proof incomplete", null, "")
        val ultra = UltraEnvRootExecutor(runner = {
            UltraRootCommandReceipt(it, "failed-zero").decodeSdk(native) { value -> value }
        }, running = { true })
        val result = ultra.run("id")
        assertFalse(result.ok)
        assertEquals(-1, result.code)
        assertEquals("ULTRA_ROOT_FAILED:OK;sdkCode=0", result.detail)
    }

    private fun shell(script: String): UltraRootCommandResult {
        val executable = listOf("/bin/sh", "C:/Program Files/Git/bin/bash.exe")
            .firstOrNull { File(it).canExecute() }
        assumeTrue("A POSIX shell is required for the actual script regression", executable != null)
        val receipt = UltraRootCommandReceipt(script)
        // The prebuilt SDK appends this redirection and reads only popen's stdout.
        val process = ProcessBuilder(executable!!, "-c", receipt.command + " 2>&1").start()
        assertTrue("The command did not complete", process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue()) // The outer printf succeeds, just as the SDK transport does.
        return receipt.decode(process.inputStream.bufferedReader().use { it.readText() })
    }
}
