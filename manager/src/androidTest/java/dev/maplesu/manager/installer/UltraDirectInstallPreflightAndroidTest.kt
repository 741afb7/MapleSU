package dev.maplesu.manager.installer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maplesu.core.ui.MainActivity
import com.maplesu.core.ui.util.FlashOutcome
import com.maplesu.core.ui.util.UltraImageBuild
import com.maplesu.core.ui.util.installUltraKernelUpdate
import com.maplesu.core.ui.util.ultraImageBuildCandidate
import dev.maplesu.manager.ImageRepository
import dev.maplesu.manager.ultrakernel.UltraKernelContextAssets
import dev.maplesu.manager.ultrakernel.UltraKernelDeploymentNamespace
import dev.maplesu.manager.ultrakernel.UltraKernelFlow
import dev.maplesu.manager.ultrakernel.UltraKernelOriginalRecovery
import dev.maplesu.manager.ultrakernel.UltraKernelPlan
import dev.maplesu.manager.ultrakernel.UltraKernelSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real current-boot builder and cancels every transaction before commit. */
@RunWith(AndroidJUnit4::class)
class UltraDirectInstallPreflightAndroidTest {
    /** Exercises the real explicit upgrade selector and stops at its validated PRE write boundary. */
    @Test fun rawKernelUpdateReachesPreparedBootWithoutWriting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("existingRootInstaller") == "true")
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val taskId = java.util.UUID.randomUUID().toString()
        val before = InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.ULTRA) {
            InstallerServiceClient.decode(InstallerServiceClient.observe(it))
        }
        assertTrue(before.partitions.containsKey("boot"))
        assertEquals("boot", InstallerServiceClient.installedCarrierPartition(context, before))
        var prepared: Bundle? = null
        val result = installUltraKernelUpdate(taskId) {
            val status = InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.ULTRA) {
                it.getBootInstall(taskId)
            }
            assertEquals(taskId, status.getString("taskId"))
            when (status.getString("state")) {
                "NONE" -> Unit // Scope checks before prepare have no record for this task.
                "PREPARED" -> {
                    assertEquals("NATIVE", status.getString("kind"))
                    assertEquals("INSTALL", status.getString("operation"))
                    assertEquals("LITE", status.getString("targetMode"))
                    assertEquals("boot", status.getString("partition"))
                    assertTrue(status.getString("carrierRestoration").isNullOrEmpty())
                    assertFalse(status.getBoolean("restoreOriginal"))
                    assertTrue(status.getStringArrayList("restorePartitions").orEmpty().isEmpty())
                    assertEquals(0, status.getInt("attemptedPartitionCount", -1))
                    @Suppress("DEPRECATION")
                    val rows = status.getParcelableArrayList<Bundle>("partitionRows").orEmpty()
                    for (name in listOf("boot", "init_boot").filter(before.partitions::containsKey)) {
                        val row = rows.single { it.getString("name") == name }
                        assertEquals(before.partitions.getValue(name).sha256, row.getString("preSha256"))
                    }
                    prepared = Bundle(status)
                    throw PreflightComplete()
                }
                else -> throw AssertionError("Unexpected upgrade preflight state: ${status.getString("state")}")
            }
        }
        val pre = requireNotNull(prepared) { "The real upgrade entry did not reach its PRE write boundary: ${result.err}" }
        assertEquals(FlashOutcome.FAILED, result.outcome)
        assertTrue(result.code != 0)
        assertTrue(result.err.contains("INSTALLER_PREFLIGHT_COMPLETE"))
        InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.ULTRA) { service ->
            val status = service.getBootInstall(taskId)
            assertEquals(taskId, status.getString("taskId"))
            assertEquals("CANCELLED", status.getString("state"))
            assertEquals("boot", status.getString("partition"))
            assertTrue(status.getString("carrierRestoration").isNullOrEmpty())
            assertEquals(0, status.getInt("attemptedPartitionCount", -1))
            val after = InstallerServiceClient.decode(InstallerServiceClient.observe(service))
            assertEquals(before.partitions, after.partitions)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("rawUpgradeTaskId", taskId)
                putString("rawUpgradePreparedState", pre.getString("state"))
                putString("rawUpgradeTargetPartition", pre.getString("partition"))
                putString("rawUpgradeCarrierRestoration", pre.getString("carrierRestoration"))
                putString("rawUpgradeState", status.getString("state"))
                putInt("rawUpgradeAttemptedPartitionCount", status.getInt("attemptedPartitionCount", -1))
                putBoolean("rawUpgradePartitionsUnchanged", true)
            })
        }
    }

    /** Exercises the source FD used by the real flash page, then cancels before commit. */
    @Test fun nativeTransactionSourceReachesTheProductionBuilderWithoutWriting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("existingRootInstaller") == "true")
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val taskId = java.util.UUID.randomUUID().toString()
        val before = InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.ULTRA) {
            InstallerServiceClient.decode(InstallerServiceClient.observe(it))
        }
        var buildResult = ""
        var prepared = false
        try {
            StockInstallFlow.install(context, taskId, InstallTargetMode.ULTRA, "boot", null,
                restoreOriginal = InstrumentationRegistry.getArguments().getString("restoreMigrationOriginals") == "true",
                selectedManager = null, conflictAccepted = true,
                onPrepared = {
                    assertEquals(taskId, it.getString("taskId"))
                    assertEquals("NATIVE", it.getString("kind"))
                    assertEquals("PREPARED", it.getString("state"))
                    prepared = true
                },
                produce = { source, recoveredDeployment ->
                    assertTrue("The builder must consume a prepared transaction source", prepared)
                    val sourceSha256 = UltraKernelSpec.sha256(source)
                    if (InstrumentationRegistry.getArguments().getString("restoreMigrationOriginals") != "true") {
                        assertEquals(before.partitions.getValue("boot").sha256, sourceSha256)
                    }
                    val repository = ImageRepository(context)
                    val imported = repository.import(Uri.fromFile(source))
                    assertEquals(sourceSha256, imported.report.sha256)
                    val candidate = when (val result = ultraImageBuildCandidate(repository, imported.report.sha256, {},
                        recoveredDeployment = recoveredDeployment)) {
                        is UltraImageBuild.AlreadyPatched -> {
                            assertEquals(sourceSha256, result.candidate.bootSha256)
                            buildResult = "REUSABLE_PATCHED_CANDIDATE"
                            result.candidate
                        }
                        is UltraImageBuild.Candidate -> {
                            buildResult = "CANDIDATE_VERIFIED"
                            result
                        }
                        is UltraImageBuild.Refused -> throw AssertionError("Production builder refused: ${result.message}")
                    }
                    assertEquals(sourceSha256, UltraKernelSpec.sha256(source))
                    assertEquals(candidate.bootSha256, UltraKernelSpec.sha256(candidate.image))
                    assertEquals("boot", candidate.partition)
                    val kernel = UltraKernelPlan.readKernel(candidate.image)
                    assertEquals(candidate.kernelSha256, UltraKernelSpec.sha256(kernel))
                    assertEquals(candidate.release, UltraKernelSpec.releaseOf(kernel))
                    val namespace = requireNotNull(deploymentNamespace(context, candidate.image)) {
                        "PREFLIGHT_KEYLESS_NAMESPACE_MISSING"
                    }
                    assertArrayEquals(namespace.record(), UltraKernelDeploymentNamespace.decode(namespace.record()).record())
                    recoveredDeployment?.let { assertArrayEquals(it.record(), namespace.record()) }
                    throw PreflightComplete()
                },
                onWriting = { throw AssertionError("Read-only preflight reached the write stage") })
            throw AssertionError("Read-only preflight returned without its deliberate stop")
        } catch (_: PreflightComplete) {
            // The production StockInstallAttempt finally block cancels this PREPARED transaction.
        }
        InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.ULTRA) { service ->
            val status = service.getBootInstall(taskId)
            assertEquals("CANCELLED", status.getString("state"))
            assertEquals(0, status.getInt("attemptedPartitionCount", -1))
            val after = InstallerServiceClient.decode(InstallerServiceClient.observe(service))
            assertEquals(before.partitions, after.partitions)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("nativeDirectTaskId", taskId)
                putString("nativeDirectResult", buildResult)
                putString("nativeDirectState", status.getString("state"))
                putInt("nativeDirectAttemptedPartitionCount", status.getInt("attemptedPartitionCount", -1))
                putBoolean("nativeDirectPartitionsUnchanged", true)
            })
        }
    }

    private fun deploymentNamespace(context: android.content.Context, image: File): UltraKernelDeploymentNamespace? =
        dev.maplesu.image.UltraInitBootProducer.readDeploymentNamespace(image)
            ?.let(UltraKernelDeploymentNamespace::decode)
            ?: UltraKernelOriginalRecovery(UltraKernelContextAssets(context))
                .generatedKernelWithDeployment(image, context.cacheDir)?.deploymentNamespace

    private class PreflightComplete : RuntimeException("INSTALLER_PREFLIGHT_COMPLETE")

    @Test fun currentBootReachesTheUltraBuilderAndLeavesPartitionsUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("existingRootInstaller") == "true")
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val work = File(context.cacheDir, "ultra-direct-preflight-${java.util.UUID.randomUUID()}")
        assertTrue(work.mkdir())
        try {
            InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.ULTRA) { service ->
                val response = Bundle()
                val source = File(work, "current-boot.img")
                val before = ParcelFileDescriptor.AutoCloseInputStream(service.openBootImage(response)).use { input ->
                    InstallerServiceClient.decode(response).also { snapshot ->
                        source.outputStream().use { output ->
                            input.copyTo(output, 64 * 1024)
                            output.fd.sync()
                        }
                        val boot = snapshot.partitions.getValue("boot")
                        assertEquals(boot.size, source.length())
                        assertEquals(boot.sha256, UltraKernelSpec.sha256(source))
                    }
                }
                val flow = UltraKernelFlow(File(work, "builder"),
                    dev.maplesu.manager.ultrakernel.UltraKernelContextAssets(context))
                val release = flow.releaseOf(source)
                assertEquals(before.kernelRelease, release)
                val selection = requireNotNull(flow.selectForRelease(release)) {
                    "This selected-device regression requires a shipped kernel-series asset"
                }
                val preparation = flow.prepare(source, selection) {}
                if (preparation.alreadyInstalled) {
                    assertNull(preparation.candidate)
                } else {
                    val candidate = requireNotNull(preparation.candidate)
                    assertEquals(release, candidate.kernelRelease)
                    assertTrue(candidate.image.length() in 1..before.partitions.getValue("boot").size)
                    assertEquals(candidate.bootSha256, UltraKernelSpec.sha256(candidate.image))
                    val reopened = flow.prepare(candidate.image, selection) {}
                    assertTrue("The produced template must be recognized on a second install", reopened.alreadyInstalled)
                    assertNull(reopened.candidate)
                }
                // The production service reads the devices again, rather than trusting our file.
                val after = InstallerServiceClient.decode(InstallerServiceClient.observe(service))
                assertEquals(before.kernelRelease, after.kernelRelease)
                assertEquals(before.firmware, after.firmware)
                assertEquals(before.slotSuffix, after.slotSuffix)
                assertEquals(before.selinux, after.selinux)
                assertEquals(before.partitions, after.partitions)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("ultraDirectRelease", release)
                    putString("ultraDirectSourceSha256", before.partitions.getValue("boot").sha256)
                    putString("ultraDirectResult", if (preparation.alreadyInstalled) "ALREADY_PATCHED" else "CANDIDATE_VERIFIED")
                    preparation.candidate?.let {
                        putString("ultraDirectCandidateSha256", it.bootSha256)
                        putBoolean("ultraDirectExpectedPinned", it.expectedPinned)
                    }
                    putBoolean("ultraDirectPartitionsUnchanged", true)
                })
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
