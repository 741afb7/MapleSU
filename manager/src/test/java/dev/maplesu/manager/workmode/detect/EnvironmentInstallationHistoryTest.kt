package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.WorkMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EnvironmentInstallationHistoryTest {
    private fun detector(store: EnvironmentStateStore, ksu: KsuFacts = KsuFacts()) = DefaultModeDetector(
        ksuProbe = KsuKernelProbe { ksu },
        bootIdSource = BootIdSource { "current-boot" },
        localFactsProvider = store::localInstallationFacts,
        rootEvidenceReader = object : RootEvidenceReader {
            override fun open(channel: RootChannelKind): RootEvidenceSession? =
                error("Installation history does not grant a Root channel")
        },
    )

    @Test fun ultraHistoryRemainsAnUnboundRepairStateWhenTheRuntimeIsNotReady() = runBlocking {
        for (boot in listOf("current-boot", "older-boot")) {
            val previous = EnvironmentSnapshot(WorkMode.LITE, true, "ultra:running", boot, 1L, dirty = true)
            val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
            val result = detector(store).detect(DetectionRequest(DetectionOrigin.AFTER_ROOT_GRANTED, true, true))
            assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, result.state)
            assertEquals(DetectionRules.Rule.R9_A_DISK_WEAK_UNBOUND, result.rule)
            assertNull(result.state.activeMode)
            assertNull(result.rootChannel)
            assertEquals(WorkMode.LITE, result.recovery.restorableMode)
            assertFalse(result.recovery.controlPlaneReachable)
            assertSame(previous, store.snapshot())
        }
    }

    @Test fun unknownAbsentOrNormalHistoryDoesNotCreateAnUltraInstallation() = runBlocking {
        for (previous in listOf(null,
            EnvironmentSnapshot(null, false, null, "older-boot", 1L),
            EnvironmentSnapshot(WorkMode.KSU, true, null, "older-boot", 1L))) {
            val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
            val result = detector(store).detect(DetectionRequest(DetectionOrigin.AFTER_ROOT_GRANTED, true, true))
            assertEquals(ModeState.INDETERMINATE, result.state)
            assertNull(result.state.activeMode)
            assertNull(result.rootChannel)
            assertNull(result.recovery.restorableMode)
        }
    }

    @Test fun currentNormalOwnershipWinsOverUltraHistory() = runBlocking {
        val previous = EnvironmentSnapshot(WorkMode.LITE, true, "ultra:running", "older-boot", 1L)
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
        val result = detector(store, KsuFacts(true, 32650, 7, true))
            .detect(DetectionRequest(DetectionOrigin.STARTUP, false, false))
        assertEquals(ModeState.MODE_B_KERNEL_ONLY, result.state)
        assertEquals(WorkMode.KSU, result.state.activeMode)
    }

    @Test fun aForeignKernelWinsOverUltraHistory() = runBlocking {
        val previous = EnvironmentSnapshot(WorkMode.LITE, true, "ultra:running", "older-boot", 1L)
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
        val result = detector(store, KsuFacts(true, 32650, 8, false))
            .detect(DetectionRequest(DetectionOrigin.STARTUP, false, false))
        assertEquals(ModeState.FOREIGN_SU, result.state)
        assertNull(result.state.activeMode)
        assertNull(result.rootChannel)
    }

    @Test fun aDefinitiveAbsenceReplacesTheWeakInstallationHistory() {
        val previous = EnvironmentSnapshot(WorkMode.LITE, true, "ultra:running", "older-boot", 1L)
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
        val probes = object : EnvironmentProbes {
            override fun suPresent() = false
            override fun ksuNormal() = false
            override fun ultraFingerprint(): String? = null
        }
        val outcome = EnvironmentResolver(probes, store).resolve("current-boot")
        assertEquals(EnvironmentVerdict.NOT_INSTALLED, outcome.verdict)
        assertNull(store.localInstallationFacts("current-boot").lastKnownMode)
        assertNull(store.localInstallationFacts("current-boot").lastKnownBootId)
    }

    @Test fun unreadableHistoryStaysUnknownAndDoesNotWrite() = runBlocking {
        val store = EnvironmentStateStore(object : EnvironmentStatePersistence {
            override fun read(): EnvironmentSnapshot? = error("Read unavailable")
            override fun write(snapshot: EnvironmentSnapshot) = error("History reads cannot write")
            override fun clear() = error("History reads cannot clear")
        })
        val result = detector(store).detect(DetectionRequest(DetectionOrigin.STARTUP, false, false))
        assertEquals(ModeState.INDETERMINATE, result.state)
        assertNull(result.state.activeMode)
        assertNull(result.recovery.restorableMode)
    }
}
