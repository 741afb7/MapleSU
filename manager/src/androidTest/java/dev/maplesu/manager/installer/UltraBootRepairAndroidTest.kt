package dev.maplesu.manager.installer

import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maplesu.core.ui.MainActivity
import dev.maplesu.manager.ultrakernel.UltraKernelSpec
import dev.maplesu.manager.runtime.ultra.UltraIdentityRecovery
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.workmode.detect.ProcBootIdSource
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONObject

/** Explicit selected-device steps: prepare, commit, then verify after a separate normal reboot. */
@RunWith(AndroidJUnit4::class)
class UltraBootRepairAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test fun prepare() {
        selected("prepare")
        val task = taskId()
        assertEquals(sourceBoot(), ProcBootIdSource.read())
        val before = InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.ULTRA) {
            InstallerServiceClient.decode(InstallerServiceClient.observe(it))
        }
        assertEquals("_a", before.slotSuffix)
        assertEquals(required("sourceSha256"), before.partitions.getValue("boot").sha256)
        val directory = File(context.filesDir, "panic-boot-repair")
        val original = File(directory, "original-boot.img")
        val candidate = File(directory, "candidate-boot.img")
        assertEquals(required("sourceSha256"), UltraKernelSpec.sha256(original))
        assertEquals(required("candidateSha256"), UltraKernelSpec.sha256(candidate))
        InstallerServiceClient.beginInstallation(context, task, InstallTargetMode.ULTRA)
        val result = InstallerServiceClient.prepareBootInstall(context, candidate, before, original, task)
        assertEquals("PREPARED", result.getString("state"))
        assertEquals(task, result.getString("taskId"))
        assertEquals(required("candidateSha256"), result.getString("candidateSha256"))
        val persisted = InstallerServiceClient.installationStatus(context, task, InstallTargetMode.ULTRA)
        assertEquals("PREPARED", persisted.getString("state"))
        assertEquals(required("candidateSha256"), persisted.getString("candidateSha256"))
        assertEquals(sourceBoot(), ProcBootIdSource.read())
        report(result)
    }

    @Test fun commit() {
        selected("commit")
        assertEquals(sourceBoot(), ProcBootIdSource.read())
        assertEquals(taskId(), InstallerServiceClient.lastTaskId(context))
        val prepared = InstallerServiceClient.installationStatus(context, taskId(), InstallTargetMode.ULTRA)
        assertEquals("PREPARED", prepared.getString("state"))
        assertEquals(required("candidateSha256"), prepared.getString("candidateSha256"))
        val result = InstallerServiceClient.continueInstallation(context, taskId())
        assertEquals("REBOOT_REQUIRED", result.getString("state"))
        assertEquals(required("candidateSha256"), result.getString("candidateSha256"))
        assertTrue(result.getBoolean("rebootRequired"))
        assertEquals(sourceBoot(), ProcBootIdSource.read())
        report(result)
    }

    @Test fun verify() {
        selected("verify")
        val through = args.getString("repairVerifyThrough") ?: "transaction"
        require(through in setOf("credential", "identity", "transaction"))
        assertNotEquals(sourceBoot(), ProcBootIdSource.read())
        assertEquals(taskId(), InstallerServiceClient.lastTaskId(context))
        stage("CREDENTIAL_READ_STARTED")
        val client = UltraRuntimeClient()
        val owner = requireNotNull(client.queryManagerOwnerIdentity().identity)
        assertEquals(android.os.Process.myUid(), owner.fullUid)
        stage("CREDENTIAL_READ_COMPLETED")
        stage("SDK_UID_STARTED")
        assertEquals(0, UltraIdentityRecovery.uid(client))
        stage("SDK_UID_COMPLETED")
        if (through == "credential") return
        stage("IDENTITY_RECOVER_STARTED")
        assertEquals("RECOVERY_OWNER_VERIFIED", UltraIdentityRecovery.recover(context))
        stage("IDENTITY_RECOVER_COMPLETED")
        // Recheck the current owner after recovery before accepting the transaction.
        stage("IDENTITY_ENSURE_STARTED")
        assertEquals(owner, client.queryManagerOwnerIdentity().identity)
        assertEquals(0, UltraIdentityRecovery.uid(client))
        stage("IDENTITY_ENSURE_COMPLETED")
        if (through == "identity") return
        stage("TRANSACTION_VERIFY_STARTED")
        val result = InstallerServiceClient.verifyInstallation(context, taskId())
        assertEquals("BOOT_VERIFIED", result.getString("state"))
        assertTrue(result.getBoolean("runtimeVerified"))
        assertEquals(required("candidateSha256"), result.getString("candidateSha256"))
        stage("TRANSACTION_VERIFY_COMPLETED")
        report(result)
    }

    @Test fun restore() {
        selected("restore")
        assertEquals(taskId(), InstallerServiceClient.lastTaskId(context))
        val result = InstallerServiceClient.restoreInstallation(context, taskId())
        assertEquals("RESTORED", result.getString("state"))
        report(result)
    }

    private fun selected(action: String) {
        assumeTrue(args.getString("panicBootRepair") == action)
        assertEquals("PLQ110", android.os.Build.MODEL)
        stage("SELECTED_${action.uppercase()}_BEFORE_ACTIVITY")
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        stage("SELECTED_${action.uppercase()}_AFTER_ACTIVITY")
    }

    private fun stage(name: String) {
        val runId = args.getString("repairRunId").orEmpty()
        val record = JSONObject().put("runId", runId).put("stage", name)
            .put("atMillis", System.currentTimeMillis()).put("bootId", ProcBootIdSource.read())
        val directory = File(context.filesDir, "panic-boot-repair").apply { check(isDirectory) }
        FileOutputStream(File(directory, "stages.jsonl"), true).use {
            it.write((record.toString() + "\n").toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        instrumentation.sendStatus(3, Bundle().apply {
            putString("repairRunId", runId)
            putString("repairStage", name)
        })
    }

    private fun required(name: String) = requireNotNull(args.getString(name)) { "REPAIR_ARGUMENT_REQUIRED:$name" }
    private fun sourceBoot() = required("sourceBootId").also { UUID.fromString(it) }
    private fun taskId() = required("repairTaskId").also { assertEquals(it, UUID.fromString(it).toString()) }
    private fun report(result: Bundle) = instrumentation.sendStatus(2, Bundle().apply {
        putString("repairTaskId", taskId())
        putString("repairState", result.getString("state"))
        putString("candidateSha256", result.getString("candidateSha256"))
        putBoolean("runtimeVerified", result.getBoolean("runtimeVerified"))
    })
}
