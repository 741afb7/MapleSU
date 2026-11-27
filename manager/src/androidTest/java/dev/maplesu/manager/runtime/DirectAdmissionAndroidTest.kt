package dev.maplesu.manager.runtime

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Direct Ultra execution uses the current installation identity and preserves business stdin.
 * The process observation covers root-owned ps NAME entries containing maplesu/libmaplesu;
 * it does not establish that every product process or kernel component is absent.
 * Manager instrumentation remains running, so this is not a no-Manager cold-boot test.
 */
class DirectAdmissionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun execute(mode: String, vararg command: String, input: String = ""): Pair<Int, String> {
        val runtime = File(context.applicationInfo.nativeLibraryDir, "libmaplesu_runtime.so")
        val process = ProcessBuilder(listOf(runtime.absolutePath, mode, "--") + command)
            .redirectErrorStream(true).start()
        try {
            process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
            val text = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to text
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }

    private fun residentRootProcesses(): List<String> {
        val text = shell("ps -A -o USER,PID,NAME")
        return text.lineSequence()
            .filter { it.contains("maplesu") || it.contains("libmaplesu") }
            .filter { it.trim().startsWith("root") }
            .toList()
    }

    @Test fun currentInstallationIdentityGetsRootWithoutObservedResidentService() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyMapleSuBoot") == "true")
        val rootBefore = residentRootProcesses()
        val pids = rootBefore.mapNotNull { line ->
            line.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
        }
        if (pids.isNotEmpty()) {
            val (code, output) = execute("--ultra", "/system/bin/kill", "-9", *pids.map { it.toString() }.toTypedArray())
            assertEquals("observed resident cleanup failed: $output", 0, code)
            Thread.sleep(1_500)
        }
        val rootAfter = residentRootProcesses()
        assertTrue("matching resident root processes still present: $rootAfter", rootAfter.isEmpty())
        android.util.Log.i("MapleSU",
            "MAPLESU_EVIDENCE resident_chain before=$rootBefore after=$rootAfter")

        val (code, first) = execute("--ultra", "/system/bin/id", "-u")
        android.util.Log.i("MapleSU",
            "MAPLESU_EVIDENCE direct_admission no_observed_resident code=$code out=[${first.trim()}]")
        assertEquals("current installation identity admission failed: $first", 0, code)
        assertEquals("the command must really be root", "0", first.trim())

        val (argumentCode, arguments) = execute("--ultra", "/system/bin/sh", "-c", "id -u; printf 'argument-marker\\n'")
        assertEquals(0, argumentCode)
        assertEquals(listOf("0", "argument-marker"), arguments.lineSequence().filter(String::isNotBlank).toList())

        val (interactiveCode, interactive) = execute("--ultra", "/system/bin/sh",
            input = "id -u\nprintf 'stdin-marker\\n'\nexit\n")
        assertEquals(0, interactiveCode)
        assertEquals(listOf("0", "stdin-marker"), interactive.lineSequence().filter(String::isNotBlank).toList())
        assertEquals("the real command exit code must be retained", 23,
            execute("--ultra", "/system/bin/sh", "-c", "exit 23").first)
        assertTrue("direct execution left a matching root resident process", residentRootProcesses().isEmpty())
    }

    @Test fun retiredDirectStdinModeRejectsBeforeExecutingTheCommand() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyMapleSuBoot") == "true")
        val (code, output) = execute("--direct-stdin", "/system/bin/sh", "-c", "printf must-not-execute")
        assertEquals(64, code)
        assertEquals("maplesu-runtime: unknown execution mode", output.trim())
    }
}
