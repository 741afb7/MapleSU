package dev.maplesu.manager.runtime

import android.os.Process as AndroidProcess
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MapleSuExecutorAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val executor = MapleSuCommandExecutor(context)

    private data class Result(val code: Int, val out: ByteArray, val err: ByteArray)

    private fun collect(process: Process, input: ByteArray = byteArrayOf()): Result {
        var out = byteArrayOf()
        var err = byteArrayOf()
        val stdout = thread { out = process.inputStream.use { it.readBytes() } }
        val stderr = thread { err = process.errorStream.use { it.readBytes() } }
        try {
            process.outputStream.use { if (input.isNotEmpty()) it.write(input) }
            assertTrue("Native command timed out", process.waitFor(10, TimeUnit.SECONDS))
            stdout.join(5000)
            stderr.join(5000)
            assertFalse(stdout.isAlive)
            assertFalse(stderr.isAlive)
            return Result(process.exitValue(), out, err)
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun nativeProcessKeepsApplicationUid() {
        val result = collect(executor.startUnprivileged(listOf("/system/bin/id", "-u")))
        assertEquals(0, result.code)
        assertEquals(AndroidProcess.myUid().toString(), result.out.decodeToString().trim())
        assertTrue(AndroidProcess.myUid() >= 10000)
    }

    @Test fun literalArgumentsStderrAndExitCode() {
        val result = collect(executor.startUnprivileged(listOf("/system/bin/sh", "-c",
            "printf '%s' \"\$1\"; printf err >&2; exit 37", "fixture", "\$(id); * 你好")))
        assertEquals(37, result.code)
        assertEquals("\$(id); * 你好", result.out.decodeToString())
        assertEquals("err", result.err.decodeToString())
    }

    @Test fun callerCanTerminateAndWaitForAnOrdinaryProcess() {
        val process = executor.startUnprivileged(listOf("/system/bin/sleep", "30"))
        try {
            process.outputStream.close()
            assertFalse("waitFor reported an early exit", process.waitFor(100, TimeUnit.MILLISECONDS))
            assertTrue(process.isAlive)
            process.destroyForcibly()
            assertTrue("Terminated command timed out", process.waitFor(10, TimeUnit.SECONDS))
            assertFalse(process.isAlive)
            assertNotEquals(0, process.exitValue())
            // Android may close process streams on destruction; successful termination does not promise readable EOF.
        } finally {
            process.destroyForcibly()
            process.outputStream.close()
            process.inputStream.close()
            process.errorStream.close()
        }
    }

    @Test fun execFailuresReturnFailureCodes() {
        val missing = collect(executor.startUnprivileged(listOf("/maplesu-missing-executable")))
        assertEquals(127, missing.code)
        assertEquals(0, missing.out.size)
        val directory = collect(executor.startUnprivileged(listOf(context.filesDir.absolutePath)))
        assertEquals(126, directory.code)
        assertEquals(0, directory.out.size)
    }

    @Test fun binaryStdinAndStdout() {
        val input = ByteArray(131072) { it.toByte() }
        val result = collect(executor.startUnprivileged(listOf("/system/bin/cat")), input)
        assertEquals(0, result.code)
        assertArrayEquals(input, result.out)
        assertEquals(0, result.err.size)
    }

    @Test fun workingDirectoryAndEnvironment() {
        val result = collect(executor.startUnprivileged(listOf("/system/bin/sh", "-c",
            "printf '%s:' \"\$PWD\"; printf '%s' \"\$MAPLESU_EXEC_TEST\""),
            directory = context.filesDir, environment = mapOf("MAPLESU_EXEC_TEST" to "value with spaces")))
        assertEquals(0, result.code)
        val fields = result.out.decodeToString().split(':', limit = 2)
        assertEquals(2, fields.size)
        assertTrue(java.nio.file.Files.isSameFile(context.filesDir.toPath(), File(fields[0]).toPath()))
        assertEquals("value with spaces", fields[1])
    }

    @Test fun deniedUltraIdentityDoesNotExecuteCommand() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyMapleSuDenied") == "true")
        val result = collect(executor.start(listOf("/system/bin/printf", "COMMAND_MUST_NOT_RUN")))
        assertEquals(69, result.code)
        assertEquals(0, result.out.size)
    }

    @Test fun deniedUltraIdentityDoesNotOpenRootShell() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyMapleSuDenied") == "true")
        val opened = runCatching { executor.openShell() }
        opened.getOrNull()?.close()
        assertTrue("Denied identity opened a root shell", opened.isFailure)
    }

    @Test fun ultraBinaryInputHasNoAdmissionPrefix() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyMapleSuBoot") == "true")
        for (input in listOf(byteArrayOf(), ByteArray(131072) { it.toByte() })) {
            val result = collect(executor.start(listOf("/system/bin/cat")), input)
            assertEquals(0, result.code)
            assertArrayEquals(input, result.out)
            assertEquals(0, result.err.size)
        }
    }

    @Test fun malformedRequestsFailBeforeStartingProcess() {
        for (command in listOf(emptyList(), listOf("id"), listOf("/system/bin/id", "bad\u0000argument"))) {
            try {
                executor.start(command)
                fail("Invalid command accepted")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            executor.startUnprivileged(listOf("id"))
            fail("Relative command accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(File(context.applicationInfo.nativeLibraryDir, "libmaplesu_runtime.so").canExecute())
    }
}
