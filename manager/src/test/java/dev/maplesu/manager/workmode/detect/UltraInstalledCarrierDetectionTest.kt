package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.detect.probes.UltraEnvironmentAdapter
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UltraInstalledCarrierDetectionTest {
    private fun detector(installed: Boolean, state: UltraState = UltraState.UNKNOWN,
                         ksu: KsuFacts = KsuFacts()) = DefaultModeDetector(
        ksuProbe = KsuKernelProbe { ksu },
        ultraPort = object : UltraEnvironmentPort {
            override fun state() = state
            override fun observe(allowRefresh: Boolean) =
                UltraEnvironmentObservation(state, installedCarrierVerified = installed)
        },
        rootEvidenceReader = object : RootEvidenceReader {
            override fun open(channel: RootChannelKind): RootEvidenceSession? =
                error("Installed carrier bytes do not grant a Root channel")
        },
        bootIdSource = BootIdSource { "boot" },
    )

    @Test fun oldCarrierWithUnknownOwnerRemainsInstalledWithoutActivatingAChannel() = runBlocking {
        val result = detector(true).detect(DetectionRequest(DetectionOrigin.AFTER_ROOT_GRANTED, true, true))
        assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, result.state)
        assertEquals(DetectionRules.Rule.R8_A_DISK_STRONG_UNBOUND, result.rule)
        assertNull(result.state.activeMode)
        assertNull(result.rootChannel)
        assertNull(result.trace.single { it.id == "L3" }.ok)
    }

    @Test fun startupCanReadAnAlreadyVerifiedCarrierWithoutOpeningRoot() = runBlocking {
        val result = detector(true).detect(DetectionRequest(DetectionOrigin.STARTUP, false, false))
        assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, result.state)
        assertNull(result.rootChannel)
    }

    @Test fun noCarrierAndUnknownOwnerStayUnknown() = runBlocking {
        val result = detector(false).detect(DetectionRequest(DetectionOrigin.AFTER_ROOT_GRANTED, true, true))
        assertEquals(ModeState.INDETERMINATE, result.state)
        assertNull(result.state.activeMode)
    }

    @Test fun currentKsuIdentityWinsOverRetainedCarrierBytes() = runBlocking {
        val result = detector(true, ksu = KsuFacts(true, 100, 7, true))
            .detect(DetectionRequest(DetectionOrigin.STARTUP, false, false))
        assertEquals(ModeState.MODE_B_KERNEL_ONLY, result.state)
    }

    @Test fun foreignKsuIsNotAdoptedAsAnInstalledUltraOwner() = runBlocking {
        val result = detector(true, ksu = KsuFacts(true, 100, 8, false))
            .detect(DetectionRequest(DetectionOrigin.STARTUP, false, false))
        assertEquals(ModeState.FOREIGN_SU, result.state)
    }

    @Test fun adapterReadsOnlyTheScopedCacheDuringStartup() {
        var cacheReads = 0
        val adapter = UltraEnvironmentAdapter(
            readSnapshot = { UltraEnvironmentProbe.Snapshot() },
            refreshSnapshot = { error("Startup cannot submit a Root probe") },
            hookActive = { false },
            readManagerOwnerBound = { error("Owner query was not requested") },
            readInstalledCarrier = { cacheReads++; true },
        )
        val result = adapter.observe(allowRefresh = false, allowOwnerQuery = false)
        assertEquals(UltraState.UNKNOWN, result.state)
        assertEquals(true, result.installedCarrierVerified)
        assertFalse(result.kernelRootVerified)
        assertFalse(result.managerOwnerBound)
        assertEquals(1, cacheReads)
    }

    @Test fun hookSupersedesTheRetainedSdkCarrierObservation() {
        val adapter = UltraEnvironmentAdapter(
            readSnapshot = { error("Hook must short-circuit SDK cache") },
            refreshSnapshot = { error("Hook must not refresh SDK") },
            hookActive = { true },
            readInstalledCarrier = { error("Hook must not inherit a competing carrier") },
        )
        assertFalse(adapter.observe(false, false).installedCarrierVerified)
    }
}
