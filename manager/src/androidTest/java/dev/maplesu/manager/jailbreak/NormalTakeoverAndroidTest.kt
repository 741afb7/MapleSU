package dev.maplesu.manager.jailbreak

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.topjohnwu.superuser.Shell
import dev.maplesu.manager.installer.InstallerRootSourceRegistry
import dev.maplesu.manager.ksu.KsuBackends
import dev.maplesu.manager.runtime.MapleSuCommandExecutor
import dev.maplesu.manager.runtime.RootManagement
import dev.maplesu.manager.workmode.ModeRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NormalTakeoverAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    /** One invocation of the product entry. `code == null` means the entry threw. */
    private data class Trial(
        val index: Int,
        val command: String,
        val code: Int?,
        val out: String,
        val err: String,
        val exception: String?,
        val elapsedMillis: Long,
    ) {
        val uidZero: Boolean get() = code == 0 && out.trim() == "0"

        fun describe(): String =
            "code=" + (code ?: "exception") + " out=" + out.trim().replace('\n', '|') +
                " err=" + err.trim().replace('\n', '|') +
                (exception?.let { " exception=" + it } ?: "")
    }

    private fun shell(command: String): String = try {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
    } catch (error: Throwable) {
        "SHELL_COMMAND_FAILED:" + describe(error)
    }

    private fun describe(error: Throwable): String {
        val text = StringBuilder(error.javaClass.name)
        error.message?.let { text.append(": ").append(it) }
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < 3) {
            text.append(" <- ").append(cause.javaClass.name)
            cause.message?.let { text.append(": ").append(it) }
            cause = cause.cause
            depth++
        }
        return text.toString()
    }

    private fun put(document: JSONObject, key: String, value: Any?) {
        document.put(key, value ?: JSONObject.NULL)
    }

    private fun bootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrNull()

    private fun runEntry(index: Int, command: String): Trial {
        val started = SystemClock.elapsedRealtime()
        var shell: Shell? = null
        try {
            shell = RootManagement.openShell(context)
            val out = ArrayList<String>()
            val err = ArrayList<String>()
            val result = shell.newJob().add(command).to(out, err).exec()
            return Trial(index, command, result.code, out.joinToString("\n"),
                err.joinToString("\n"), null, SystemClock.elapsedRealtime() - started)
        } catch (error: Throwable) {
            return Trial(index, command, null, "", "", describe(error),
                SystemClock.elapsedRealtime() - started)
        } finally {
            runCatching { shell?.close() }
        }
    }

    /** What exists on this boot, as far as an ordinary app can see it. Never asserted. */
    private fun preconditionFacts(): JSONObject {
        val document = JSONObject()
        put(document, "boot", bootId())
        put(document, "enforcing", shell("getenforce").trim())
        put(document, "kernelRelease", shell("uname -r").trim())

        val ksud = JSONObject()
        for (path in listOf("/data/adb/ksud", "/data/adb/ksu/bin/ksud", "/data/adb/ksu/bin/su", "/data/adb/ksu")) {
            val file = File(path)
            ksud.put(path, JSONObject()
                .put("exists", file.exists())
                .put("isFile", file.isFile)
                .put("isDirectory", file.isDirectory)
                .put("canRead", file.canRead())
                .put("canExecute", file.canExecute()))
        }
        /*
         * An app cannot stat /data/adb (0700 root), so "missing" and "not allowed to look"
         * are different facts. Keep the shell's own answer as well, and never turn either
         * into an assertion: the arm this test needs is the entry's own execution.
         */
        put(ksud, "shellLs", shell(
            "ls -ld /data/adb/ksud /data/adb/ksu /data/adb/ksu/bin/su 2>&1; echo rc=$?").trim())
        put(document, "ksud", ksud)

        val ksu = JSONObject()
        put(ksu, "sysModuleKernelsu", File("/sys/module/kernelsu").exists())
        put(ksu, "procModulesGrep", shell(
            "cat /proc/modules 2>&1 | grep -i kernelsu; echo rc=$?").trim())
        put(ksu, "driverAvailable", runCatching { KsuBackends.natives().available }.getOrNull())
        put(ksu, "driverVersion", runCatching { KsuBackends.natives().version }.getOrNull())
        put(ksu, "driverLateLoad", runCatching { KsuBackends.natives().isLateLoadMode }.getOrNull())
        put(ksu, "nativeIsManager", runCatching { KsuBackends.natives().isManager }.getOrNull())
        put(ksu, "nativeLoadError", runCatching { KsuBackends.nativeLoadError() }.getOrNull())
        put(document, "ksu", ksu)
        return document
    }

    /**
     * The wiring facts this run is judged against: what the entry is made of, whether the
     * Mode-B (KernelSU native) channel is registered at all, and what the failed attempt
     * before the soft reboot left behind.
     */
    private fun wiringFacts(): JSONObject {
        val document = JSONObject()
        put(document, "entry", ENTRY)
        put(document, "entryAliases", JSONArray(listOf(ENTRY_ALIAS_SHELL, ENTRY_ALIAS_GET)))
        put(document, "transport", TRANSPORT)

        val runtime = File(context.applicationInfo.nativeLibraryDir, RUNTIME_NAME)
        document.put("runtimeImage", JSONObject()
            .put("path", runtime.absolutePath)
            .put("executable", runtime.isFile && runtime.canExecute()))
        val ksudLibrary = File(context.applicationInfo.nativeLibraryDir, KSUD_LIBRARY)
        document.put("ksudLibrary", JSONObject()
            .put("path", ksudLibrary.absolutePath)
            .put("present", ksudLibrary.isFile))

        val channel = runCatching { InstallerRootSourceRegistry.require() }
        document.put("ksuNativeChannelRegistered", channel.isSuccess)
        put(document, "ksuNativeChannelError", channel.exceptionOrNull()?.let { describe(it) })
        put(document, "modeRuntimeActive", ModeRuntime.active?.name)

        put(document, "previousAttempt", runCatching {
            JailbreakStatus.read(context)?.let { status ->
                JSONObject()
                    .put("stage", status.stage)
                    .put("failure", status.failure ?: JSONObject.NULL)
                    .put("rootBackend", status.rootBackend ?: JSONObject.NULL)
                    .put("restoreEnforcing", status.restoreEnforcing ?: JSONObject.NULL)
                    .put("bootId", status.bootId ?: JSONObject.NULL)
            }
        }.getOrNull())
        return document
    }

    /** A separate denied-owner fixture is required; its absence is recorded as NOT_RUN. */
    private fun negative(): JSONObject {
        val document = JSONObject()
        if (InstrumentationRegistry.getArguments().getString("verifyMapleSuDenied") != "true") {
            return document.put("attempted", false).put("status", "NOT_RUN")
                .put("reason", "ULTRA_DENIED_OWNER_FIXTURE_REQUIRED")
        }
        document.put("attempted", true)
        var process: Process? = null
        try {
            process = MapleSuCommandExecutor(context).start(listOf("/system/bin/id", "-u"))
            process.outputStream.close()
            val exited = process.waitFor(8, TimeUnit.SECONDS)
            document.put("exited", exited)
            document.put("code", if (exited) process.exitValue() else -1)
            document.put("out", if (exited) process.inputStream.readBytes().decodeToString() else "")
            document.put("err", if (exited) process.errorStream.readBytes().decodeToString() else "")
        } catch (error: Throwable) {
            document.put("code", -1)
            put(document, "exception", describe(error))
        } finally {
            runCatching { process?.destroyForcibly() }
            runCatching { process?.outputStream?.close() }
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
        }
        document.put("denied", document.optInt("code", -1) == 69 && document.optString("out").isEmpty())
        return document
    }

    private fun transportProbe(): JSONObject {
        val document = JSONObject()
        document.put("attempted", true)
        var process: Process? = null
        try {
            process = MapleSuCommandExecutor(context).start(listOf("/system/bin/id", "-u"))
            process.outputStream.close()
            val exited = process.waitFor(10, TimeUnit.SECONDS)
            document.put("exited", exited)
            document.put("code", if (exited) process.exitValue() else -1)
            document.put("out", if (exited) process.inputStream.readBytes().decodeToString() else "")
            document.put("err", if (exited) process.errorStream.readBytes().decodeToString() else "")
        } catch (error: Throwable) {
            document.put("code", -1)
            put(document, "exception", describe(error))
        } finally {
            runCatching { process?.destroyForcibly() }
            runCatching { process?.outputStream?.close() }
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
        }
        return document
    }

    private fun write(document: JSONObject) {
        val directory = File(context.filesDir, "jailbreak-evidence")
        if (!directory.isDirectory) directory.mkdirs()
        File(directory, EVIDENCE_NAME).writeText(document.toString(2))
        Log.i("MapleSU", EVIDENCE_TAG + document)
    }

    @Test
    fun normalModeRunsRootCommands() {
        val document = JSONObject()
        document.put("schema", 1)
        document.put("test", "normalModeRunsRootCommands")
        document.put("recordedAtMillis", System.currentTimeMillis())

        document.put("preconditions", preconditionFacts())
        val wiring = wiringFacts()
        document.put("wiring", wiring)
        put(document, "boot", bootId())
        put(document, "enforcing", shell("getenforce").trim())

        /* Three independent invocations of the product entry. */
        val trials = (1..3).map { runEntry(it, "/system/bin/id -u") }
        document.put("trialsNote", "each trial is one entry invocation; code=null means it threw")
        document.put("trials", JSONArray(trials.map { trial ->
            JSONObject()
                .put("index", trial.index)
                .put("command", trial.command)
                .put("code", trial.code ?: JSONObject.NULL)
                .put("out", trial.out)
                .put("err", trial.err)
                .put("exception", trial.exception ?: JSONObject.NULL)
                .put("elapsedMillis", trial.elapsedMillis)
        }))

        /* One `id` for the full identity line. */
        val identity = runEntry(4, "/system/bin/id")
        document.put("uidCheck", JSONObject()
            .put("code", identity.code ?: JSONObject.NULL)
            .put("out", identity.out)
            .put("err", identity.err)
            .put("exception", identity.exception ?: JSONObject.NULL)
            .put("containsUidZero", identity.out.contains("uid=0")))
        document.put("negative", negative())
        document.put("transportProbe", transportProbe())

        val causes = mutableListOf<String>()
        trials.filter { !it.uidZero }.forEach { causes.add("trial" + it.index + ":" + it.describe()) }
        if (!identity.uidZero || !identity.out.contains("uid=0")) {
            causes.add("id:" + identity.describe())
        }
        val failure = if (causes.isEmpty()) {
            null
        } else {
            val wiringNow = document.optJSONObject("wiring")
            val ksuNow = document.optJSONObject("preconditions")?.optJSONObject("ksu")
            val ksuChannel = wiringNow?.optBoolean("ksuNativeChannelRegistered") == true
            val prefix = if (ksuChannel) "NORMAL_TAKEOVER_ROOT_COMMAND_FAILED" else "NORMAL_TAKEOVER_NOT_WIRED"
            prefix + ":ENTRY_ROOT_CHANNEL" +
                ",entry=" + ENTRY +
                ",ksuNativeChannel=" + (wiringNow?.optString("ksuNativeChannelError")
                    ?.takeIf { it.isNotEmpty() } ?: "registered") +
                ",ksuDriverVersion=" + ksuNow?.opt("driverVersion") +
                ",ksuModulePresent=" + ksuNow?.optBoolean("sysModuleKernelsu") +
                ",previousRootBackend=" + (wiringNow?.optJSONObject("previousAttempt")
                    ?.opt("rootBackend") ?: JSONObject.NULL) +
                ",transportCode=" + (document.optJSONObject("transportProbe")?.opt("code")
                    ?: JSONObject.NULL) +
                ",transportErr=" + (document.optJSONObject("transportProbe")?.optString("err")
                    ?.trim()?.take(120) ?: "") +
                ",cause=" + causes.joinToString("; ")
        }
        put(document, "failure", failure)

        /* Evidence first: a red run must still leave its record on disk. */
        write(document)
        Log.i("MapleSU", EVIDENCE_TAG + document)

        if (failure != null) {
            fail(failure + " trials=" + trials.joinToString("; ") { it.describe() })
        }

        /* L-a: three consecutive UID-0 executions through the product entry. */
        trials.forEach { trial ->
            assertEquals("trial ${trial.index}: entry exit code (" + trial.describe() + ")",
                0, trial.code ?: -1)
            assertEquals("trial ${trial.index}: expected UID 0 from /system/bin/id -u (" +
                trial.describe() + ")", "0", trial.out.trim())
        }
        /* L-b: the identity line itself. */
        assertEquals("id: entry exit code (" + identity.describe() + ")", 0, identity.code ?: -1)
        assertTrue("id: output did not contain uid=0 (" + identity.describe() + ")",
            identity.out.contains("uid=0"))
        val denied = document.optJSONObject("negative")
        if (denied?.optBoolean("attempted") == true) {
            assertTrue("Denied owner did not return admission code 69: $denied", denied.optBoolean("denied"))
        }
    }

    private companion object {
        const val ENTRY = "dev.maplesu.manager.runtime.RootManagement.openShell"
        const val ENTRY_ALIAS_SHELL = "com.maplesu.core.ui.util.KsuCli.SHELL"
        const val ENTRY_ALIAS_GET = "com.maplesu.core.ui.util.getRootShell"
        const val TRANSPORT =
            "RootManagement selected KSU/SDK task channel; direct diagnostic: libmaplesu_runtime.so --ultra -> SDK get_root()"
        const val RUNTIME_NAME = "libmaplesu_runtime.so"
        const val KSUD_LIBRARY = "libksud_ksu.so"
        const val EVIDENCE_NAME = "normal-takeover.json"
        const val EVIDENCE_TAG = "MAPLESU_NORMAL_TAKEOVER_EVIDENCE "
    }
}
