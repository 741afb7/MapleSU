package dev.maplesu.manager.workmode.detect

import android.os.Process
import dev.maplesu.manager.runtime.ultra.NativeReceipt
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.parseManagerOwnerIdentity
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.detect.probes.UltraEnvironmentAdapter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EnvironmentCurrentOwnerInstallationTest {
    private val boot = "current-boot"
    private val owner = UltraRuntimeClient.ManagerOwnerIdentity(Process.myUid(), 7, "ab".repeat(16))
    private val detail = "operation=probe_manager_owner_root;sdkError=namespace_open;sdkErrorCode=-1"

    private fun observation(state: UltraEnvironmentProbe.State) = UltraEnvironmentProbe.scopeOwnerSnapshot(
        UltraEnvironmentProbe.Snapshot(state, detail = detail, managerOwnerIdentity = owner),
        owner, boot, boot, suspended = false,
    )

    private fun fingerprint(observed: UltraEnvironmentProbe.Snapshot) = UltraEnvironmentProbe.recoveryFingerprint(
        observed.state, safeMode = false, observed.kernelRootVerified, observed.kernelChannelAbsent,
        observed.detail, managerOwnerBound = observed.managerOwnerIdentity != null,
    )

    private fun resolve(observed: UltraEnvironmentProbe.Snapshot, current: Boolean = true,
                        normal: Boolean = false): Pair<EnvironmentCheckOutcome, EnvironmentStateStore> {
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence())
        val probes = object : EnvironmentProbes {
            override fun ksuNormal() = normal
            override fun currentUltraFingerprint(): String? = if (normal) error("Normal is decisive")
                else if (current) runCatching { fingerprint(observed) }.getOrNull() else null
            override fun currentUltraIdentity() = UltraIdentityObservation(currentUltraFingerprint(),
                observed.managerOwnerIdentity.takeIf { current })
            override fun suReadiness(): SuReadiness {
                check(!current || observed.managerOwnerIdentity == null) { "Owner must resolve before ordinary SU" }
                return SuReadiness.UNKNOWN
            }
            override fun suPresent(): Boolean = error("Use the tri-state port")
            override fun ultraFingerprint() = fingerprint(observed)
            override fun ultraIdentity() = UltraIdentityObservation(ultraFingerprint(), observed.managerOwnerIdentity)
        }
        return EnvironmentResolver(probes, store).resolve(boot) to store
    }

    @Test fun currentOwnerWithUnknownRuntimeIsInstalledWithoutHistoryOrRoot() {
        val observed = observation(UltraEnvironmentProbe.State.Unknown)
        val (outcome, store) = resolve(observed)
        assertEquals(EnvironmentVerdict.ULTRA, outcome.verdict)
        assertEquals(listOf("ksu", "ultraCurrent"), outcome.probes)
        assertEquals(WorkMode.LITE, store.snapshot()?.mode)
        assertEquals("ultra:owner", store.snapshot()?.ultraEnvFingerprint)
        assertFalse(store.snapshot()!!.suAvailable)
        assertEquals(UltraEnvironmentProbe.State.Unknown, observed.state)
        assertFalse(observed.rootAvailable)
        assertFalse(observed.sdkChannelAvailable)
        assertEquals(owner, outcome.ultraOwnerIdentity)
        val projected = EnvironmentDetectionProjection.override(outcome)
        assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, projected?.state)
        assertNull(projected?.state?.activeMode)
        assertNull(projected?.rootChannel)
    }

    @Test fun absentUserspaceFilesDoNotEraseTheConfirmedCurrentOwnerInstallation() {
        val observed = observation(UltraEnvironmentProbe.State.NotInstalled)
        val (outcome, store) = resolve(observed)
        assertEquals(EnvironmentVerdict.ULTRA, outcome.verdict)
        assertEquals("ultra:owner", store.snapshot()?.ultraEnvFingerprint)
        assertFalse(store.snapshot()!!.suAvailable)
        assertFalse(observed.rootAvailable)
        assertEquals(UltraEnvironmentProbe.State.NotInstalled, observed.state)
    }

    @Test fun recoveryRetainsTheOwnerWhenTheRootProbeHasNotSucceeded() {
        for (state in listOf(UltraEnvironmentProbe.State.Unknown, UltraEnvironmentProbe.State.NotInstalled)) {
            val (outcome, store) = resolve(observation(state), current = false)
            assertEquals(EnvironmentVerdict.ULTRA, outcome.verdict)
            assertEquals("ultra:owner", store.snapshot()?.ultraEnvFingerprint)
            assertFalse(store.snapshot()!!.suAvailable)
            assertEquals(owner, outcome.ultraOwnerIdentity)
            assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, EnvironmentDetectionProjection.override(outcome)?.state)
        }
    }

    @Test fun missingOwnerAndErrorTextDoNotBecomeAnInstallationFact() {
        for (failure in listOf(detail, "MAPLESU_SDK_BUSY", "ERR_MODULE_NO_BOOT", "ownerBound=true")) {
            val observed = UltraEnvironmentProbe.Snapshot(detail = failure, sdkChannelAvailable = true)
            val (outcome, store) = resolve(observed)
            assertEquals(EnvironmentVerdict.UNKNOWN, outcome.verdict)
            assertNull(store.snapshot())
            assertFalse(observed.rootAvailable)
        }
    }

    @Test fun anExplicitUserspaceAbsenceWithoutOwnerRemainsNegative() {
        val (outcome, store) = resolve(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.NotInstalled))
        assertEquals(EnvironmentVerdict.NOT_INSTALLED, outcome.verdict)
        assertNull(store.snapshot()?.mode)
    }

    @Test fun staleBootUidEpochHostSuspensionAndHookDiscardTheOwnerFact() {
        val original = observation(UltraEnvironmentProbe.State.Unknown)
        val invalid = listOf(
            UltraEnvironmentProbe.scopeOwnerSnapshot(original, owner, "new-boot", boot, false),
            UltraEnvironmentProbe.scopeOwnerSnapshot(original, owner, null, boot, false),
            UltraEnvironmentProbe.scopeOwnerSnapshot(original, owner.copy(fullUid = owner.fullUid + 100000), boot, boot, false),
            UltraEnvironmentProbe.scopeOwnerSnapshot(original, owner.copy(epoch = 8), boot, boot, false),
            UltraEnvironmentProbe.scopeOwnerSnapshot(original, owner.copy(hostInstance = "cd".repeat(16)), boot, boot, false),
            UltraEnvironmentProbe.scopeOwnerSnapshot(original, owner, boot, boot, true),
            UltraEnvironmentProbe.scopeOwnerSnapshot(original, owner, boot, boot, false, hookActive = true),
        )
        for (observed in invalid) {
            assertNull(observed.managerOwnerIdentity)
            assertFalse(observed.rootAvailable)
            val (outcome, store) = resolve(observed)
            assertEquals(EnvironmentVerdict.UNKNOWN, outcome.verdict)
            assertNull(store.snapshot())
        }
    }

    @Test fun completeDetectorConsumesTheCurrentOwnerWithoutUsingCachedRoot() = runBlocking {
        for (state in listOf(UltraEnvironmentProbe.State.Unknown, UltraEnvironmentProbe.State.NotInstalled)) {
            var ownerReads = 0
            var healthReads = 0
            val adapter = UltraEnvironmentAdapter(
                readSnapshot = { error("Retained display state is not current identity") },
                refreshSnapshot = { healthReads++; observation(state) }, hookActive = { false },
                readManagerOwnerIdentity = { ownerReads++; observation(UltraEnvironmentProbe.State.Unknown) },
            )
            val detector = DefaultModeDetector(ultraPort = adapter, bootIdSource = BootIdSource { boot },
                rootEvidenceReader = object : RootEvidenceReader {
                    override fun open(channel: RootChannelKind): RootEvidenceSession? = error("Owner is not Root")
                })
            val result = detector.detect(DetectionRequest(DetectionOrigin.AFTER_ROOT_GRANTED, true, true))
            assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, result.state)
            assertEquals(DetectionRules.Rule.R6_A_ULTRA_OWNER_ONLY, result.rule)
            assertNull(result.state.activeMode)
            assertNull(result.rootChannel)
            assertEquals(1, ownerReads)
            assertEquals(1, healthReads)
        }
    }

    @Test fun aConfirmedNormalOwnerStillWinsTheQuickCheck() {
        val (outcome, store) = resolve(observation(UltraEnvironmentProbe.State.Unknown), normal = true)
        assertEquals(EnvironmentVerdict.KSU_NORMAL, outcome.verdict)
        assertEquals(listOf("ksu"), outcome.probes)
        assertEquals(WorkMode.KSU, store.snapshot()?.mode)
        assertFalse(store.snapshot()!!.suAvailable)
    }

    @Test fun foreignKernelStillWinsTheCompleteDetector() = runBlocking {
        val adapter = UltraEnvironmentAdapter(
            readSnapshot = { observation(UltraEnvironmentProbe.State.Unknown) },
            refreshSnapshot = { error("Foreign kernel must not refresh Ultra") }, hookActive = { false },
            readManagerOwnerIdentity = { error("Foreign kernel must not query Ultra") },
        )
        val result = DefaultModeDetector(ksuProbe = KsuKernelProbe { KsuFacts(true, 100, 8, false) },
            ultraPort = adapter, bootIdSource = BootIdSource { boot })
            .detect(DetectionRequest(DetectionOrigin.STARTUP, false, false))
        assertEquals(ModeState.FOREIGN_SU, result.state)
        assertNull(result.state.activeMode)
        assertNull(result.rootChannel)
    }

    @Test fun anOkReceiptWithoutAValidOwnerCannotProveAnInstallation() {
        val receipt = requireNotNull(NativeReceipt.parse("""{"schemaVersion":1,"backend":"maplesu_ultra","bridgeVersion":1,"operation":"query_manager_owner_identity","ok":true,"sdkError":"OK","sdkErrorCode":0,"data":{"ownerBound":true}}"""))
        val parsed = parseManagerOwnerIdentity(receipt)
        assertFalse(parsed.succeeded)
        assertNull(parsed.identity)
        assertEquals(EnvironmentVerdict.UNKNOWN, resolve(UltraEnvironmentProbe.Snapshot(detail = "identity=invalid_reply")).first.verdict)
    }

    @Test fun freshProductionIdentityPathsReturnTheCurrentOwnerWithoutReadingHealthOrCache() {
        val path = "src/main/java/dev/maplesu/manager/workmode/detect/probes/EnvironmentProbesAdapter.kt"
        val source = listOf(File(path), File("manager/$path")).first { it.isFile }.readText()
        val current = source.substringAfter("private fun computeCurrentUltraIdentity()").substringBefore("override fun ultraFingerprint()")
        val fresh = source.substringAfter("private fun freshUltraIdentity()").substringBefore("override fun ksuNormal()")
        assertTrue(current.contains("UltraEnvironmentProbe.observeFreshIdentity(context.applicationContext)"))
        assertTrue(fresh.contains("UltraEnvironmentProbe.observeFreshIdentity(app)"))
        assertTrue(fresh.contains("observed.managerOwnerIdentity"))
        assertFalse(fresh.contains("UltraEnvironmentProbe.observeFresh(app)"))
        assertFalse(fresh.contains("currentSnapshot"))
    }

    @Test fun startupReadsCurrentOwnerWithoutHealthRootOrRetainedSnapshot() {
        var ownerReads = 0
        val adapter = UltraEnvironmentAdapter(
            readSnapshot = { error("A retained owner or Root proof is not this call's identity") },
            refreshSnapshot = { error("Startup identity does not run health or Root") },
            hookActive = { false },
            readManagerOwnerIdentity = { ownerReads++; observation(UltraEnvironmentProbe.State.Unknown) },
        )
        val observed = adapter.observe(allowRefresh = false, allowOwnerQuery = true)
        assertTrue(observed.managerOwnerBound)
        assertFalse(observed.kernelRootVerified)
        assertEquals(UltraState.UNKNOWN, observed.state)
        assertEquals(1, ownerReads)
    }

    @Test fun aBusyCurrentOwnerDoesNotAdoptTheRetainedOwnerOrRoot() {
        val adapter = UltraEnvironmentAdapter(
            readSnapshot = { UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running,
                kernelRootVerified = true, managerOwnerIdentity = owner) },
            refreshSnapshot = { UltraEnvironmentProbe.Snapshot(detail = "MAPLESU_SDK_BUSY") },
            hookActive = { false },
            readManagerOwnerIdentity = { UltraEnvironmentProbe.Snapshot(detail = "MAPLESU_SDK_BUSY") },
        )
        val observed = adapter.observe(allowRefresh = true, allowOwnerQuery = true)
        assertFalse(observed.managerOwnerBound)
        assertFalse(observed.kernelRootVerified)
        assertEquals(UltraState.UNKNOWN, observed.state)
    }

    @Test fun failedHealthRetainsThisReadOwnerWithoutPromotingRunningToReady() {
        val adapter = UltraEnvironmentAdapter(
            readSnapshot = { error("Health must use its direct return") },
            refreshSnapshot = { UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running,
                managerOwnerIdentity = owner, rootProbeDetail = "sdkErrorCode=-19914;phase=11;childStatus=256") },
            hookActive = { false },
            readManagerOwnerIdentity = { observation(UltraEnvironmentProbe.State.Unknown) },
        )
        val observed = adapter.observe(allowRefresh = true, allowOwnerQuery = true)
        assertTrue(observed.managerOwnerBound)
        assertFalse(observed.kernelRootVerified)
        assertEquals(UltraState.UNKNOWN, observed.state)
    }

    @Test fun anotherHealthOwnerRevokesTheEarlierOwnersRootProof() {
        for (another in listOf(owner.copy(fullUid = owner.fullUid + 100000), owner.copy(epoch = 8),
                owner.copy(hostInstance = "cd".repeat(16)))) {
            val adapter = UltraEnvironmentAdapter(
                readSnapshot = { error("Retained snapshot is irrelevant") },
                refreshSnapshot = { UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running,
                    kernelRootVerified = true, managerOwnerIdentity = another) },
                hookActive = { false },
                readManagerOwnerIdentity = { observation(UltraEnvironmentProbe.State.Unknown) },
            )
            val observed = adapter.observe(allowRefresh = true, allowOwnerQuery = true)
            assertFalse(observed.kernelRootVerified)
            assertEquals(UltraState.UNKNOWN, observed.state)
        }
    }

    @Test fun theMatchingCurrentHealthProofCanStillEnableUltra() {
        val adapter = UltraEnvironmentAdapter(
            readSnapshot = { error("Retained snapshot is irrelevant") },
            refreshSnapshot = { UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running,
                kernelRootVerified = true, managerOwnerIdentity = owner) },
            hookActive = { false },
            readManagerOwnerIdentity = { observation(UltraEnvironmentProbe.State.Unknown) },
        )
        val observed = adapter.observe(allowRefresh = true, allowOwnerQuery = true)
        assertTrue(observed.kernelRootVerified)
        assertEquals(UltraState.RUNNING, observed.state)
    }
}
