package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.workmode.ModeActivation
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Runs the complete production upgrade entrypoint; only external Android/SDK boundaries are fixtures. */
class LegacyUpgradeRegression {
    @Before fun reset() = Fixture.reset()
    private val context get() = Fixture.context
    private val prefs get() = context.getSharedPreferences("maplesu-ultra", 0)
    private val upgradePrefs get() = context.getSharedPreferences("ultra-env-upgrade", 0)
    private fun owner() = UltraRuntimeClient.ManagerOwnerIdentity(10123, 7, "fixture-host")
    private fun setOwner() { Fixture.owner = owner(); Fixture.snapshotOwner = Fixture.owner }
    private fun assertInvalid(facts: UltraEnvFacts) {
        assertTrue(facts.readUnknown); assertTrue(facts.quiescing); assertNull(facts.observationScope)
    }
    private fun marker() = upgradePrefs.getString("submittedBootId", null)

    @Test fun verifiedLegacyGetsLocalObservationScopeWithoutSdkCalls() {
        assertNotNull(UltraEnvironmentUpgrade.observationScope(context))
        assertTrue(Fixture.calls.isEmpty())
    }
    @Test fun unverifiedLegacyHasNoScope() {
        prefs.edit().putBoolean("keyVerified", false).commit()
        assertNull(UltraEnvironmentUpgrade.observationScope(context))
    }
    @Test fun mismatchedVerificationHasNoScope() {
        prefs.edit().putString("keyVerifiedFor", "unrelated-record").commit()
        assertNull(UltraEnvironmentUpgrade.observationScope(context))
    }
    @Test fun legacyReadUsesAuthenticatedStateAndKeepsMissingHelperUnverified() {
        val facts = UltraEnvironmentUpgrade.readFacts(context)
        assertTrue(facts.running); assertFalse(facts.quiescing); assertFalse(facts.readUnknown)
        assertNotNull(facts.observationScope); assertNull(facts.installedHelperSha256)
        assertEquals(UltraEnvUpgrade.Unverified("IDENTITY_INSTALLED_UNKNOWN", "5.2.1"), UltraEnvUpgradePolicy.decide(facts))
    }
    @Test fun busyGlobalProbeDoesNotReplaceBoundRunningReceipt() {
        Fixture.probeState = UltraEnvironmentProbe.State.Unknown
        val facts = UltraEnvironmentUpgrade.readFacts(context)
        assertTrue(Fixture.calls.contains("refresh")); assertTrue(facts.running); assertFalse(facts.readUnknown)
    }
    @Test fun authenticatedUnknownDoesNotInheritCachedRunning() {
        setOwner(); Fixture.state = "Unknown"
        val facts = UltraEnvironmentUpgrade.readFacts(context)
        assertFalse(facts.running); assertTrue(facts.readUnknown)
    }
    @Test fun failedStateReceiptDoesNotInheritCachedRunning() {
        setOwner(); Fixture.stateOk = false
        val facts = UltraEnvironmentUpgrade.readFacts(context)
        assertFalse(facts.running); assertTrue(facts.readUnknown)
    }
    @Test fun ownerTakesPriorityOverStoredLegacy() {
        setOwner()
        val facts = UltraEnvironmentUpgrade.readFacts(context)
        assertTrue(facts.running); assertFalse(facts.readUnknown)
        assertTrue(Fixture.clients.isNotEmpty())
        assertTrue(Fixture.clients.all { it.second.legacyCredentialFingerprint == null })
    }
    @Test fun knownOwnerQueryFailureDoesNotDowngrade() {
        Fixture.snapshotOwner = owner()
        assertInvalid(UltraEnvironmentUpgrade.readFacts(context))
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertTrue(UltraEnvironmentUpgrade.lastFailureReason!!.contains("ERR_MODULE_TEST_CHANNEL:-19999"))
        assertFalse(Fixture.calls.contains("install"))
        assertTrue(Fixture.clients.all { it.second.legacyCredentialFingerprint == null })
    }
    @Test fun liveOwnerWithoutSnapshotNeverUsesLegacyClient() {
        Fixture.owner = owner()
        assertInvalid(UltraEnvironmentUpgrade.readFacts(context))
        assertTrue(Fixture.clients.all { it.second.legacyCredentialFingerprint == null })
    }
    @Test fun staleOwnerSnapshotNeverUsesLegacyOrPublishesFacts() {
        setOwner(); Fixture.owner = owner().copy(epoch = 8)
        assertInvalid(UltraEnvironmentUpgrade.readFacts(context))
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertFalse(Fixture.calls.contains("install"))
        assertTrue(Fixture.clients.all { it.second.legacyCredentialFingerprint == null })
    }
    @Test fun replacedVerifiedLegacyRecordChangesObservationScope() {
        val original = UltraEnvironmentUpgrade.observationScope(context)
        Fixture.verifiedRecord("another-verified-legacy-fixture")
        val replacement = UltraEnvironmentUpgrade.observationScope(context)
        assertNotNull(original); assertNotNull(replacement); assertNotEquals(original, replacement)
    }
    @Test fun ownerAppearingDuringLegacyReadInvalidatesObservation() {
        Fixture.onCall = { op, _ -> if (op == "helper") Fixture.owner = owner() }
        assertInvalid(UltraEnvironmentUpgrade.readFacts(context))
    }
    @Test fun ownerChangingDuringReadInvalidatesObservation() {
        setOwner()
        Fixture.onCall = { op, _ -> if (op == "helper") Fixture.owner = owner().copy(epoch = 8) }
        assertInvalid(UltraEnvironmentUpgrade.readFacts(context))
    }
    @Test fun quiesceStartingAtFinalOwnerRecheckInvalidatesFacts() {
        var ownerReads = 0
        Fixture.onCall = { op, _ -> if (op == "owner" && ++ownerReads == 2)
            dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate.blocked = true }
        assertInvalid(UltraEnvironmentUpgrade.readFacts(context))
    }
    @Test fun lifecycleChangesDuringReadInvalidateObservation() {
        val mutations = listOf<() -> Unit>(
            { Fixture.boot = "boot-2" },
            { ModeRuntime.activation = ModeActivation.Active(WorkMode.LITE) },
            { prefs.edit().putString("rootKey", "changed-credential-fixture").commit() },
            { prefs.edit().putBoolean("keyVerified", false).commit() },
            { prefs.edit().putString("keyVerifiedFor", "changed-verification").commit() },
            { prefs.edit().putString("rootKeySuspended", "paused").commit() },
            { prefs.edit().putString("environmentRemoval", "removing").commit() },
            { dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate.blocked = true },
            { dev.maplesu.manager.workmode.ui.WorkModeSwitchSession.executing = true },
        )
        for (mutate in mutations) {
            Fixture.reset()
            Fixture.onCall = { op, _ -> if (op == "helper") mutate() }
            assertInvalid(UltraEnvironmentUpgrade.readFacts(context))
        }
    }
    @Test fun legacySubmitReusesOneClientForFactsAndInstaller() {
        assertEquals(UltraEnvironmentUpgrade.Result.SUBMITTED, UltraEnvironmentUpgrade.upgrade(context))
        val used = Fixture.clients.filter { it.first in setOf("state", "installed-version", "sdk-version", "helper", "install") }
        assertEquals(setOf("state", "installed-version", "sdk-version", "helper", "install"), used.map { it.first }.toSet())
        assertTrue(used.all { it.second === used.first().second })
        assertTrue(used.all { it.second.legacyCredentialFingerprint != null })
        assertEquals("boot-1", marker())
    }
    @Test fun ownerSubmitReusesOneClient() {
        setOwner()
        assertEquals(UltraEnvironmentUpgrade.Result.SUBMITTED, UltraEnvironmentUpgrade.upgrade(context))
        val used = Fixture.clients
        assertTrue(used.all { it.second === used.first().second })
        assertTrue(used.all { it.second.legacyCredentialFingerprint == null })
    }
    @Test fun explicitLegacyFaultRepairReusesTheSameCapturedClient() {
        Fixture.state = "Fault"; Fixture.probeState = UltraEnvironmentProbe.State.Fault
        assertEquals(UltraEnvironmentUpgrade.Result.SUBMITTED, UltraEnvironmentUpgrade.repairEnvironment(context))
        val used = Fixture.clients
        assertTrue(used.any { it.first == "state" }); assertTrue(used.any { it.first == "install" })
        assertTrue(used.all { it.second === used.first().second })
    }
    @Test fun changedIdentityBeforeSubmitNeverWritesOrMarks() {
        Fixture.onCall = { op, _ -> if (op == "helper") prefs.edit().putBoolean("keyVerified", false).commit() }
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertFalse(Fixture.calls.contains("install")); assertNull(marker())
    }
    @Test fun ownerAppearingBeforeSubmitNeverWrites() {
        Fixture.onCall = { op, _ -> if (op == "helper") Fixture.owner = owner() }
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertFalse(Fixture.calls.contains("install")); assertNull(marker())
    }
    @Test fun changedActivationBeforeSubmitNeverWrites() {
        setOwner()
        Fixture.onCall = { op, _ -> if (op == "helper") ModeRuntime.activation = ModeActivation.Active(WorkMode.LITE) }
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertFalse(Fixture.calls.contains("install")); assertNull(marker())
    }
    @Test fun existingCarrierRoutePrecedesSdkAdmission() {
        Fixture.kernelRequired = true
        assertEquals(UltraEnvironmentUpgrade.Result.SUBMITTED, UltraEnvironmentUpgrade.upgrade(context))
        assertTrue(Fixture.calls.contains("kernel-install")); assertFalse(Fixture.calls.contains("owner"))
        assertFalse(Fixture.calls.contains("install")); assertEquals("boot-1", marker())
    }
    @Test fun carrierLifecycleCheckRemainsEffective() {
        Fixture.kernelRequired = true
        Fixture.onCarrier = { Fixture.boot = "boot-2" }
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertFalse(Fixture.calls.contains("kernel-install")); assertNull(marker())
    }
    @Test fun carrierObservationFailureClearsMarkerAndCanRetryThisBoot() {
        Fixture.kernelRequired = true
        Fixture.onKernelObserve = { error("INSTALLER_PARTITION_READ_FAILED") }
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertTrue(UltraEnvironmentUpgrade.lastFailureReason!!.contains("INSTALLER_PARTITION_READ_FAILED"))
        assertNull(marker()); assertFalse(Fixture.calls.contains("kernel-install"))
        Fixture.onKernelObserve = null
        assertEquals(UltraEnvironmentUpgrade.Result.SUBMITTED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(1, Fixture.calls.count { it == "kernel-install" })
    }
    @Test fun signingPreparationFailureClearsDefiniteNonSubmission() {
        Fixture.kernelRequired = true
        Fixture.onKernelSigning = { error("LENOVO_AVB_INSTALLED_MODE_INVALID") }
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertTrue(UltraEnvironmentUpgrade.lastFailureReason!!.contains("LENOVO_AVB_INSTALLED_MODE_INVALID"))
        assertNull(marker()); assertFalse(Fixture.calls.contains("kernel-install"))
    }
    @Test fun lostCarrierBeforeTransactionClearsMarker() {
        Fixture.kernelRequired = true; Fixture.carrierPartition = null
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertNull(marker()); assertFalse(Fixture.calls.contains("kernel-install"))
    }
    @Test fun changedScopeDuringKernelPreparationRetainsItsRefusalResult() {
        Fixture.kernelRequired = true
        Fixture.onKernelObserve = { ModeRuntime.activation = ModeActivation.Active(WorkMode.LITE) }
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals("KERNEL_PREFLIGHT_ACTIVATION_CHANGED", UltraEnvironmentUpgrade.lastFailureReason)
        assertNull(marker()); assertFalse(Fixture.calls.contains("kernel-install"))
    }
    @Test fun transactionReplyLossRetainsMarkerAndDoesNotRetry() {
        Fixture.kernelRequired = true
        Fixture.onKernelInstall = { error("INSTALLER_TRANSACTION_REPLY_LOST") }
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals("boot-1", marker())
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(1, Fixture.calls.count { it == "kernel-install" })
    }
    @Test fun kernelPreparationCancellationKeepsItsOriginalException() {
        val cancellation = java.util.concurrent.CancellationException("cancel-kernel-preparation")
        Fixture.onKernelObserve = { throw cancellation }
        assertSame(cancellation, assertThrows(java.util.concurrent.CancellationException::class.java) {
            com.maplesu.core.ui.util.installUltraKernelUpdate("fixture-task") {}
        })
        assertFalse(Fixture.calls.contains("kernel-install"))
    }
    private fun initBootComponentRetry(): com.maplesu.core.ui.util.FlashResult {
        Fixture.kernelRequired = true; Fixture.carrierPartition = "init_boot"
        Fixture.kernelSnapshot = Fixture.kernelSnapshot.copy(partitions = mapOf("boot" to "boot-sha", "init_boot" to "init-sha"))
        Fixture.component = dev.maplesu.image.UltraInitBootProducer.Artifact()
        return com.maplesu.core.ui.util.FlashResult(1, "ULTRA_INIT_KMI_COMPONENT_MISSING", false,
            com.maplesu.core.ui.util.FlashOutcome.FAILED).also { Fixture.kernelOutcomes += it }
    }
    @Test fun cancelledInitBootComponentFailureRetriesTheSameCarrierInANewTransaction() {
        initBootComponentRetry()
        assertEquals(UltraEnvironmentUpgrade.Result.SUBMITTED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(listOf("init_boot", "init_boot"), Fixture.kernelAttempts.map { it.first })
        assertNotEquals(Fixture.kernelAttempts[0].second, Fixture.kernelAttempts[1].second)
        assertEquals(Fixture.kernelAttempts[1].second, Fixture.rebootTask)
        assertTrue(Fixture.calls.contains("paired-boot")); assertTrue(Fixture.calls.contains("component"))
        assertFalse(Fixture.pairedCaptured!!.exists())
        assertEquals("boot-1", marker())
    }
    @Test fun absentCarrierRetainsTheOriginalComponentFailure() {
        initBootComponentRetry(); Fixture.kernelSnapshot = Fixture.kernelSnapshot.copy(partitions = mapOf("boot" to "boot-sha"))
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertTrue(UltraEnvironmentUpgrade.lastFailureReason!!.contains("ULTRA_INIT_KMI_COMPONENT_MISSING"))
        assertEquals(listOf("init_boot"), Fixture.kernelAttempts.map { it.first }); assertNull(marker())
        assertNull(Fixture.pairedCaptured)
    }
    @Test fun absentComponentDoesNotInventASuccessOrReplayTheCarrier() {
        initBootComponentRetry(); Fixture.component = null
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(listOf("init_boot"), Fixture.kernelAttempts.map { it.first }); assertNull(marker())
        assertFalse(Fixture.pairedCaptured!!.exists())
    }
    @Test fun unconfirmedCancellationPreservesTheMarkerAndNeverStartsFallback() {
        initBootComponentRetry(); Fixture.kernelTaskState = "WRITING"
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(listOf("init_boot"), Fixture.kernelAttempts.map { it.first }); assertEquals("boot-1", marker())
    }
    @Test fun uncertainCommitIsNeverReplayedEvenWithAComponentDiagnostic() {
        val original = initBootComponentRetry(); Fixture.kernelOutcomes.clear()
        Fixture.kernelOutcomes += original.copy(outcome = com.maplesu.core.ui.util.FlashOutcome.RECOVERY_REQUIRED)
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(listOf("init_boot"), Fixture.kernelAttempts.map { it.first }); assertEquals("boot-1", marker())
        assertFalse(Fixture.calls.contains("kernel-status"))
    }
    @Test fun unreadableCancellationReceiptRetainsTheMarkerAndDoesNotFallback() {
        initBootComponentRetry()
        Fixture.onKernelStatus = { error("INSTALLER_SERVICE_DISCONNECTED") }
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(1, Fixture.kernelAttempts.size); assertEquals("boot-1", marker())
        assertTrue(UltraEnvironmentUpgrade.lastFailureReason!!.contains("INSTALLER_SERVICE_DISCONNECTED"))
    }
    @Test fun aSwitchAfterCancelledComponentFailureStopsBeforeRetryPreparation() {
        initBootComponentRetry()
        Fixture.onKernelStatus = { ModeRuntime.activation = ModeActivation.Active(WorkMode.LITE) }
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals("KERNEL_PREFLIGHT_ACTIVATION_CHANGED", UltraEnvironmentUpgrade.lastFailureReason)
        assertEquals(1, Fixture.kernelAttempts.size); assertNull(marker())
    }
    @Test fun cancelledFallbackKeepsBothFailuresAndAllowsALaterExplicitAttempt() {
        initBootComponentRetry()
        Fixture.kernelOutcomes += com.maplesu.core.ui.util.FlashResult(1, "ULTRA_INIT_RAMDISK_ERROR", false,
            com.maplesu.core.ui.util.FlashOutcome.FAILED)
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertTrue(UltraEnvironmentUpgrade.lastFailureReason!!.contains("ULTRA_INIT_KMI_COMPONENT_MISSING"))
        assertTrue(UltraEnvironmentUpgrade.lastFailureReason!!.contains("ULTRA_INIT_RAMDISK_ERROR"))
        assertEquals(2, Fixture.kernelAttempts.size); assertNull(marker())
    }
    @Test fun changedPairedSourceStopsBeforeFallbackPreparation() {
        initBootComponentRetry()
        Fixture.onKernelStatus = { Fixture.kernelSnapshot = Fixture.kernelSnapshot.copy(firmware = "new-firmware") }
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertTrue(UltraEnvironmentUpgrade.lastFailureReason!!.contains("INSTALLER_DEVICE_CHANGED"))
        assertEquals(1, Fixture.kernelAttempts.size); assertNull(marker())
    }
    @Test fun sourceReadFailureNeverTriggersAnUnrelatedComponentFallback() {
        initBootComponentRetry(); Fixture.kernelOutcomes.clear()
        Fixture.kernelOutcomes += com.maplesu.core.ui.util.FlashResult(1, "INSTALLER_SOURCE_READ_FAILED", false,
            com.maplesu.core.ui.util.FlashOutcome.FAILED)
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(1, Fixture.kernelAttempts.size); assertFalse(Fixture.calls.contains("kernel-status"))
    }
    @Test fun finalTaskIsReportedBeforeTheFallbackCanPrepare() {
        initBootComponentRetry()
        var selected: String? = null
        Fixture.onKernelInstall = {
            if (Fixture.kernelAttempts.size == 2) assertEquals(Fixture.kernelAttempts.last().second, selected)
        }
        val result = com.maplesu.core.ui.util.installUltraKernelUpdate("fixture-primary",
            onTaskSelected = { selected = it }, verifyScope = {})
        assertEquals(0, result.code); assertEquals(2, Fixture.kernelAttempts.size)
        assertNotNull(selected); assertFalse(Fixture.pairedCaptured!!.exists())
    }
    @Test fun uncertainFallbackRetainsItsMarkerAndNeverRunsAThirdAttempt() {
        initBootComponentRetry()
        Fixture.kernelOutcomes += com.maplesu.core.ui.util.FlashResult(1, "INSTALLER_REPLY_UNKNOWN", false,
            com.maplesu.core.ui.util.FlashOutcome.RECOVERY_REQUIRED)
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals("boot-1", marker()); assertEquals(2, Fixture.kernelAttempts.size)
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(2, Fixture.kernelAttempts.size); assertFalse(Fixture.pairedCaptured!!.exists())
    }
    @Test fun modernBootGenerationFailureDoesNotChangeCarrierEvenWhenAComponentExists() {
        Fixture.kernelRequired = true; Fixture.carrierPartition = "boot"
        Fixture.kernelSnapshot = Fixture.kernelSnapshot.copy(partitions = mapOf("boot" to "boot-sha", "init_boot" to "init-sha"))
        Fixture.component = dev.maplesu.image.UltraInitBootProducer.Artifact()
        for (code in listOf("ULTRA_KERNEL_GENERATE_CAVE_UNAVAILABLE", "ULTRA_KERNEL_GENERATE_CAVE_TOO_SMALL",
            "ULTRA_KERNEL_GENERATE_SITE_COVERAGE", "ULTRA_KERNEL_GENERATE_STRUCT_OFFSET",
            "ULTRA_KERNEL_MANAGEMENT_ABI_CAP_LAST_SOURCE")) {
            Fixture.kernelAttempts.clear(); Fixture.calls.clear()
            Fixture.kernelOutcomes += com.maplesu.core.ui.util.FlashResult(1, code, false,
                com.maplesu.core.ui.util.FlashOutcome.FAILED)
            val result = com.maplesu.core.ui.util.installUltraKernelUpdate("modern-$code", verifyScope = {})
            assertEquals(1, result.code); assertEquals(code, result.err)
            assertEquals(listOf("boot"), Fixture.kernelAttempts.map { it.first })
            assertFalse(Fixture.calls.contains("kernel-status"))
            assertFalse(Fixture.calls.contains("paired-boot")); assertFalse(Fixture.calls.contains("component"))
        }
    }

    @Test fun markerCommitFailurePreventsInstaller() {
        upgradePrefs.commits = false
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertFalse(Fixture.calls.contains("install"))
        assertEquals("PREFLIGHT_MARKER_COMMIT_FAILED", UltraEnvironmentUpgrade.lastFailureReason)
    }
    @Test fun failedInstallPreservesErrorAndClearsDefiniteNonSubmission() {
        Fixture.outcome = UltraEnvironmentInstaller.Outcome(false, "ERR_HELPER_WRITE", sdkErrorCode = -42, reason = "write-stage", detail = "errno=5")
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals("SDK:ERR_HELPER_WRITE:write-stage code=-42 errno=5", UltraEnvironmentUpgrade.lastFailureReason)
        assertNull(marker())
    }
    @Test fun uncertainSubmissionRetainsMarker() {
        Fixture.outcome = UltraEnvironmentInstaller.Outcome(false, "ERR_TRANSPORT", submissionUnknown = true)
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals("boot-1", marker())
    }
    @Test fun successfulInstallWithoutReadbackStillFailsAndRetainsMarker() {
        Fixture.outcome = UltraEnvironmentInstaller.Outcome(true, "OK", verified = false)
        assertEquals(UltraEnvironmentUpgrade.Result.FAILED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals("boot-1", marker()); assertEquals("UNVERIFIED:UNKNOWN", UltraEnvironmentUpgrade.lastFailureReason)
    }
    @Test fun oneSubmissionPerBootRemainsEnforced() {
        assertEquals(UltraEnvironmentUpgrade.Result.SUBMITTED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(UltraEnvironmentUpgrade.Result.REFUSED, UltraEnvironmentUpgrade.upgrade(context))
        assertEquals(1, Fixture.calls.count { it == "install" })
    }
}
