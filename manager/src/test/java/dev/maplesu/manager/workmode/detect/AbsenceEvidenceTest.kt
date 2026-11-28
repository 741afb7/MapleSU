package dev.maplesu.manager.workmode.detect

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsenceEvidenceTest {
    @Test
    fun managerInstallAgeDoesNotProveAbsence() {
        for (ageMs in listOf(0L, 300_000L, 300_001L)) {
            val decision = DetectionRules.decide(
                DetectionFacts(local = LocalFacts(bootId = "absence-boot", managerFirstInstallAgeMs = ageMs)),
            )
            assertEquals("ageMs=$ageMs", ModeState.INDETERMINATE, decision.state)
            assertEquals("ageMs=$ageMs", DetectionRules.Rule.R12_INDETERMINATE, decision.rule)
            assertNull(decision.defaultSelectedMode)
        }
    }

    @Test
    fun failedProbeReadsStayIndeterminateForFreshManager() = runBlocking {
        var ksuCalls = 0
        var ultraCalls = 0
        var rootCalls = 0
        var handshakeCalls = 0
        var leaseCalls = 0
        val result = DefaultModeDetector(
            ksuProbe = KsuKernelProbe { ksuCalls++; error("KSU_READ_FAILED") },
            liteHandshakeProbe = LiteHandshakeProbe { handshakeCalls++; error("UNEXPECTED_HANDSHAKE") },
            ultraPort = UltraEnvironmentPort { ultraCalls++; error("ULTRA_READ_FAILED") },
            jailbreakLease = JailbreakLeaseProbe { leaseCalls++; error("UNEXPECTED_LEASE") },
            bootIdSource = BootIdSource { "absence-boot" },
            clock = Clock { 1_000L },
            rootEvidenceReader = object : RootEvidenceReader {
                override fun open(channel: RootChannelKind): RootEvidenceSession? {
                    rootCalls++
                    error("UNEXPECTED_ROOT")
                }
            },
            localFactsProvider = { LocalFacts(bootId = it, managerFirstInstallAgeMs = 0) },
        ).detect(
            DetectionRequest(
                DetectionOrigin.STARTUP,
                allowRootProbe = false,
                allowLiteExecutor = false,
                allowJailbreakProbe = false,
            ),
        )
        assertEquals(1, ksuCalls)
        assertEquals(1, ultraCalls)
        assertNull(result.trace.single { it.id == "S1" }.ok)
        assertTrue(result.trace.single { it.id == "S1" }.detail.contains("ksu=UNKNOWN"))
        assertTrue(result.trace.single { it.id == "L3" }.detail.contains("ultra=UNKNOWN"))
        assertEquals(ModeState.INDETERMINATE, result.state)
        assertEquals(DetectionRules.Rule.R12_INDETERMINATE, result.rule)
        assertEquals(0, rootCalls)
        assertEquals(0, handshakeCalls)
        assertEquals(0, leaseCalls)
    }
}
