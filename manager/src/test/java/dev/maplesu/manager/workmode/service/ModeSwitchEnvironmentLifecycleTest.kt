package dev.maplesu.manager.workmode.service

import dev.maplesu.manager.installer.RootScriptResult
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.storage.ModeSwitchDataPaths
import dev.maplesu.manager.workmode.storage.MoveSet
import dev.maplesu.manager.workmode.switch.HookResult
import dev.maplesu.manager.workmode.switch.ModeSwitchRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModeSwitchEnvironmentLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val request = ModeSwitchRequest("0f8fad5b-d9cb-469f-a165-70867728950e", WorkMode.LITE, WorkMode.KSU, "baseline-fixture")

    @Test
    fun sharedRecoveryIsNotArchivedOrReplacedDuringAnUltraRollback() {
        val fixture = Fixture()
        fixture.populate()
        val recovery = fixture.file("/data/adb/maplesu/recovery/rootkey")
        recovery.parentFile!!.mkdirs()
        recovery.writeText("initial-ciphertext")
        val lifecycle = fixture.lifecycle { HookResult.OK }
        val archived = lifecycle.withdraw(request)
        assertTrue(archived.toString(), archived.ok)
        val copied = fixture.file("/archive/${request.switchId}").walkTopDown()
            .filter { it.isFile && it.name == "rootkey" }.toList()
        assertTrue(copied.toString(), copied.isEmpty())
        recovery.writeText("refreshed-ciphertext")
        assertTrue(lifecycle.restoreAfterFailure(request).ok)
        assertEquals("refreshed-ciphertext", recovery.readText())
        fixture.assertOriginal()
    }

    @Test
    fun withdrawArchivesSourceWithoutCallingTheRebootingSdk() {
        val fixture = Fixture()
        fixture.populate()
        val lifecycle = fixture.lifecycle { error("SDK must run after commit") }
        assertTrue(lifecycle.withdraw(request).ok)
        fixture.assertOriginal()
        assertEquals(0, fixture.uninstalls)
        assertTrue(fixture.file("/archive/${request.switchId}/ready").isFile)
    }

    @Test
    fun completeCallsSdkWithoutRestoringSourceOverTargetData() {
        val fixture = Fixture()
        fixture.populate()
        val lifecycle = fixture.lifecycle {
            fixture.remove(fixture.privateDirectory)
            HookResult.OK
        }
        assertTrue(lifecycle.withdraw(request).ok)
        fixture.file("/data/adb/modules/demo/config").writeText("normal-target-data")
        assertTrue(lifecycle.complete(request).ok)
        assertEquals("normal-target-data", fixture.file("/data/adb/modules/demo/config").readText())
        assertFalse(fixture.file(fixture.privateDirectory).exists())
        assertEquals(1, fixture.scripts)
        assertEquals(1, fixture.uninstalls)
    }

    @Test
    fun sdkFailureAndUncertainReceiptLeaveTheArchiveForExplicitRecovery() {
        for (code in listOf("SDK_FAILED", ModeSwitchRootProtocol.UNCERTAIN)) {
            val fixture = Fixture()
            fixture.populate()
            val lifecycle = fixture.lifecycle {
                fixture.remove(fixture.privateDirectory)
                HookResult.failed(code)
            }
            assertTrue(lifecycle.withdraw(request).ok)
            assertEquals(code, lifecycle.complete(request).code)
            assertEquals(1, fixture.scripts)
            assertTrue(fixture.file("/archive/${request.switchId}/ready").isFile)
            assertFalse(fixture.file(fixture.privateDirectory).exists())
            assertTrue(lifecycle.restoreAfterFailure(request).ok)
            fixture.assertOriginal()
        }
    }

    @Test
    fun thrownSdkFailureIsReportedWithoutAnAutomaticRestore() {
        val fixture = Fixture()
        val lifecycle = fixture.lifecycle { throw IllegalStateException("sdk-fixture-error") }
        assertEquals(RootModeSwitchEnvironmentLifecycle.UNINSTALL_FAILED, lifecycle.complete(request).code)
        assertEquals(0, fixture.scripts)
    }

    @Test
    fun anArchiveVerificationFailureNeverInvokesTheSdk() {
        val fixture = Fixture()
        fixture.populate()
        fixture.shellPrefix = """
            cp() {
              command cp "${'$'}@" || return
              case "${'$'}3" in */0) printf corrupt >> "${'$'}3/config" ;; esac
            }
        """.trimIndent() + "\n"
        val result = fixture.lifecycle { HookResult.OK }.withdraw(request)
        assertFalse(result.ok)
        assertEquals(RootModeSwitchEnvironmentLifecycle.ARCHIVE_FAILED, result.code)
        assertEquals(0, fixture.uninstalls)
        assertFalse(fixture.file("/archive/${request.switchId}/ready").exists())
        fixture.assertOriginal()
    }

    @Test
    fun normalModeDoesNotCallTheUltraSdkOrArchiveRunner() {
        val fixture = Fixture()
        val lifecycle = fixture.lifecycle { error("unexpected SDK call") }
        val normal = request.copy(fromMode = WorkMode.KSU, toMode = WorkMode.LITE)
        assertTrue(lifecycle.withdraw(normal).ok)
        assertTrue(lifecycle.restoreAfterFailure(normal).ok)
        assertTrue(lifecycle.complete(normal).ok)
        assertEquals(0, fixture.uninstalls)
        assertEquals(0, fixture.scripts)
    }

    @Test
    fun recoveryRestoresAnExistingVerifiedArchiveWithoutCallingTheSdkAgain() {
        val fixture = Fixture()
        fixture.populate()
        val lifecycle = fixture.lifecycle { HookResult.OK }
        val first = fixture.execute(lifecycle.archiveScript(request,
            MoveSet.moveSetFor(WorkMode.LITE) + fixture.paths.entries(WorkMode.LITE)))
        assertTrue(first.toString(), first.ok)
        // Shared modules remain in place across both modes; only the private tree is restored.
        fixture.remove(fixture.privateDirectory)
        assertEquals("original-module", fixture.file("/data/adb/modules/demo/config").readText())
        val restored = lifecycle.restoreAfterFailure(request)
        assertTrue(restored.toString(), restored.ok)
        fixture.assertOriginal()
        assertEquals(0, fixture.uninstalls)
    }

    @Test
    fun damagedPersistentArchiveIsRejectedBeforeAnyLiveDataIsReplaced() {
        val fixture = Fixture()
        fixture.populate()
        val lifecycle = fixture.lifecycle { HookResult.OK }
        val archived = fixture.execute(lifecycle.archiveScript(request,
            MoveSet.moveSetFor(WorkMode.LITE) + fixture.paths.entries(WorkMode.LITE)))
        assertTrue(archived.toString(), archived.ok)
        fixture.file("/archive/${request.switchId}/0/config").writeText("damaged-copy")
        val result = lifecycle.restoreAfterFailure(request)
        assertFalse(result.toString(), result.ok)
        assertEquals(RootModeSwitchEnvironmentLifecycle.RESTORE_FAILED, result.code)
        fixture.assertOriginal()
        assertEquals(0, fixture.uninstalls)
    }

    @Test
    fun aDeletedInstallerSubtreeRequiresRecoveryInsteadOfRecreatingTheHeldLock() {
        val fixture = Fixture()
        fixture.populate()
        val lifecycle = fixture.lifecycle {
            fixture.remove(MoveSet.INSTALLER_ROOT)
            HookResult.OK
        }
        assertTrue(lifecycle.withdraw(request).ok)
        assertTrue(lifecycle.complete(request).ok)
        val result = lifecycle.restoreAfterFailure(request)
        assertFalse(result.toString(), result.ok)
        assertEquals(RootModeSwitchEnvironmentLifecycle.RESTORE_FAILED, result.code)
        assertFalse(fixture.file(MoveSet.INSTALLER_ROOT).exists())
    }

    @Test
    fun aReplacedLockInTheSameInstallerDirectoryAlsoRequiresRecovery() {
        val fixture = Fixture()
        fixture.populate()
        val lifecycle = fixture.lifecycle {
            val lock = fixture.file("${MoveSet.INSTALLER_ROOT}/install.lock")
            val replacement = File(lock.parentFile, "replacement.lock").apply { writeText("new-inode") }
            Files.move(replacement.toPath(), lock.toPath(), StandardCopyOption.REPLACE_EXISTING)
            HookResult.OK
        }
        assertTrue(lifecycle.withdraw(request).ok)
        assertTrue(lifecycle.complete(request).ok)
        val result = lifecycle.restoreAfterFailure(request)
        assertFalse(result.toString(), result.ok)
        assertEquals(RootModeSwitchEnvironmentLifecycle.RESTORE_FAILED, result.code)
        assertEquals("new-inode", fixture.file("${MoveSet.INSTALLER_ROOT}/install.lock").readText())
    }

    @Test
    fun baselineAndItsPermanentMirrorSurviveSdkDeletion() {
        val fixture = Fixture()
        fixture.populate()
        fixture.populateBaseline()
        val baseline = RootModeSwitchEnvironmentLifecycle.BASELINE_ROOT
        val mode = fixture.stat("$baseline/stock-a/boot.img")
        val lifecycle = fixture.lifecycle {
            fixture.remove(baseline)
            HookResult.OK
        }

        assertTrue(lifecycle.withdraw(request).ok)
        assertTrue(lifecycle.complete(request).ok)
        val result = lifecycle.restoreAfterFailure(request)

        assertTrue(result.toString(), result.ok)
        fixture.assertBaseline(baseline)
        fixture.assertBaseline("${RootModeSwitchEnvironmentLifecycle.RECOVERY_ROOT}/originals")
        assertEquals(mode, fixture.stat("$baseline/stock-a/boot.img"))
        assertTrue(fixture.file("/archive/${request.switchId}/baseline.present").isFile)
    }

    @Test
    fun deletingAllOfDataAdbStillRestoresOriginalsBeforeReportingTheLostLock() {
        val fixture = Fixture()
        fixture.populate()
        fixture.populateBaseline()
        val archive = "${RootModeSwitchEnvironmentLifecycle.RECOVERY_ROOT}/environment-archives"
        val lifecycle = fixture.lifecycle(archiveRoot = archive) {
            fixture.remove("/data/adb")
            HookResult.OK
        }

        assertTrue(lifecycle.withdraw(request).ok)
        assertTrue(lifecycle.complete(request).ok)
        val result = lifecycle.restoreAfterFailure(request)

        assertEquals(RootModeSwitchEnvironmentLifecycle.RESTORE_FAILED, result.code)
        fixture.assertBaseline(RootModeSwitchEnvironmentLifecycle.BASELINE_ROOT)
        fixture.assertBaseline("${RootModeSwitchEnvironmentLifecycle.RECOVERY_ROOT}/originals")
        assertTrue(fixture.file("$archive/${request.switchId}/ready").isFile)
        assertFalse(fixture.file(MoveSet.INSTALLER_ROOT).exists())
        assertEquals(1, fixture.uninstalls)
    }

    @Test
    fun permanentOriginalsAreNeverOverwrittenWhenCanonicalContentChanges() {
        val fixture = Fixture()
        fixture.populate()
        fixture.populateBaseline()
        val lifecycle = fixture.lifecycle { HookResult.OK }
        val initial = lifecycle.withdraw(request)
        assertTrue(initial.toString(), initial.ok)
        fixture.file("${RootModeSwitchEnvironmentLifecycle.BASELINE_ROOT}/stock-a/boot.img").writeText("changed")

        val result = lifecycle.withdraw(request.copy(switchId = "88c48a6d-f9e2-4657-a566-8a3778115237"))

        assertEquals(RootModeSwitchEnvironmentLifecycle.ARCHIVE_FAILED, result.code)
        fixture.assertBaseline("${RootModeSwitchEnvironmentLifecycle.RECOVERY_ROOT}/originals")
        assertEquals(0, fixture.uninstalls)
        assertTrue(fixture.file("/archive/${request.switchId}/ready").isFile)
    }

    @Test
    fun failedSdkReceiptKeepsTheRestoredBaselineAndPermanentMirror() {
        val fixture = Fixture()
        fixture.populate()
        fixture.populateBaseline()
        val lifecycle = fixture.lifecycle {
            fixture.remove(RootModeSwitchEnvironmentLifecycle.BASELINE_ROOT)
            HookResult.failed("SDK_PARTIAL_FAILURE")
        }

        assertTrue(lifecycle.withdraw(request).ok)
        assertEquals("SDK_PARTIAL_FAILURE", lifecycle.complete(request).code)
        assertTrue(lifecycle.restoreAfterFailure(request).ok)
        fixture.assertBaseline(RootModeSwitchEnvironmentLifecycle.BASELINE_ROOT)
        fixture.assertBaseline("${RootModeSwitchEnvironmentLifecycle.RECOVERY_ROOT}/originals")
        assertTrue(fixture.file("/archive/${request.switchId}/ready").isFile)
    }

    @Test
    fun uncertainArchiveTransportStopsBeforeSdkUninstall() {
        var uninstalls = 0
        val lifecycle = RootModeSwitchEnvironmentLifecycle(
            ready = { HookResult.OK },
            runner = { RootScriptResult(true, 0, "MODE_ENV_OK", ModeSwitchRootProtocol.UNCERTAIN) },
            dynamicPaths = { emptyList() },
            uninstallUltra = { uninstalls++; HookResult.OK },
        )
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, lifecycle.withdraw(request).code)
        assertEquals(0, uninstalls)
    }

    @Test
    fun archiveRootAndSwitchIdAreValidatedBeforeAnyShellRuns() {
        val fixture = Fixture()
        val lifecycle = fixture.lifecycle { HookResult.OK }
        assertTrue(runCatching { lifecycle.archiveScript(request.copy(switchId = "../fixture"), emptyList()) }.isFailure)
        assertEquals(0, fixture.scripts)
        val invalidRoot = RootModeSwitchEnvironmentLifecycle({ HookResult.OK }, fixture::execute,
            { emptyList() }, { HookResult.OK }, "/archive/../outside")
        assertTrue(runCatching { invalidRoot.archiveScript(request, emptyList()) }.isFailure)
    }

    @Test
    fun prepareReportsThePinnedRootReadinessResult() {
        val fixture = Fixture()
        val lifecycle = RootModeSwitchEnvironmentLifecycle(
            ready = { HookResult.failed("ROOT_FIXTURE_UNAVAILABLE") }, runner = fixture::execute,
            dynamicPaths = { emptyList() }, uninstallUltra = { error("unexpected SDK call") },
        )
        assertEquals("ROOT_FIXTURE_UNAVAILABLE", lifecycle.prepare().code)
        assertEquals(0, fixture.scripts)
    }

    private inner class Fixture {
        val root: File = tmp.newFolder()
        val privateDirectory = "/data/a1b2c3d4e5f60718"
        val paths = ModeSwitchDataPaths.fromLocal(ultraDirectory = privateDirectory)
        var scripts = 0
        var uninstalls = 0
        var shellPrefix = ""

        fun lifecycle(archiveRoot: String = "/archive", uninstall: () -> HookResult) = RootModeSwitchEnvironmentLifecycle(
            ready = { HookResult.OK },
            runner = { scripts++; execute(it) },
            dynamicPaths = paths::entries,
            uninstallUltra = { uninstalls++; uninstall() },
            archiveRoot = archiveRoot,
        )

        fun file(path: String) = File(root, path.trimStart('/'))
        private fun write(path: String, body: String) {
            file(path).also { it.parentFile!!.mkdirs(); it.writeText(body) }
        }

        fun populate() {
            write("${MoveSet.INSTALLER_ROOT}/install.lock", "held-lock")
            write("${MoveSet.INSTALLER_ROOT}/pending", "pending-task")
            write("/data/adb/maplesu/config", "original-config")
            write("/data/adb/maplesu/.hidden", "hidden-config")
            file("/data/adb/maplesu/empty").mkdirs()
            write("/data/adb/maplesu-su-policy/grants.json", "original-grants")
            write("/data/adb/modules/demo/config", "original-module")
            Files.createSymbolicLink(file("/data/adb/modules/demo/current").toPath(), File("config").toPath())
            write("$privateDirectory/state", "native-state")
            val chmod = execute("chmod 700 '${file("/data/adb/maplesu").absolutePath.replace('\\', '/')}'\n" +
                "chmod 600 '${file("/data/adb/maplesu/config").absolutePath.replace('\\', '/')}'\n")
            assertTrue(chmod.toString(), chmod.ok)
        }

        fun populateBaseline() {
            val baseline = RootModeSwitchEnvironmentLifecycle.BASELINE_ROOT
            write("$baseline/stock-a/boot.img", "original-stock-boot")
            write("$baseline/stock-a/init_boot.img", "original-stock-init-boot")
            write("$baseline/stock-a/manifest.json", "original-stock-manifest")
            write("$baseline/stock-a/manifest.json.sha256", "original-manifest-digest")
            file("$baseline/stock-a/empty").mkdirs()
        }

        fun assertBaseline(path: String) {
            assertEquals("original-stock-boot", file("$path/stock-a/boot.img").readText())
            assertEquals("original-stock-init-boot", file("$path/stock-a/init_boot.img").readText())
            assertEquals("original-stock-manifest", file("$path/stock-a/manifest.json").readText())
            assertEquals("original-manifest-digest", file("$path/stock-a/manifest.json.sha256").readText())
            assertTrue(file("$path/stock-a/empty").isDirectory)
            assertTrue(file("$path/stock-a/empty").list()!!.isEmpty())
        }

        fun assertOriginal() {
            assertEquals("held-lock", file("${MoveSet.INSTALLER_ROOT}/install.lock").readText())
            assertEquals("pending-task", file("${MoveSet.INSTALLER_ROOT}/pending").readText())
            assertEquals("original-config", file("/data/adb/maplesu/config").readText())
            assertEquals("hidden-config", file("/data/adb/maplesu/.hidden").readText())
            assertTrue(file("/data/adb/maplesu/empty").isDirectory)
            assertTrue(file("/data/adb/maplesu/empty").list()!!.isEmpty())
            assertEquals("original-grants", file("/data/adb/maplesu-su-policy/grants.json").readText())
            assertEquals("original-module", file("/data/adb/modules/demo/config").readText())
            assertEquals("config", Files.readSymbolicLink(file("/data/adb/modules/demo/current").toPath()).toString())
            assertEquals("native-state", file("$privateDirectory/state").readText())
        }

        fun remove(vararg paths: String) {
            for (path in paths) assertTrue(file(path).deleteRecursively())
        }

        fun stat(path: String, includeTimestamp: Boolean = true): String {
            val format = if (includeTimestamp) "%a:%u:%g:%Y" else "%a:%u:%g"
            val result = execute("stat -c '$format' '${file(path).absolutePath.replace('\\', '/')}'\n")
            assertTrue(result.toString(), result.ok)
            return result.output.trim()
        }

        fun execute(script: String): RootScriptResult {
            val hostRoot = root.absolutePath.replace('\\', '/')
            val mapped = script.replace("'/data/", "'$hostRoot/data/")
                .replace("'/metadata/", "'$hostRoot/metadata/")
                .replace("'/archive/", "'$hostRoot/archive/")
                .replace("'/archive'", "'$hostRoot/archive'")
            val windows = System.getProperty("os.name", "").orEmpty().startsWith("Windows")
            val shell = if (windows) listOf("C:/Program Files/Git/bin/bash.exe", "--noprofile", "--norc", "-s")
                else listOf("/bin/sh", "-s")
            val builder = ProcessBuilder(shell).redirectErrorStream(true)
            if (windows) {
                // Git Bash otherwise turns a copied native symlink into a regular file on NTFS.
                builder.environment()["MSYS"] =
                    (builder.environment()["MSYS"].orEmpty() + " winsymlinks:nativestrict").trim()
            }
            val trace = System.getenv("MAPLESU_LIFECYCLE_TRACE") == "1"
            val inputFile = File.createTempFile("lifecycle-script-", ".sh", tmp.root)
            val outputFile = File.createTempFile("lifecycle-output-", ".log", tmp.root)
            var child: Process? = null
            try {
                inputFile.writeText((if (trace) "set -x\n" else "") + shellPrefix + mapped + "\n")
                // Feeding stdin before draining stdout can fill both Windows pipes and deadlock.
                val process = builder.redirectInput(inputFile).redirectOutput(outputFile).start()
                child = process
                // Windows file/metadata subprocesses take about a minute under compiler load.
                assertTrue("lifecycle fixture shell timed out", process.waitFor(180, TimeUnit.SECONDS))
                val output = outputFile.readText()
                val code = process.exitValue()
                if (code != 0 && trace) System.err.println(output)
                return RootScriptResult(code == 0, code, output)
            } finally {
                child?.takeIf { it.isAlive }?.let { process ->
                    destroyDescendants(process)
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                inputFile.delete()
                outputFile.delete()
            }
        }

        /**
         * Java 9+ `Process.descendants()` 在 Android 的 `java.lang.Process` stub 里不存在
         * （单元测试编译期按 android.jar 解析），所以用反射调用：宿主 JVM 上照常杀子进程树，
         * 解析不到时退化为只杀直接子进程（`finally` 里仍会 `destroyForcibly()`）。
         */
        private fun destroyDescendants(process: Process) {
            val stream = runCatching {
                process.javaClass.getMethod("descendants").invoke(process) as? java.util.stream.Stream<*>
            }.getOrNull() ?: return
            runCatching {
                stream.use { handles ->
                    handles.map { it as Process }.toList().asReversed().forEach { it.destroyForcibly() }
                }
            }
        }
    }
}
