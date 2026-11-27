package dev.maplesu.manager.installer

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.maplesu.core.ui.MainActivity
import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.runtime.RootExec
import dev.maplesu.manager.runtime.RootExecResult
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in startup observation only: no pairing/setup calls, task replacement, flash or reboot. */
class InstallMigrationReadinessAndroidTest {
    @Test(timeout = 120_000)
    fun normalStartupRestoresVerifiedUltraRootOnTheCurrentBoot() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifyInstallMigration") == "true")
        val taskId = requireNotNull(arguments.getString("migrationTaskId")) { "migrationTaskId is required" }
        require(UUID.fromString(taskId).toString() == taskId) { "migrationTaskId must be a canonical UUID" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previousTask = InstallerServiceClient.lastTaskId(context)
        val bootId = File("/proc/sys/kernel/random/boot_id").readText().trim()
        assertEquals("System boot identity must be a UUID", bootId, UUID.fromString(bootId).toString())

        fun report(state: String, backend: RootBackend = RootBackend.NONE) {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("taskId", taskId)
                putString("bootId", bootId)
                putString("backend", backend.name)
                putString("state", state)
            })
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            report("WAITING_FOR_STARTUP")
            val deadline = SystemClock.elapsedRealtime() + 60_000
            var root: RootExecResult? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                if (ModeRuntime.active == WorkMode.LITE &&
                    UltraEnvironmentProbe.currentSnapshot(context).managerOwnerIdentity?.fullUid == android.os.Process.myUid() &&
                    UltraEnvironmentProbe.usesUltraBackend(context)) {
                    root = RootExec.execute(context,
                        "/system/bin/id -u\n/system/bin/cat /proc/sys/kernel/random/boot_id")
                    if (root.ok && root.backend == RootBackend.ULTRA_ENV &&
                        root.output.trim().lines().map(String::trim) == listOf("0", bootId)) break
                }
                SystemClock.sleep(250)
            }
            report(if (root?.ok == true) "ROOT_OBSERVED" else "ROOT_NOT_READY", root?.backend ?: RootBackend.NONE)
            assertEquals("Normal startup did not activate Ultra within its budget", WorkMode.LITE, ModeRuntime.active)
            val owner = requireNotNull(UltraEnvironmentProbe.currentSnapshot(context).managerOwnerIdentity)
            assertEquals(android.os.Process.myUid(), owner.fullUid)
            assertEquals("Normal startup owner identity is stale", owner,
                UltraRuntimeClient().queryManagerOwnerIdentity().identity)
            assertTrue("Startup identity does not match a verified Ultra identity", UltraEnvironmentProbe.usesUltraBackend(context))
            assertNotNull("Startup never reached a verified Root execution", root)
            val verifiedRoot = requireNotNull(root)
            assertTrue("Root command failed: backend=${verifiedRoot.backend} code=${verifiedRoot.code}", verifiedRoot.ok)
            assertEquals(RootBackend.ULTRA_ENV, verifiedRoot.backend)
            assertEquals(listOf("0", bootId), verifiedRoot.output.trim().lines().map(String::trim))
            assertEquals("System boot changed during observation", bootId,
                File("/proc/sys/kernel/random/boot_id").readText().trim())

            if (arguments.getString("checkMigrationTask") == "true") {
                assertEquals(InstallTargetMode.ULTRA, InstallerServiceClient.installTargetOf(context, taskId))
                val status = InstallerServiceClient.installationStatus(context, taskId, InstallTargetMode.ULTRA)
                report(status.getString("state").orEmpty(), verifiedRoot.backend)
                assertEquals(taskId, status.getString("taskId"))
                assertEquals(bootId, status.getString("currentBootId"))
                assertEquals(arguments.getString("expectMigrationRebootObserved") != "false",
                    status.getBoolean("rebootObserved"))
                assertTrue("Installation task is not a written migration", status.getString("state") in setOf(
                    "REBOOT_REQUIRED", "REBOOT_REQUESTED", "BOOT_IMAGE_VERIFIED", "BOOT_VERIFIED", "VERIFIED"))
                arguments.getString("expectedMigrationTaskState")?.let { expected ->
                    assertEquals(expected, status.getString("state"))
                }
            }
            assertEquals("Read-only verification changed lastTaskId", previousTask, InstallerServiceClient.lastTaskId(context))
            report("ROOT_VERIFIED", verifiedRoot.backend)
        }
    }
}
