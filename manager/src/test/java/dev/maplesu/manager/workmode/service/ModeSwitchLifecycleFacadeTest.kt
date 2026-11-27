package dev.maplesu.manager.workmode.service

import dev.maplesu.manager.installer.InstallConfigMover
import dev.maplesu.manager.installer.BaselineProvisioner
import dev.maplesu.manager.installer.BaselineProvisionOutcome
import dev.maplesu.manager.installer.InstallCandidateImage
import dev.maplesu.manager.installer.InstallCommitOutcome
import dev.maplesu.manager.installer.InstallMoveOutcome
import dev.maplesu.manager.installer.InstallMovePhase
import dev.maplesu.manager.installer.RootScriptResult
import dev.maplesu.manager.runtime.ultra.UltraStartupWarmup
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.switch.BatteryFacts
import dev.maplesu.manager.workmode.switch.BeginInputs
import dev.maplesu.manager.workmode.switch.CandidateResult
import dev.maplesu.manager.workmode.switch.GatewayOutcome
import dev.maplesu.manager.workmode.switch.HookResult
import dev.maplesu.manager.workmode.switch.ModeCommitSink
import dev.maplesu.manager.workmode.switch.ModeSwitchCodes
import dev.maplesu.manager.workmode.switch.ModeSwitchHashes
import dev.maplesu.manager.workmode.switch.ModeSwitchRequest
import dev.maplesu.manager.workmode.switch.ModeSwitchSnapshots
import dev.maplesu.manager.workmode.switch.SwitchCandidateProducer
import dev.maplesu.manager.workmode.switch.SwitchDeviceFacts
import dev.maplesu.manager.workmode.switch.SwitchQuiesceHooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Production facade order and restart boundaries, using an external-data-moving root client. */
class ModeSwitchLifecycleFacadeTest {
    @Test fun lenovoFinalizationFollowsProductionAndPrecedesEveryWriteForEitherNormalPartition() = runBlocking {
        for ((partition, signingMode) in listOf("boot" to "CHAINED", "init_boot" to "NON_CHAINED")) {
            val h = Harness(WorkMode.LITE).apply { defaultSigning = { error("explicit signing must keep its selection") } }
            val result = h.facade().startSwitch(h.to, requestedPartition = partition, signingMode = signingMode,
                finalizeCandidate = { patched, source ->
                    assertEquals(4096L, source.length())
                    assertEquals(partition, patched.partition)
                    h.events += "sign"
                    val image = File(h.root, "signed.img").apply { writeBytes(ByteArray(4096) { 11 }) }
                    patched.copy(image = image, sha256 = ModeSwitchHashes.sha256(image), size = image.length())
                }) as SwitchOutcome.AwaitingReboot
            h.inOrder("snapshot", "produce", "sign", "submit", "move-out", "commit", "move-in", "materialize")
            assertEquals(partition, h.client.selectedPartition)
            assertEquals(signingMode, h.client.plans.last()["lenovoSigning"])
            assertEquals(result.candidate!!.sha256, h.client.candidateSha)
            assertEquals("signed-$partition.img", result.candidate.image.name)
        }
    }

    @Test fun pageDefaultsUseTheResolvedNormalCarrierBeforeBeginAndKeepCompanionsThroughCommit() = runBlocking {
        for ((hasInitBoot, partition) in listOf(false to "boot", true to "init_boot")) {
            val h = Harness().apply { environment.initBoot = hasInitBoot }
            var closed = false
            val companion = File(h.root, "vbmeta.img").apply { writeBytes(byteArrayOf(1)) }
            h.defaultSigning = { request ->
                h.events += "open-signing"
                assertEquals(partition, request.targetPartition)
                assertFalse(h.events.contains("begin"))
                object : ModeSwitchSigningContext {
                    override val mode = if (partition == "boot") "CHAINED" else "NON_CHAINED"
                    override fun finish(candidate: InstallCandidateImage, source: File): InstallCandidateImage {
                        assertFalse(closed)
                        assertEquals(partition, candidate.partition)
                        h.events += "sign"
                        val signed = File(h.root, "avb-signed.img").apply { writeBytes(ByteArray(4096) { 11 }) }
                        return candidate.copy(image = signed, sha256 = ModeSwitchHashes.sha256(signed), size = signed.length())
                    }
                    override fun close() { closed = true; companion.delete(); h.events += "close-signing" }
                }
            }
            h.client.pauseCommit = { assertFalse(closed); assertTrue(companion.isFile) }
            val result = h.facade().startSwitch(h.to, useDefaultSigning = true) as SwitchOutcome.AwaitingReboot
            h.inOrder("acquire-lease", "open-signing", "begin", "snapshot", "produce", "sign", "submit", "commit", "release-lease", "close-signing")
            assertEquals(partition, h.client.plans.last()["targetPartition"])
            assertEquals(if (partition == "boot") "CHAINED" else "NON_CHAINED", h.client.plans.last()["lenovoSigning"])
            assertEquals(result.candidate!!.sha256, h.client.candidateSha)
            assertTrue(closed)
            assertFalse(companion.exists())
        }
    }

    @Test fun unsignedPageDefaultsLeaveTheOrdinaryCandidateAndPlanIntact() = runBlocking {
        val h = Harness().apply { defaultSigning = { events += "resolve-signing"; null } }
        val result = h.facade().startSwitch(h.to, useDefaultSigning = true) as SwitchOutcome.AwaitingReboot
        assertTrue(h.events.contains("resolve-signing"))
        assertFalse(h.client.plans.last().containsKey("lenovoSigning"))
        assertEquals("candidate.img", result.candidate!!.image.name)
        assertFalse(h.events.contains("sign"))
    }

    @Test fun aDefaultSigningModeForTheWrongCarrierClosesWithoutBeginningOrWriting() = runBlocking {
        val h = Harness().apply {
            defaultSigning = {
                object : ModeSwitchSigningContext {
                    override val mode = "CHAINED"
                    override fun finish(candidate: InstallCandidateImage, source: File) = error("must not sign")
                    override fun close() { events += "close-signing" }
                }
            }
        }
        val error = runCatching { h.facade().startSwitch(h.to, useDefaultSigning = true) }.exceptionOrNull()
        assertEquals("LENOVO_AVB_PARTITION_INVALID", error?.message)
        assertFalse(h.events.contains("begin"))
        assertTrue(h.events.contains("close-signing"))
        h.assertSourceUntouched()
    }

    @Test fun signingFailureClosesAfterAbortAndLeavesTheSourceUntouched() = runBlocking {
        val h = Harness().apply {
            defaultSigning = {
                object : ModeSwitchSigningContext {
                    override val mode = "NON_CHAINED"
                    override fun finish(candidate: InstallCandidateImage, source: File) = error("LENOVO_AVB_HASH_MISMATCH")
                    override fun close() { events += "close-signing" }
                }
            }
        }
        val result = h.facade().startSwitch(h.to, useDefaultSigning = true) as SwitchOutcome.Failed
        assertEquals("LENOVO_AVB_HASH_MISMATCH", result.code)
        h.inOrder("abort", "release-lease", "close-signing")
        h.assertSourceUntouched()
    }

    @Test fun cancellationKeepsTheSigningContextUntilPartitionAndSourceCompensationFinish() = runBlocking {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        h.defaultSigning = {
            object : ModeSwitchSigningContext {
                override val mode = "NON_CHAINED"
                override fun finish(candidate: InstallCandidateImage, source: File) = candidate
                override fun close() { h.events += "close-signing" }
            }
        }
        h.client.pauseCommit = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
        val running = async { h.facade().startSwitch(h.to, useDefaultSigning = true) }
        entered.await()
        assertFalse(h.events.contains("close-signing"))
        running.cancelAndJoin()
        h.inOrder("rollback-partitions", "restore-source", "restore-hooks", "release-lease", "close-signing")
    }

    @Test fun lenovoUltraReceiptUsesTheFinalSignedHashAndRetainedTransactionCopy() = runBlocking {
        val h = Harness(WorkMode.KSU).apply { credentialFingerprint = { "b".repeat(64) } }
        val result = h.facade().startSwitch(h.to, requestedPartition = "boot", signingMode = "AOSP",
            finalizeCandidate = { patched, _ ->
                val image = File(h.root, "avb-signed.img").apply { writeBytes(ByteArray(4096) { 12 }) }
                patched.copy(image = image, sha256 = ModeSwitchHashes.sha256(image))
            }) as SwitchOutcome.AwaitingReboot
        val record = ModeSwitchProgress(h.progress()).read()!!
        assertEquals(result.candidate!!.sha256, record.ultraBoot!!.candidateSha256)
        assertEquals("signed-boot.img", record.ultraBoot.candidateName)
        assertEquals(h.id, h.ultraRecovery())
    }

    @Test fun failedLenovoSigningCancelsPreparationWithoutWithdrawingTheSourceOrWriting() = runBlocking {
        val h = Harness()
        val result = h.facade().startSwitch(h.to, requestedPartition = "init_boot", signingMode = "NON_CHAINED",
            finalizeCandidate = { _, _ -> error("LENOVO_AVB_HASH_MISMATCH") }) as SwitchOutcome.Failed
        assertEquals("LENOVO_AVB_HASH_MISMATCH", result.code)
        assertTrue(h.events.contains("abort"))
        assertFalse(h.events.contains("submit"))
        h.assertSourceUntouched()
        assertEquals("CANCELLED", h.client.current?.state)
    }

    @Test fun downloadedLenovoCandidateIsSignedAfterSnapshotWithoutProducingAnotherImage() = runBlocking {
        val h = Harness()
        val candidate = h.candidate("init_boot")
        val result = h.facade().startSwitch(h.to, requestedPartition = "init_boot", signingMode = "NON_CHAINED",
            providedCandidate = candidate, finalizeCandidate = { patched, _ ->
                assertEquals(candidate.sha256, patched.sha256)
                h.events += "sign"
                patched
            })
        assertTrue(result.toString(), result is SwitchOutcome.AwaitingReboot)
        h.inOrder("snapshot", "sign", "submit", "commit")
        assertFalse(h.events.contains("produce"))
    }

    @Test fun abortReleasesThePersistentLeaseEvenWithoutAnAppProgressFile() = runBlocking {
        val h = Harness(WorkMode.LITE)
        h.client.current = h.status("PRODUCE")
        assertFalse(h.progress().exists())
        val result = h.facade().abort(h.id)
        assertTrue(result.toString(), result?.ok == true)
        h.inOrder("acquire-lease", "abort", "release-lease")
    }

    @Test fun resumedServiceAbortAndRollbackUseTheOriginalServiceLockWithoutTakingAUltraLease() = runBlocking {
        for (rollback in listOf(false, true)) {
            val h = Harness()
            h.client.current = h.status(if (rollback) "REBOOT_REQUIRED" else "PRODUCE")
            val ultra = object : ModeSwitchClient by h.client {
                override fun statusExact(switchId: String): ClientStatus? = null
            }
            val resumed = ServiceResumeModeSwitchClient(ultra, h.client) { true }
            assertEquals(h.id, resumed.statusExact(h.id)!!.switchId)
            val facade = h.facade(resumed)
            val result = if (rollback) facade.rollback(h.id) else facade.abort(h.id)
            assertTrue(result.toString(), result?.ok == true)
            assertTrue(h.events.contains(if (rollback) "rollback-partitions" else "abort"))
            assertFalse(h.events.contains("acquire-lease"))
            assertFalse(h.events.contains("release-lease"))
        }
    }

    @Test fun resumedUltraRecoveryStillAcquiresAndReleasesItsLeaseOnce() = runBlocking {
        val h = Harness()
        h.client.current = h.status("PRODUCE")
        val service = object : ModeSwitchClient by h.client {
            override fun statusExact(switchId: String): ClientStatus? = error("no SERVICE history")
        }
        val resumed = ServiceResumeModeSwitchClient(h.client, service) { false }
        assertTrue(h.facade(resumed).abort(h.id)?.ok == true)
        assertEquals(1, h.events.count { it == "acquire-lease" })
        assertEquals(1, h.events.count { it == "release-lease" })
        h.inOrder("acquire-lease", "abort", "release-lease")
    }

    @Test fun newUltraTaskInsideTheRecoveryAdapterStillMovesConfigurationInTheFacade() = runBlocking {
        val h = Harness()
        val service = object : ModeSwitchClient by h.client {
            override fun statusExact(switchId: String): ClientStatus? = error("no SERVICE history")
        }
        val resumed = ServiceResumeModeSwitchClient(h.client, service) { error("must not inspect history") }
        val result = h.facade(resumed).startSwitch(h.to)
        assertTrue(result.toString(), result is SwitchOutcome.AwaitingReboot)
        h.inOrder("move-out", "commit", "move-in", "materialize")
        assertEquals(1, h.events.count { it == "acquire-lease" })
    }

    @Test fun reopeningLegacyRecoveryPreservesServiceReadAndDualPendingErrors() = runBlocking {
        for (conflict in listOf(false, true)) {
            val h = Harness()
            h.client.current = h.status("REBOOT_REQUIRED")
            val original = IllegalStateException("original-SERVICE-read-error")
            val ultra = object : ModeSwitchClient by h.client {
                override fun statusExact(switchId: String): ClientStatus? = if (conflict)
                    h.status("REBOOT_REQUIRED").copy(switchId = "d8472b5a-4e2f-4a20-9a26-aa8a9cc70293") else null
            }
            val service = object : ModeSwitchClient by h.client {
                override fun statusExact(switchId: String): ClientStatus? {
                    if (!conflict) throw original
                    return h.client.status(switchId)
                }
            }
            val resumed = ServiceResumeModeSwitchClient(ultra, service) { true }
            val failure = runCatching { h.facade(resumed).reopenAfterReboot() }.exceptionOrNull()
            if (conflict) assertTrue(failure?.message.orEmpty().startsWith(ModeSwitchCodes.PLAN_CONFLICT))
            else assertSame(original, failure)
            assertFalse(h.events.contains("verify"))
        }
    }

    @Test fun sdkUninstallRunsOnlyAfterImagesAndDataAreCommittedAtReboot() = runBlocking {
        val h = Harness(WorkMode.LITE)
        val facade = h.facade()
        val started = facade.startSwitch(h.to) as SwitchOutcome.AwaitingReboot
        assertFalse(h.events.contains("complete-environment"))
        assertTrue(facade.reboot(started.switchId))
        h.inOrder("submit", "move-out", "commit", "move-in", "materialize", "complete-environment",
            "root:/system/bin/svc power reboot")
    }

    @Test fun helperRebootAcknowledgementDoesNotNeedASecondManagerRequest() = runBlocking {
        val h = Harness(WorkMode.LITE)
        val started = h.facade().startSwitch(h.to) as SwitchOutcome.AwaitingReboot
        h.events.clear()
        h.completeResult = HookResult(true, ModeSwitchRootProtocol.REBOOT_REQUESTED)
        assertTrue(h.facade().reboot(started.switchId))
        assertTrue(h.events.contains("complete-environment"))
        assertFalse(h.events.any { it.startsWith("root:") })
        assertTrue(h.events.contains("release-lease"))
    }

    @Test fun sdkUninstallAfterManagerRestartUsesTheCommittedProgress() = runBlocking {
        val h = Harness(WorkMode.LITE)
        val started = h.facade().startSwitch(h.to) as SwitchOutcome.AwaitingReboot
        h.events.clear()
        assertTrue(h.facade().reboot(started.switchId))
        h.inOrder("prepare", "acquire-lease", "mark-reboot", "complete-environment",
            "root:/system/bin/svc power reboot")
        assertTrue(h.progress().exists())
        assertFalse(h.events.contains("move-out"))
        assertFalse(h.events.contains("commit"))
        assertFalse(h.events.contains("restore-source"))
    }

    @Test fun failedFinalSdkUninstallKeepsCommittedImagesAndAllowsAnExplicitRetry() = runBlocking {
        val h = Harness(WorkMode.LITE)
        val started = h.facade().startSwitch(h.to) as SwitchOutcome.AwaitingReboot
        h.events.clear()
        h.completeResult = HookResult.failed("SDK_FAILED")
        assertFalse(h.facade().reboot(started.switchId))
        assertFalse(h.events.any { it.startsWith("root:") })
        assertFalse(h.events.contains("rollback-partitions"))
        assertTrue(h.events.contains("release-lease"))
        assertTrue(h.progress().exists())
        assertEquals("REBOOT_REQUIRED", h.client.current?.state)
        h.completeResult = HookResult.OK
        assertTrue(h.facade().reboot(started.switchId))
    }

    @Test fun completedWriteReleasesTheRealWriterLeaseBeforeAnotherInstallInTheSameBoot() = runBlocking {
        val h = Harness()
        val installer = File(h.root, "installer").apply { mkdirs() }
        ModeSwitchRootLease(root = installer,
            acquireLock = { dev.maplesu.manager.installer.InstallerDeviceLock.acquireLockFile(installer) },
            syncDirectory = {}, recoveryRoot = File(h.root, "recovery")).use { lease ->
            h.rootLease = lease
            val facade = h.facade()
            val first = facade.startSwitch(h.to) as SwitchOutcome.AwaitingReboot
            val firstProgress = h.progress()
            val second = facade.startSwitch(h.to)
            assertTrue("An idle helper must not exclude a new install: $second", second is SwitchOutcome.AwaitingReboot)
            assertNotEquals(first.switchId, (second as SwitchOutcome.AwaitingReboot).switchId)
            assertTrue("Recovery evidence remains available", firstProgress.isFile)
            assertFalse("Completed writes do not retain a physical lock", lease.isHeld())
        }
    }

    @Test fun successfulSwitchReturnsTheExactSubmittedBootForExport() = runBlocking {
        for (from in listOf(WorkMode.LITE, WorkMode.KSU)) {
            val h = Harness(from)
            val result = h.facade().startSwitch(h.to) as SwitchOutcome.AwaitingReboot
            val candidate = requireNotNull(result.candidate)
            assertEquals(h.client.submittedImage, candidate.image)
            assertEquals(h.client.candidateSha, candidate.sha256)
            assertEquals(candidate.image.length(), candidate.size)
            assertEquals(h.client.selectedPartition, candidate.partition)
            assertTrue(candidate.image.isFile)
        }
    }

    @Test fun mirrorExportFailureBeforeBeginIsBlockedAndLeavesNoPendingTransaction() = runBlocking {
        val h = Harness(WorkMode.LITE)
        h.environment.beginFailure = "SUPERUSER_BACKEND_UNAVAILABLE"
        val result = h.facade().startSwitch(h.to, requestedSwitchId = h.id)
        assertTrue("No transaction has begun: $result", result is SwitchOutcome.Blocked)
        assertEquals("SWITCH_EXPORT_REQUIRED", (result as SwitchOutcome.Blocked).code)
        assertEquals("SUPERUSER_BACKEND_UNAVAILABLE", result.detail)
        assertNull(h.client.current)
        assertFalse(h.events.contains("begin"))
        assertFalse(h.events.contains("abort"))
        assertTrue(h.events.contains("release-lease"))
        assertFalse(h.progress().exists())
        h.assertSourceUntouched()
    }

    @Test fun mirrorExportCanBeRetriedAfterItsBeforeBeginFailure() = runBlocking {
        val h = Harness(WorkMode.LITE)
        val facade = h.facade()
        h.environment.beginFailure = "SUPERUSER_BACKEND_UNAVAILABLE"
        assertTrue(facade.startSwitch(h.to, requestedSwitchId = h.id) is SwitchOutcome.Blocked)
        h.environment.beginFailure = ""
        assertTrue(facade.startSwitch(h.to, requestedSwitchId = h.id) is SwitchOutcome.AwaitingReboot)
        assertEquals(1, h.events.count { it == "begin" })
    }

    @Test fun ultraImagesAreSubmittedBeforeSourceTeardown() = runBlocking {
        val h = Harness(WorkMode.LITE)
        assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.AwaitingReboot)
        h.inOrder("produce", "submit", "producer-cleanup", "quiesce", "withdraw-implants",
            "withdraw", "move-out", "commit", "move-in", "materialize", "mark-reboot")
        assertFalse(h.events.contains("restore-source"))
        assertFalse(h.events.contains("restore-hooks"))
    }

    @Test fun normalUsesTheSamePreparedImageOrderWithoutUltraImplantWithdrawal() = runBlocking {
        val h = Harness(WorkMode.KSU)
        assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.AwaitingReboot)
        h.inOrder("produce", "submit", "quiesce", "withdraw", "move-out", "commit", "move-in")
        assertFalse(h.events.contains("withdraw-implants"))
    }

    @Test fun failedProducerLeavesSourceAndItsDataUntouched() = runBlocking {
        val h = Harness().apply { produceFails = true }
        assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.Failed)
        h.assertSourceUntouched()
        assertFalse(h.events.any { it.startsWith("rollback-data") })
    }

    @Test fun rejectedCandidateLeavesSourceAndItsDataUntouched() = runBlocking {
        val h = Harness().apply { client.submitResult = ClientResult(false, "SUBMIT_FAILED") }
        assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.Failed)
        h.assertSourceUntouched()
        assertFalse(h.events.any { it.startsWith("rollback-data") })
    }

    @Test fun quiesceFailureDoesNotUninstallOrMoveData() = runBlocking {
        val h = Harness().apply { quietResult = HookResult.failed("QUIET_FAILED") }
        assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.Failed)
        assertFalse(h.events.contains("withdraw"))
        assertFalse(h.events.contains("move-out"))
        assertFalse(h.events.contains("commit"))
        assertTrue(h.events.contains("restore-hooks"))
    }

    @Test fun knownUninstallFailureRestoresSourceBeforeReturning() = runBlocking {
        val h = Harness().apply { withdrawResult = HookResult.failed("UNINSTALL_FAILED") }
        assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.Failed)
        h.inOrder("withdraw", "restore-source", "restore-hooks")
        assertFalse(h.events.contains("move-out"))
        assertFalse(h.events.contains("commit"))
    }

    @Test fun uncertainUninstallStopsWithoutAbortOrSourceRecovery() = runBlocking {
        val h = Harness().apply { withdrawResult = HookResult.failed(UNCERTAIN) }
        val result = h.facade().startSwitch(h.to)
        assertTrue(result is SwitchOutcome.Failed)
        assertEquals(UNCERTAIN, (result as SwitchOutcome.Failed).code)
        assertFalse(h.events.contains("abort"))
        assertFalse(h.events.contains("restore-source"))
        assertFalse(h.events.contains("restore-hooks"))
        assertFalse(h.events.contains("move-out"))
        assertTrue(h.progress().isFile)
    }

    @Test fun partialOutboundFailureRestoresOnlyOutboundData() = runBlocking {
        val h = Harness().apply { outResult = InstallMoveOutcome.failed("OUT_FAILED") }
        assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.Failed)
        h.inOrder("move-out", "rollback-data:OUT", "restore-source", "restore-hooks")
        assertFalse(h.events.contains("commit"))
    }

    @Test fun failedPartitionCommitRollsPartitionsBackBeforeSourceData() = runBlocking {
        val h = Harness().apply { client.commitFails = true }
        assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.Failed)
        h.inOrder("commit", "rollback-partitions", "rollback-data:OUT", "restore-source", "restore-hooks")
    }

    @Test fun failedPartitionRollbackKeepsDataAndWritersFrozen() = runBlocking {
        val h = Harness().apply {
            client.commitFails = true
            client.rollbackFails = true
        }
        val result = h.facade().startSwitch(h.to)
        assertTrue(result is SwitchOutcome.Failed)
        assertFalse(h.events.any { it.startsWith("rollback-data") })
        assertFalse(h.events.contains("restore-source"))
        assertFalse(h.events.contains("restore-hooks"))
        assertTrue(h.progress().isFile)
    }

    @Test fun failedInboundRollsPartitionsBackBeforeBothDataPhases() = runBlocking {
        val h = Harness().apply { inResult = InstallMoveOutcome.failed("IN_FAILED") }
        assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.Failed)
        h.inOrder("move-in", "rollback-partitions", "rollback-data:OUT,IN", "restore-source", "restore-hooks")
    }

    @Test fun failedMaterializationRollsPartitionsBackBeforeBothDataPhases() = runBlocking {
        val h = Harness().apply { materializeResult = InstallMoveOutcome.failed("MATERIALIZE_FAILED") }
        assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.Failed)
        h.inOrder("materialize", "rollback-partitions", "rollback-data:OUT,IN", "restore-source", "restore-hooks")
    }

    @Test fun completedDataStagesSurviveReopeningInBothDirections() = runBlocking {
        listOf(WorkMode.LITE, WorkMode.KSU).forEach { from ->
            val h = Harness(from)
            assertTrue(h.facade().startSwitch(h.to) is SwitchOutcome.AwaitingReboot)
            assertTrue(h.progress().isFile)
            h.events.clear()
            h.environment.active = h.to
            h.client.verifySuccess = true
            assertTrue(h.facade().reopenAfterReboot() is SwitchOutcome.Verified)
            h.inOrder("verify", "commit-mode")
            assertFalse(h.events.contains("move-out"))
            assertFalse(h.events.contains("move-in"))
            assertFalse(h.progress().exists())
        }
    }

    @Test fun appDeathAfterPartitionCommitFinishesDataBeforeVerification() = runBlocking {
        listOf(WorkMode.LITE, WorkMode.KSU).forEach { from ->
            val h = Harness(from)
            h.seed("REBOOT_REQUIRED", setOf(InstallMovePhase.OUT))
            h.environment.active = h.to
            h.client.verifySuccess = true
            assertTrue(h.facade().reopenAfterReboot() is SwitchOutcome.Verified)
            h.inOrder("move-in", "materialize", "verify", "commit-mode")
            assertFalse(h.events.contains("withdraw"))
            assertFalse(h.events.contains("move-out"))
            assertFalse(h.progress().exists())
        }
    }

    @Test fun appDeathDuringInboundRetriesInboundBeforeVerification() = runBlocking {
        val h = Harness()
        h.seed("VERIFIED", setOf(InstallMovePhase.OUT, InstallMovePhase.IN))
        h.environment.active = h.to
        assertTrue(h.facade().reopenAfterReboot() is SwitchOutcome.Verified)
        h.inOrder("move-in", "materialize", "commit-mode")
        assertFalse(h.events.contains("withdraw"))
    }

    @Test fun interruptedPrecommitRestoresDataThenSourceAndAborts() = runBlocking {
        val h = Harness()
        h.seed("PRODUCE", setOf(InstallMovePhase.OUT))
        assertTrue(h.facade().reopenAfterReboot() is SwitchOutcome.Failed)
        h.inOrder("rollback-data:OUT", "restore-source", "restore-hooks", "abort")
        assertFalse(h.events.contains("rollback-partitions"))
        assertFalse(h.events.contains("verify"))
        assertFalse(h.progress().exists())
    }

    @Test fun uncertainHelperOnReopenLeavesRecoveryJournalAndDataUntouched() = runBlocking {
        val h = Harness().apply { prepareResult = HookResult.failed(UNCERTAIN) }
        h.seed("PRODUCE", setOf(InstallMovePhase.OUT))
        val result = h.facade().reopenAfterReboot()
        assertTrue(result is SwitchOutcome.Failed)
        assertEquals(UNCERTAIN, (result as SwitchOutcome.Failed).code)
        assertFalse(h.events.any { it.startsWith("rollback") })
        assertFalse(h.events.contains("restore-source"))
        assertTrue(h.progress().isFile)
    }

    @Test fun manualRollbackRestoresSourceDataAndLifecycle() = runBlocking {
        val h = Harness()
        h.seed("REBOOT_REQUIRED", setOf(InstallMovePhase.OUT, InstallMovePhase.IN), waiting = true)
        assertTrue(h.facade().rollback()?.ok == true)
        h.inOrder("rollback-partitions", "rollback-data:OUT,IN", "restore-source", "restore-hooks")
        assertFalse(h.progress().exists())
    }

    @Test fun manualAbortRestoresPartiallyMovedDataAndLifecycle() = runBlocking {
        val h = Harness()
        h.seed("PRODUCE", setOf(InstallMovePhase.OUT))
        assertTrue(h.facade().abort()?.ok == true)
        h.inOrder("rollback-data:OUT", "restore-source", "restore-hooks")
        assertTrue(h.events.contains("abort"))
        assertFalse(h.progress().exists())
    }

    @Test fun retryVerificationCompletesInterruptedDataStages() = runBlocking {
        val h = Harness()
        h.seed("REBOOT_REQUIRED", setOf(InstallMovePhase.OUT))
        h.environment.active = h.to
        h.client.verifySuccess = true
        assertTrue(h.facade().retryVerify(h.id) is SwitchOutcome.Verified)
        h.inOrder("move-in", "materialize", "verify")
        assertFalse(h.progress().exists())
    }

    @Test fun everyMutatingEntryPointHonorsTheSameSwitchLock() = runBlocking {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.client.pauseCommit = {
            entered.complete(Unit)
            release.await()
        }
        val facade = h.facade()
        val running = async { facade.startSwitch(h.to) }
        entered.await()
        assertTrue(facade.operationActive)
        val competingStart = facade.startSwitch(h.to)
        val aborted = facade.abort()
        val rolledBack = facade.rollback()
        val retried = facade.retryVerify(h.id)
        val reopened = facade.reopenAfterReboot()
        release.complete(Unit)
        running.await()
        assertFalse(facade.operationActive)
        assertTrue(competingStart is SwitchOutcome.Blocked)
        assertTrue(reopened is SwitchOutcome.Blocked)
        assertTrue(retried is SwitchOutcome.Blocked)
        assertTrue(aborted?.ok != true)
        assertTrue(rolledBack?.ok != true)
        assertFalse(h.events.contains("abort"))
        assertFalse(h.events.contains("rollback-partitions"))
        assertFalse(h.events.contains("verify"))
    }

    @Test fun failedSourceRestorationReleasesTheIdleLeaseAndRetainsRecoveryJournal() = runBlocking {
        val h = Harness().apply {
            client.commitFails = true
            sourceRestoreResult = HookResult.failed("SOURCE_RESTORE_FAILED")
        }
        val result = h.facade().startSwitch(h.to) as SwitchOutcome.Failed
        assertEquals("SOURCE_RESTORE_FAILED", result.code)
        assertTrue(h.events.contains("release-lease"))
        assertTrue(h.progress().isFile)
    }

    @Test fun failedHookRestorationReleasesTheIdleLeaseAndRetainsRecoveryJournal() = runBlocking {
        val h = Harness().apply {
            client.commitFails = true
            hookRestoreResult = HookResult.failed("HOOK_RESTORE_FAILED")
        }
        val result = h.facade().startSwitch(h.to) as SwitchOutcome.Failed
        assertEquals("HOOK_RESTORE_FAILED", result.code)
        assertTrue(h.events.contains("release-lease"))
        assertTrue(h.progress().isFile)
    }

    @Test fun uncertainSwitchStagesDoNotStartCompetingCompensation() = runBlocking {
        listOf("submit", "out", "commit", "in", "materialize").forEach { stage ->
            val h = Harness().apply {
                when (stage) {
                    "submit" -> client.submitResult = ClientResult(false, UNCERTAIN)
                    "out" -> outResult = InstallMoveOutcome.failed(UNCERTAIN)
                    "commit" -> client.commitCode = UNCERTAIN
                    "in" -> inResult = InstallMoveOutcome.failed(UNCERTAIN)
                    "materialize" -> materializeResult = InstallMoveOutcome.failed(UNCERTAIN)
                }
            }
            val result = h.facade().startSwitch(h.to) as SwitchOutcome.Failed
            assertEquals("stage=$stage events=${h.events}", UNCERTAIN, result.code)
            assertFalse("stage=$stage", h.events.any { it.startsWith("rollback") })
            assertFalse("stage=$stage", h.events.contains("abort"))
            assertFalse("stage=$stage", h.events.contains("restore-source"))
            assertFalse("stage=$stage", h.events.contains("restore-hooks"))
            assertFalse("stage=$stage", h.events.contains("release-lease"))
            assertTrue("stage=$stage", h.progress().isFile)
        }
    }

    @Test fun failedLeaseReleaseIsReturnedByStartSwitch() = runBlocking {
        val h = Harness().apply { client.commitFails = true; releaseResult = HookResult.failed("LEASE_CLEAR_FAILED") }
        val result = h.facade().startSwitch(h.to) as SwitchOutcome.Failed
        assertEquals("LEASE_CLEAR_FAILED", result.code)
        h.inOrder("restore-source", "restore-hooks", "release-lease")
    }

    @Test fun failedLeaseReleaseIsReturnedByVerificationEntryPoints() = runBlocking {
        listOf(false, true).forEach { retry ->
            val h = Harness().apply { releaseResult = HookResult.failed("LEASE_CLEAR_FAILED") }
            h.seed("VERIFIED", setOf(InstallMovePhase.OUT, InstallMovePhase.IN), waiting = true)
            h.environment.active = h.to
            h.client.verifySuccess = true
            val result = if (retry) h.facade().retryVerify(h.id) else h.facade().reopenAfterReboot()
            assertTrue("$result", result is SwitchOutcome.Incomplete)
            assertEquals("LEASE_CLEAR_FAILED", (result as SwitchOutcome.Incomplete).code)
        }
    }

    @Test fun failedLeaseReleaseIsReturnedByManualRecovery() = runBlocking {
        listOf(false, true).forEach { rollback ->
            val h = Harness().apply { releaseResult = HookResult.failed("LEASE_CLEAR_FAILED") }
            h.seed(if (rollback) "REBOOT_REQUIRED" else "PRODUCE", setOf(InstallMovePhase.OUT))
            val result = if (rollback) h.facade().rollback() else h.facade().abort()
            assertFalse(result?.ok == true)
            assertEquals("LEASE_CLEAR_FAILED", result?.code)
        }
    }

    @Test fun ultraInstallCommitUsesTheSelectedBootCandidateAndFullLifecycle() = runBlocking {
        val h = Harness(WorkMode.KSU).apply {
            environment.provisionerPort = BaselineProvisioner { BaselineProvisionOutcome.Ready("baseline", true, "OEM_PACKAGE") }
        }
        val result = h.facade().installCommit(h.candidate("boot"), target = WorkMode.LITE)
        assertTrue("$result", result is InstallCommitOutcome.AwaitingReboot)
        assertEquals("boot", h.client.selectedPartition)
        h.inOrder("submit", "quiesce", "withdraw", "move-out", "commit", "move-in", "materialize")
        assertFalse(h.events.contains("withdraw-implants"))
    }

    private fun ultraDownloadHarness() = Harness(WorkMode.KSU).apply {
        environment.provisionerPort = BaselineProvisioner { BaselineProvisionOutcome.Ready("baseline", true, "OEM_PACKAGE") }
        credentialFingerprint = { "b".repeat(64) }
    }

    @Test fun downloadedUltraInstallStagesTheExactCandidateAndPublishesRecoveryOnlyAfterCommit() = runBlocking {
        val h = ultraDownloadHarness()
        val candidate = h.candidate("boot")
        h.client.pauseCommit = {
            assertNull("an in-progress commit must not authorize Boot setup", h.ultraRecovery())
            assertEquals(File(h.work, h.id).canonicalFile, h.client.submittedImage!!.canonicalFile.parentFile)
            assertEquals(candidate.sha256, ModeSwitchHashes.sha256(h.client.submittedImage!!))
        }
        val result = h.facade().installCommit(candidate, h.id, target = WorkMode.LITE)
        assertTrue("$result", result is InstallCommitOutcome.AwaitingReboot)
        assertEquals(h.id, h.ultraRecovery())
        val boot = ModeSwitchProgress(h.progress()).read()!!.ultraBoot!!
        assertEquals(candidate.sha256, boot.candidateSha256)
        assertEquals(h.client.submittedImage!!.name, boot.candidateName)
        assertTrue("the downloaded source must remain intact", candidate.image.isFile)
    }

    @Test fun downloadedUltraWithExistingKeyCanSetupWithoutPendingInstallTaskAfterBoot() = runBlocking {
        val h = ultraDownloadHarness()
        val result = h.facade().installCommit(h.candidate("boot"), h.id, target = WorkMode.LITE)
        assertTrue("$result", result is InstallCommitOutcome.AwaitingReboot)
        val calls = mutableListOf<String>()
        val warmup = UltraStartupWarmup.run(UltraStartupWarmup.Input(
            activeMode = null, detectionSettled = true, ksuResponded = false,
            pendingInstall = false, suspended = false, quiescing = false,
            switchPending = true, conflictingRoot = false,
            postBootUltraSwitchRecovery = h.ultraRecovery() == h.id,
        ), setupEnvironment = { calls += "setup" }, pairEnvironment = { calls += "pair"; true },
            retryDetection = { calls += "detect" })
        assertTrue("existing keys have no new install-task tag, so the committed receipt must qualify", warmup.ran)
        assertEquals(listOf("setup", "pair", "detect"), calls)
    }

    @Test fun downloadedUltraMissingRetainedIdentityNeverSubmitsOrCommits() = runBlocking {
        val h = ultraDownloadHarness().apply { credentialFingerprint = { null } }
        val result = h.facade().installCommit(h.candidate("boot"), h.id, target = WorkMode.LITE)
        assertTrue("$result", result is InstallCommitOutcome.Rejected)
        assertEquals("ULTRA_KERNEL_INSTALL_IDENTITY_MISSING", (result as InstallCommitOutcome.Rejected).code)
        assertFalse(h.events.contains("submit"))
        assertFalse(h.events.contains("commit"))
        assertNull(h.ultraRecovery())
    }

    @Test fun downloadedUltraChangedCandidateNeverSubmitsOrCommits() = runBlocking {
        val h = ultraDownloadHarness()
        val candidate = h.candidate("boot")
        candidate.image.writeBytes(ByteArray(4096) { 8 })
        val result = h.facade().installCommit(candidate, h.id, target = WorkMode.LITE)
        assertTrue("$result", result is InstallCommitOutcome.Rejected)
        assertEquals("KSU_INSTALL_CANDIDATE_CHANGED", (result as InstallCommitOutcome.Rejected).code)
        assertFalse(h.events.contains("submit"))
        assertFalse(h.events.contains("commit"))
        assertNull(h.ultraRecovery())
    }

    @Test fun downloadedUltraSubmitOrCommitFailureNeverPublishesRecoveryReceipt() = runBlocking {
        listOf("submit", "commit").forEach { failed ->
            val h = ultraDownloadHarness().apply {
                if (failed == "submit") client.submitResult = ClientResult(false, "SUBMIT_FAILED")
                else client.commitFails = true
            }
            val result = h.facade().installCommit(h.candidate("boot"), h.id, target = WorkMode.LITE)
            assertTrue("$failed: $result", result is InstallCommitOutcome.Rejected)
            assertNull("$failed must not leave a setup receipt", h.ultraRecovery())
        }
    }

    @Test fun downloadedUltraRollbackRevokesTheCommittedRecoveryReceipt() = runBlocking {
        val h = ultraDownloadHarness()
        val facade = h.facade()
        assertTrue(facade.installCommit(h.candidate("boot"), h.id, target = WorkMode.LITE) is InstallCommitOutcome.AwaitingReboot)
        assertEquals(h.id, h.ultraRecovery())
        assertTrue(facade.rollback(h.id)?.ok == true)
        assertNull(h.ultraRecovery())
    }

    @Test fun installCommitReportsSourceRecoveryAndLeaseReleaseFailures() = runBlocking {
        listOf(false, true).forEach { failedSource ->
            val h = Harness(WorkMode.KSU).apply {
                environment.provisionerPort = BaselineProvisioner { BaselineProvisionOutcome.Ready("baseline", true, "OEM_PACKAGE") }
                client.commitFails = true
                if (failedSource) sourceRestoreResult = HookResult.failed("SOURCE_RESTORE_FAILED")
                else releaseResult = HookResult.failed("LEASE_CLEAR_FAILED")
            }
            val result = h.facade().installCommit(h.candidate("boot"), target = WorkMode.LITE) as InstallCommitOutcome.Rejected
            assertEquals(if (failedSource) "SOURCE_RESTORE_FAILED" else "LEASE_CLEAR_FAILED", result.code)
            assertTrue(result.requiresRecovery)
            assertTrue(h.events.contains("release-lease"))
        }
    }

    @Test fun requestedTaskIdAndNormalBootPartitionReachTheSameTransaction() = runBlocking {
        val h = Harness()
        val id = h.id
        val result = h.facade().startSwitch(h.to, requestedPartition = "boot", requestedSwitchId = id)
        assertTrue("$result", result is SwitchOutcome.AwaitingReboot)
        assertEquals(id, (result as SwitchOutcome.AwaitingReboot).switchId)
        assertEquals(id, h.id)
        assertEquals("boot", h.producedPartition)
        assertEquals("boot", h.client.selectedPartition)
        assertTrue(h.client.plans.all { it["targetPartition"] == "boot" })
    }

    @Test fun malformedRequestedTaskIdDoesNotPrepareOrMutate() = runBlocking {
        val h = Harness()
        val result = h.facade().startSwitch(h.to, requestedSwitchId = "../latest") as SwitchOutcome.Failed
        assertEquals("KSU_INSTALL_SWITCH_ID_INVALID", result.code)
        assertTrue(h.events.isEmpty())
    }

    @Test fun exactRecoveryDoesNotFallBackToLatestTask() = runBlocking {
        listOf(false, true).forEach { rollback ->
            val h = Harness()
            h.seed(if (rollback) "REBOOT_REQUIRED" else "PRODUCE", setOf(InstallMovePhase.OUT))
            val otherId = "d8472b5a-4e2f-4a20-9a26-aa8a9cc70293"
            val facade = h.facade()
            assertNull(facade.statusFor(otherId))
            assertEquals(h.id, facade.statusFor(h.id)?.switchId)
            assertNull(if (rollback) facade.rollback(otherId) else facade.abort(otherId))
            assertFalse(h.events.contains("acquire-lease"))
            assertFalse(h.events.any { it.startsWith("rollback") })
            assertFalse(h.events.contains("abort"))
            assertTrue((if (rollback) facade.rollback(h.id) else facade.abort(h.id))?.ok == true)
        }
    }

    @Test fun rebootExecutesTheActualCommandOnlyForTheExactWaitingTask() = runBlocking {
        val h = Harness()
        h.seed("REBOOT_REQUIRED", setOf(InstallMovePhase.OUT, InstallMovePhase.IN), waiting = true)
        val facade = h.facade()
        assertFalse(facade.reboot("d8472b5a-4e2f-4a20-9a26-aa8a9cc70293"))
        assertFalse(h.events.contains("mark-reboot"))
        assertFalse(h.events.any { it.startsWith("root:") })
        h.events.clear()
        assertTrue(facade.reboot(h.id))
        h.inOrder("prepare", "acquire-lease", "mark-reboot", "root:/system/bin/svc power reboot")
        assertTrue(h.events.contains("release-lease"))
    }

    @Test fun rebootRequiresPreparationWaitingStateAndSuccessfulMark() = runBlocking {
        listOf("prepare", "state", "mark").forEach { failed ->
            val h = Harness()
            h.seed(if (failed == "state") "VERIFIED" else "REBOOT_REQUIRED", emptySet(), waiting = true)
            if (failed == "prepare") h.prepareResult = HookResult.failed("NO_ROOT")
            if (failed == "mark") h.client.markRebootResult = false
            assertFalse(h.facade().reboot(h.id))
            assertFalse(h.events.any { it.startsWith("root:") })
        }
    }

    @Test fun failedSourceRestoreOnReopenReleasesTheLeaseAndPreservesProgress() = runBlocking {
        val h = Harness().apply { sourceRestoreResult = HookResult.failed("SOURCE_RESTORE_FAILED") }
        h.seed("ROLLED_BACK", setOf(InstallMovePhase.OUT))
        val result = h.facade().reopenAfterReboot() as SwitchOutcome.Failed
        assertEquals("SOURCE_RESTORE_FAILED", result.code)
        assertTrue(h.events.contains("release-lease"))
        assertTrue(h.progress().exists())
    }

    @Test fun exactStatusDistinguishesAReadFailureFromAnAbsentRecord() = runBlocking {
        val h = Harness()
        val facade = h.facade()
        assertNull(facade.statusFor(h.id))
        h.prepareResult = HookResult.failed("ROOT_PREPARE_FAILED")
        assertEquals("ROOT_PREPARE_FAILED", runCatching { facade.statusFor(h.id) }.exceptionOrNull()?.message)
        h.prepareResult = HookResult.OK
        h.client.statusFailure = IllegalStateException("STATUS_READ_FAILED")
        assertEquals("STATUS_READ_FAILED", runCatching { facade.statusFor(h.id) }.exceptionOrNull()?.message)
        assertFalse(h.events.contains("acquire-lease"))
    }

    @Test fun rollbackAfterTargetBootRetainsRebootUntilAnotherBootIsObserved() = runBlocking {
        val h = Harness()
        h.seed("REBOOT_REQUIRED", setOf(InstallMovePhase.OUT, InstallMovePhase.IN), waiting = true)
        h.client.current = h.client.current!!.copy(bootObserved = true)
        h.environment.active = h.to
        val restored = h.facade().rollback(h.id)!!
        assertTrue(restored.ok)
        assertEquals("ROLLED_BACK", restored.status?.state)
        assertTrue(restored.status?.rebootRequired == true)
        assertFalse(h.progress().exists())
        assertTrue(h.facade().statusFor(h.id)?.rebootRequired == true)
        assertTrue(h.facade().canSwitch(h.from) is SwitchEligibility.Allowed)
        h.environment.currentBoot = "restored-boot"
        assertFalse(h.facade().statusFor(h.id)?.rebootRequired == true)
    }

    @Test fun sameBootRollbackRequiresNoExtraReboot() = runBlocking {
        val h = Harness()
        h.seed("REBOOT_REQUIRED", setOf(InstallMovePhase.OUT, InstallMovePhase.IN), waiting = true)
        val restored = h.facade().rollback(h.id)!!
        assertTrue(restored.ok)
        assertFalse(restored.status?.rebootRequired == true)
        assertNull(ModeSwitchProgress(h.progress()).rollbackBoot())
        assertFalse(h.facade().reboot(h.id))
    }

    @Test fun restoredRebootChecksPreImagesAndNeverCallsTheForwardRebootMarker() = runBlocking {
        val h = Harness()
        h.seed("REBOOT_REQUIRED", setOf(InstallMovePhase.OUT, InstallMovePhase.IN), waiting = true)
        h.client.current = h.client.current!!.copy(bootObserved = true)
        assertTrue(h.facade().rollback(h.id)?.ok == true)
        h.events.clear()
        assertTrue(h.facade().reboot(h.id))
        assertFalse(h.events.contains("mark-reboot"))
        val verification = h.events.first { it.startsWith("root:set -e") }
        assertTrue(verification.contains("/dev/block/by-name/boot_a"))
        assertTrue(verification.contains("/dev/block/by-name/init_boot_a"))
        assertTrue(verification.contains("blockdev --getsize64"))
        assertTrue(verification.contains("'" + "a".repeat(64) + "'"))
        assertTrue(h.events.indexOf(verification) < h.events.indexOf("root:/system/bin/svc power reboot"))
        h.environment.currentBoot = "restored-boot"
        h.events.clear()
        assertFalse(h.facade().reboot(h.id))
        assertFalse(h.events.any { it.startsWith("root:") })
    }

    @Test fun restoredRebootRejectsChangedSlotOrFailedPartitionVerification() = runBlocking {
        listOf("slot", "digest", "record", "uncertain").forEach { changed ->
            val h = Harness()
            h.seed("REBOOT_REQUIRED", setOf(InstallMovePhase.OUT), waiting = true)
            h.client.current = h.client.current!!.copy(bootObserved = true)
            assertTrue(h.facade().rollback(h.id)?.ok == true)
            if (changed == "slot") h.environment.slot = "_b"
            if (changed == "digest") h.rollbackImageCheck = RootScriptResult(false, 74, "")
            if (changed == "record") h.client.current = h.client.current!!.copy(partitions = emptyList())
            if (changed == "uncertain") h.rollbackImageCheck = RootScriptResult(true, 0,
                "MODE_SWITCH_ROLLBACK_IMAGES_OK", UNCERTAIN)
            h.events.clear()
            assertFalse(h.facade().reboot(h.id))
            assertFalse(h.events.contains("root:/system/bin/svc power reboot"))
        }
    }

    @Test fun failedRollbackNeverProjectsARestoredReboot() = runBlocking {
        val h = Harness().apply { client.rollbackFails = true }
        h.seed("REBOOT_REQUIRED", setOf(InstallMovePhase.OUT), waiting = true)
        h.client.current = h.client.current!!.copy(bootObserved = true)
        assertFalse(h.facade().rollback(h.id)?.ok == true)
        assertEquals("RECOVERY_REQUIRED", h.facade().statusFor(h.id)?.state)
        assertFalse(h.facade().statusFor(h.id)?.rebootRequired == true)
        assertFalse(ModeSwitchProgress(h.progress()).rollbackBoot()!!.ready)
    }

    @Test fun failedSourceRecoveryKeepsRollbackPendingUntilItsRetryCompletes() = runBlocking {
        val h = Harness().apply { sourceRestoreResult = HookResult.failed("SOURCE_RESTORE_FAILED") }
        h.seed("REBOOT_REQUIRED", setOf(InstallMovePhase.OUT, InstallMovePhase.IN), waiting = true)
        h.client.current = h.client.current!!.copy(bootObserved = true)
        assertFalse(h.facade().rollback(h.id)?.ok == true)
        val pending = h.facade().statusFor(h.id)!!
        assertEquals("RECOVERY_REQUIRED", pending.state)
        assertTrue(pending.canRollback)
        assertFalse(pending.rebootRequired)
        h.events.clear()
        assertFalse(h.facade().reboot(h.id))
        assertTrue(h.events.contains("release-lease"))
        h.sourceRestoreResult = HookResult.OK
        assertTrue(h.facade().rollback(h.id)?.status?.rebootRequired == true)
    }

    @Test fun reopeningAfterPartitionRollbackResumesSourceRecoveryBeforeReboot() = runBlocking {
        val h = Harness()
        h.seed("ROLLED_BACK", setOf(InstallMovePhase.OUT, InstallMovePhase.IN), waiting = true)
        ModeSwitchProgress(h.progress()).recordRollbackBoot(h.environment.currentBoot, "_a")
        h.facade().reopenAfterReboot()
        h.inOrder("rollback-data:OUT,IN", "restore-source", "restore-hooks")
        assertTrue(h.facade().statusFor(h.id)?.rebootRequired == true)
        assertFalse(h.progress().exists())
    }

    private class Harness(val from: WorkMode = WorkMode.LITE) {
        val to = if (from == WorkMode.LITE) WorkMode.KSU else WorkMode.LITE
        val root = Files.createTempDirectory("mode-switch-lifecycle-").toFile()
        val work = File(root, "work")
        val events = mutableListOf<String>()
        val client = FakeClient(this)
        val environment = FakeEnvironment(this)
        var id = "0f8fad5b-d9cb-469f-a165-70867728950e"
        var produceFails = false
        var producedPartition: String? = null
        var prepareResult = HookResult.OK
        var quietResult = HookResult.OK
        var withdrawResult = HookResult.OK
        var completeResult = HookResult.OK
        var outResult = InstallMoveOutcome.OK
        var inResult = InstallMoveOutcome.OK
        var materializeResult = InstallMoveOutcome.OK
        var sourceRestoreResult = HookResult.OK
        var hookRestoreResult = HookResult.OK
        var releaseResult = HookResult.OK
        var rootLease: ModeSwitchRootLease? = null
        var rollbackImageCheck = RootScriptResult(true, 0, "MODE_SWITCH_ROLLBACK_IMAGES_OK")
        var credentialFingerprint: (() -> String?)? = null
        var defaultSigning: ((ModeSwitchRequest) -> ModeSwitchSigningContext?)? = null

        fun ultraRecovery() = ModeSwitchProgress.postBootUltraRecovery(work, "new-ultra-boot", "b".repeat(64))

        fun facade(overrideClient: ModeSwitchClient? = null): ModeSwitchFacade {
            val selecting = SelectingModeSwitchClient(client, { client }, { true })
            val hooks = object : SwitchQuiesceHooks {
                override fun implantRestoreReady() = true
                override fun quiesce(fromMode: WorkMode): HookResult { events += "quiesce"; return quietResult }
                override fun withdrawLiteImplants(): HookResult { events += "withdraw-implants"; return HookResult.OK }
                override fun restoreAfterFailure(fromMode: WorkMode): HookResult { events += "restore-hooks"; return hookRestoreResult }
                override fun restoreProducerSideEffects(): HookResult { events += "producer-cleanup"; return HookResult.OK }
            }
            val lifecycle = object : ModeSwitchEnvironmentLifecycle {
                override fun prepare(): HookResult { events += "prepare"; return prepareResult }
                override fun withdraw(request: ModeSwitchRequest): HookResult { events += "withdraw"; return withdrawResult }
                override fun complete(request: ModeSwitchRequest): HookResult { events += "complete-environment"; return completeResult }
                override fun restoreAfterFailure(request: ModeSwitchRequest): HookResult { events += "restore-source"; return sourceRestoreResult }
            }
            val snapshots = object : ModeSwitchSnapshots {
                override suspend fun preSwitchSnapshot(request: ModeSwitchRequest) = GatewayOutcome(true, "")
                override suspend fun restoreAfterRollback(request: ModeSwitchRequest) = GatewayOutcome(true, "")
            }
            val producer = SwitchCandidateProducer { input ->
                events += "produce"
                producedPartition = input.partition
                if (produceFails) CandidateResult(null, "", 0, "PRODUCE_FAILED") else {
                    val file = File(input.workDir, "candidate.img").apply { writeBytes(ByteArray(4096) { 7 }) }
                    CandidateResult(file, ModeSwitchHashes.sha256(file), file.length())
                }
            }
            return ModeSwitchFacade(
                client = overrideClient ?: selecting, environment = environment,
                producers = mapOf(WorkMode.LITE to producer, WorkMode.KSU to producer), workRoot = work,
                snapshots = snapshots, hooks = hooks, lifecycle = lifecycle,
                moverFactory = { _, _, _ -> object : InstallConfigMover {
                    override fun moveOut(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome {
                        events += "move-out"; return outResult
                    }
                    override fun moveIn(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome {
                        events += "move-in"; return inResult
                    }
                    override fun materialize(switchId: String, from: WorkMode, to: WorkMode): InstallMoveOutcome {
                        events += "materialize"; return materializeResult
                    }
                    override fun rollback(switchId: String, from: WorkMode, to: WorkMode,
                                          completed: Set<InstallMovePhase>): InstallMoveOutcome {
                        events += "rollback-data:" + completed.sortedBy { it.ordinal }.joinToString(",") { it.name }
                        return InstallMoveOutcome.OK
                    }
                } },
                modeCommit = object : ModeCommitSink {
                    override fun commit(mode: WorkMode, bootId: String, bootSha256: String, initBootSha256: String) {
                        events += "commit-mode"
                    }
                },
                pollDelay = {}, maxVerifyPolls = 1,
                acquireLease = { id -> events += "acquire-lease"; rootLease?.acquire(id) ?: HookResult.OK },
                releaseLease = { id -> events += "release-lease"; if (releaseResult.ok) rootLease?.release(id) ?: releaseResult else releaseResult },
                ultraCredentialFingerprint = credentialFingerprint?.let { read -> { _ -> read() } },
                rootRunner = { events += "root:$it"; if (it.contains("MODE_SWITCH_ROLLBACK_IMAGES_OK"))
                    rollbackImageCheck else RootScriptResult(true, 0, "") },
                defaultSigning = { defaultSigning?.invoke(it) },
            )
        }

        fun status(state: String): ClientStatus = ClientStatus(
            id, state, "S4", "commit", from.name, to.name, "", "", "a".repeat(64),
            state == "REBOOT_REQUIRED", state == "VERIFIED",
            state in setOf("REBOOT_REQUIRED", "COMMIT", "RECOVERY_REQUIRED"), false, 0,
            listOf("boot", "init_boot").map { name ->
                ClientPartition(name, if (name == ModeSwitchFacade.targetPartition(to)) "WRITE_CANDIDATE" else "RESTORE_BASELINE",
                    4096, "a".repeat(64), client.candidateSha, client.candidateSha, "DONE")
            },
        )

        fun progress() = File(File(work, id), "switch-progress.properties")

        fun candidate(partition: String): InstallCandidateImage {
            val image = File(root, "selected.img").apply { writeBytes(ByteArray(4096) { 7 }) }
            return InstallCandidateImage(image, ModeSwitchHashes.sha256(image), image.length(), partition)
        }

        fun seed(state: String, phases: Set<InstallMovePhase>, waiting: Boolean = false) {
            client.current = status(state)
            ModeSwitchProgress(progress()).write(ModeSwitchRequest(id, from, to, "baseline"), phases, true, waiting)
        }

        fun inOrder(vararg names: String) {
            var previous = -1
            names.forEach { name ->
                val index = events.indexOf(name)
                assertTrue("Expected $name after index $previous in $events", index > previous)
                previous = index
            }
        }

        fun assertSourceUntouched() {
            listOf("quiesce", "withdraw", "withdraw-implants", "move-out", "commit", "move-in")
                .forEach { assertFalse("Unexpected $it in $events", events.contains(it)) }
        }
    }

    private class FakeEnvironment(private val h: Harness) : ModeSwitchAppEnvironment {
        var active = h.from
        var beginFailure = ""
        var currentBoot = "fixture-boot-id"
        var slot = "_a"
        var initBoot = true
        var provisionerPort: BaselineProvisioner? = null
        private val superuser = File(h.root, "su.json").apply { writeText("{}") }
        private val settings = File(h.root, "settings.json").apply { writeText("{}") }
        override fun activeMode() = active
        override fun hasInitBoot() = initBoot
        override fun modeStateName() = "MODE_A_ACTIVE"
        override fun bootId() = currentBoot
        override fun deviceFacts() = SwitchDeviceFacts(slot, "fixture-firmware", "6.1.100-android14-11-g", "Enforcing")
        override fun battery() = BatteryFacts(80, false)
        override fun freeBytes() = 1L shl 40
        override fun beginInputs() = if (beginFailure.isEmpty()) BeginInputs(superuser, settings)
            else BeginInputs(null, null, beginFailure)
        override fun provisioner() = provisionerPort
    }

    private class FakeClient(private val h: Harness) : ModeSwitchClient {
        var current: ClientStatus? = null
        var submitResult = ClientResult(true)
        var commitFails = false
        var commitCode = ""
        var candidateSha = "b".repeat(64)
        var selectedPartition: String? = null
        var submittedImage: File? = null
        var markRebootResult = true
        var statusFailure: Exception? = null
        val plans = mutableListOf<Map<String, String>>()
        var rollbackFails = false
        var verifySuccess = false
        var pauseCommit: suspend () -> Unit = {}
        override suspend fun preflight(switchId: String, planFields: Map<String, String>): ClientResult {
            plans += planFields; h.events += "preflight"; return ClientResult(true)
        }
        override suspend fun begin(switchId: String, planFields: Map<String, String>, superuserJson: File,
                                   settingsMirrorJson: File): ClientResult {
            plans += planFields; h.id = switchId; current = h.status("BEGIN"); h.events += "begin"; return ClientResult(true)
        }
        override suspend fun snapshot(switchId: String): ClientResult {
            current = h.status("SNAPSHOT"); h.events += "snapshot"; return ClientResult(true)
        }
        override suspend fun exportImage(switchId: String, kind: String, partition: String, destination: File): Boolean {
            h.events += "export"; destination.writeBytes(ByteArray(4096)); return true
        }
        override suspend fun submitCandidate(switchId: String, partition: String, candidate: File,
                                             candidateSha256: String): ClientResult {
            h.events += "submit"; selectedPartition = partition; candidateSha = candidateSha256
            submittedImage = candidate
            current = h.status("PRODUCE"); return submitResult
        }
        override suspend fun commit(switchId: String): ClientResult {
            h.events += "commit"; current = h.status("COMMIT"); pauseCommit()
            current = h.status(if (commitFails || commitCode.isNotEmpty()) "COMMIT" else "REBOOT_REQUIRED")
            return ClientResult(!commitFails && commitCode.isEmpty(), commitCode.ifEmpty { if (commitFails) "WRITE_FAILED" else "" }, status = current)
        }
        override suspend fun rollback(switchId: String): ClientResult {
            h.events += "rollback-partitions"; current = h.status(if (rollbackFails) "RECOVERY_REQUIRED" else "ROLLED_BACK")
            return ClientResult(!rollbackFails, if (rollbackFails) "ROLLBACK_FAILED" else "", status = current)
        }
        override suspend fun abort(switchId: String): ClientResult {
            h.events += "abort"; current = h.status("CANCELLED"); return ClientResult(true, status = current)
        }
        override fun status(switchId: String): ClientStatus? {
            statusFailure?.let { throw it }
            return current?.takeIf { switchId.isEmpty() || it.switchId == switchId }
        }
        override suspend fun verify(switchId: String): ClientResult {
            h.events += "verify"
            if (verifySuccess) current = h.status("VERIFIED")
            return ClientResult(verifySuccess, if (verifySuccess) "" else "NOT_BOOTED", status = current)
        }
        override suspend fun markRebootRequested(switchId: String): Boolean { h.events += "mark-reboot"; return markRebootResult }
        override suspend fun exportLog(switchId: String, destination: File): File? = null
    }

    companion object { private const val UNCERTAIN = "MODE_SWITCH_ROOT_OPERATION_UNCERTAIN" }
}
