package dev.maplesu.manager

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maplesu.core.Natives
import com.maplesu.core.ui.MainActivity
import com.maplesu.core.ui.util.LkmSelection
import com.maplesu.core.ui.util.installBoot
import dev.maplesu.image.BootImageInspector
import dev.maplesu.manager.runtime.MapleSuCommandExecutor
import dev.maplesu.manager.runtime.MapleSuRuntime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/** Hardware workflow checks are individually selected; ordinary test runs never write a partition. */
@RunWith(AndroidJUnit4::class)
class LiteWorkflowAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun candidateReceipt(imageSha256: String): JSONObject? {
        val workspace = dev.maplesu.manager.compat.UltraStateStorage.flash(context.filesDir)
        return workspace.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.startsWith("candidate-") && it.extension == "json" }
            .mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
            .firstOrNull { it.optInt("schemaVersion") == 2 &&
                it.optString("operation") == "ULTRA_KERNEL_GENERATE" &&
                it.optString("candidateBootSha256") == imageSha256 }
    }

    private fun candidateImage(receipt: JSONObject): File = File(
        dev.maplesu.manager.compat.UltraStateStorage.flash(context.filesDir),
        "candidate-${receipt.getString("asset")}-${receipt.getString("patchedKernelSha256").take(12)}.img")

    @Test(timeout = 240_000) fun productionFilePatchExportsVerifiedImage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liteWorkflow") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val input = File(context.cacheDir, "lite-source.img")
            try {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "cat /data/local/tmp/maplesu-lite-source.img")).use { stream ->
                    input.outputStream().use { stream.copyTo(it) }
                }
                val original = BootImageInspector.inspect(input)
                val output = mutableListOf<String>()
                val result = installBoot(Uri.fromFile(input), LkmSelection.KmiNone, false, "boot",
                    false, false, false, "", output::add, output::add,
                    targetMode = dev.maplesu.manager.installer.InstallTargetMode.ULTRA)
                assertEquals(output.joinToString("\n"), 0, result.code)
                assertFalse(result.showReboot)
                val exported = File(requireNotNull(result.outputPath) { "Verified export path was not returned" })
                val image = BootImageInspector.inspect(exported)
                val report = requireNotNull(candidateReceipt(image.sha256)) { "Current Ultra candidate receipt is absent" }
                val retained = candidateImage(report)
                assertEquals(image.sha256, BootImageInspector.inspect(retained).sha256)
                assertEquals(original.sha256, report.getString("baseBootSha256"))
                assertEquals(image.sha256, report.getString("candidateBootSha256"))
                assertNotEquals(original.sha256, image.sha256)
                assertTrue(report.getBoolean("readbackVerified"))
                assertTrue(report.getInt("patches") > 0)
                assertTrue(report.getInt("differingBytes") > 0)
                val footer = ByteArray(4)
                RandomAccessFile(input, "r").use { it.seek(input.length() - 64); it.readFully(footer) }
                if (footer.contentEquals("AVBf".toByteArray())) {
                    assertEquals(input.length(), exported.length())
                    RandomAccessFile(exported, "r").use { it.seek(exported.length() - 64); it.readFully(footer) }
                    assertArrayEquals("AVBf".toByteArray(), footer)
                }
                InstrumentationRegistry.getArguments().getString("expectedCandidateSha256")?.let {
                    assertEquals("Application patch differs from the verified boot image", it, image.sha256)
                }
                assertEquals(original.sha256, BootImageInspector.inspect(input).sha256)
                report.put("partitionWrites", false).put("exportVerified", true)
                File(context.filesDir, "lite-workflow.json").writeText(report.toString())
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("candidateSha256", image.sha256)
                    putString("candidatePath", exported.absolutePath)
                    putString("kernelRelease", report.getString("kernelRelease"))
                })
            } finally { input.delete() }
        }
    }

    @Test(timeout = 30_000) fun runningBackendUsesCurrentIdentityWithoutAKey() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyMapleSuBoot") == "true")
        assertTrue(MapleSuRuntime.refresh(context))
        assertTrue(Natives.isManager)
        MapleSuRuntime.openShell(context).use { shell ->
            val result = shell.newJob().add("/system/bin/id -u", "/system/bin/getenforce")
                .to(ArrayList<String>(), null).exec()
            assertTrue(result.isSuccess)
            assertEquals(listOf("0", "Enforcing"), result.out)
        }
        val namespace = runCatching { MapleSuRuntime.openShell(context, globalMnt = true).close() }
        namespace.exceptionOrNull()?.let {
            assertEquals("MAPLESU_GLOBAL_NAMESPACE_UNAVAILABLE", it.message)
        }
        instrumentation.runOnMainSync {
            assertTrue("A deployment capability failure cleared authenticated Root state", Natives.isManager)
        }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("globalNamespace", if (namespace.isSuccess) "available" else "unavailable")
            putString("authenticatedRootRetained", "true")
        })
    }

    @Test(timeout = 30_000) fun deniedOwnerDoesNotOpenTheUltraShell() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyMapleSuDenied") == "true")
        val opened = runCatching { MapleSuCommandExecutor(context).openShell() }
        opened.getOrNull()?.close()
        assertTrue("Denied owner opened a root shell", opened.isFailure)
    }

    @Test(timeout = 300_000) fun explicitlyRequestedDirectInstallation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("commitBoot") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val output = mutableListOf<String>()
            val taskId = java.util.UUID.randomUUID().toString()
            dev.maplesu.manager.installer.InstallerServiceClient.beginInstallation(context, taskId)
            val result = installBoot(null, LkmSelection.KmiNone, false, "boot",
                false, false, false, "", output::add, output::add, taskId,
                targetMode = dev.maplesu.manager.installer.InstallTargetMode.ULTRA,
                existingRootConflictAccepted = true)
            File(context.filesDir, "lite-install-result.json").writeText(JSONObject()
                .put("taskId", taskId).put("code", result.code).put("showReboot", result.showReboot)
                .put("log", output.joinToString("\n")).toString())
            assertEquals(output.joinToString("\n"), 0, result.code)
            assertTrue(result.showReboot)
            val status = dev.maplesu.manager.installer.InstallerServiceClient.installationStatus(context, taskId)
            assertEquals("REBOOT_REQUIRED", status.getString("state"))
            assertTrue(status.getInt("attemptedPartitionCount") > 0)
            assertEquals(taskId, dev.maplesu.manager.installer.InstallerServiceClient.lastTaskId(context))
            val fingerprint = dev.maplesu.manager.runtime.ultra.UltraInstallTransactions.fingerprint(context, taskId)
            assertNotNull("Installed candidate observation was not retained for this task", fingerprint)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("directInstallTaskId", taskId)
                putString("directInstallState", status.getString("state"))
                putInt("directInstallAttemptedPartitionCount", status.getInt("attemptedPartitionCount"))
                putString("directInstallCandidateFingerprint", fingerprint)
            })
        }
    }

    @Test(timeout = 90_000) fun verifiedRunningImageDoesNotRepatchOrRequestReboot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyAlreadyInstalled") == "true")
        val client = dev.maplesu.manager.installer.InstallerServiceClient
        val before = client.observeBootChain(context)
        val digest = before.partitions.getValue("boot").sha256
        val receipt = requireNotNull(candidateReceipt(digest)) { "Current boot candidate receipt is absent" }
        assertEquals(digest, BootImageInspector.inspect(candidateImage(receipt)).sha256)
        assertTrue(MapleSuRuntime.refresh(context))
        val previous = client.lastTaskId(context)
        val taskId = java.util.UUID.randomUUID().toString()
        val output = mutableListOf<String>()
        try {
            client.beginInstallation(context, taskId)
            val result = installBoot(null, LkmSelection.KmiNone, false, "boot",
                false, false, false, "", output::add, output::add, taskId,
                targetMode = dev.maplesu.manager.installer.InstallTargetMode.ULTRA)
            assertEquals(output.joinToString("\n"), 0, result.code)
            assertFalse(result.showReboot)
            assertEquals("NONE", client.installationStatus(context, taskId).getString("state"))
            val after = client.observeBootChain(context)
            before.partitions.forEach { (name, partition) -> assertEquals(partition.sha256, after.partitions.getValue(name).sha256) }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("alreadyInstalledBootSha256", digest)
                putBoolean("rebootRequired", false)
                putBoolean("partitionsUnchanged", true)
            })
        } finally {
            if (previous != null) client.rememberTask(context, previous)
            else context.getSharedPreferences("installer", android.content.Context.MODE_PRIVATE).edit().remove("lastTaskId").commit()
        }
    }
}
