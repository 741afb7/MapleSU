package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test

class EnvironmentBootUnconfirmedRecoveryTest {
    private val boot = "87883db3-abfe-41ce-8d78-32441f2725cc"
    private val detail = "operation=get_maplesu_environment_state;sdkError=ERR_MODULE_NO_BOOT;sdkErrorCode=-19998"

    @Test fun missingBootChannelDoesNotEraseAnInstalledHistoryOrClaimCurrentAbsence() {
        for (previousMode in listOf(null, WorkMode.LITE, WorkMode.KSU)) {
            val previous = previousMode?.let { EnvironmentSnapshot(it, false, "ultra:running", "older-boot", 1L) }
            val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
            val calls = mutableListOf<String>()
            val probes = object : EnvironmentProbes {
                override fun currentUltraFingerprint(): String? { calls += "current"; return null }
                override fun suReadiness(): SuReadiness { calls += "su"; return SuReadiness.UNKNOWN }
                override fun suPresent(): Boolean = error("tri-state probe required")
                override fun ksuNormal(): Boolean { calls += "normal"; return false }
                override fun ultraFingerprint(): String? {
                    calls += "recovery"
                    return UltraEnvironmentProbe.recoveryFingerprint(UltraEnvironmentProbe.State.Unknown,
                        safeMode = false, kernelChannelAbsent = true, detail = detail)
                }
            }
            val outcome = EnvironmentResolver(probes, store).resolve(boot)
            assertEquals("previous=$previousMode calls=$calls", EnvironmentVerdict.UNKNOWN, outcome.verdict)
            assertFalse(outcome.terminal)
            assertSame(previous, store.snapshot())
            assertSame(previous, outcome.snapshot)
            assertTrue(outcome.detail.contains("ERR_MODULE_NO_BOOT"))
            assertEquals(1, calls.count { it == "recovery" })
        }
    }

    @Test fun anExplicitNotInstalledStateRemainsAnIndependentNegativeObservation() {
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence())
        val probes = object : EnvironmentProbes {
            override fun ksuNormal() = false
            override fun suPresent() = false
            override fun ultraFingerprint() = UltraEnvironmentProbe.recoveryFingerprint(
                UltraEnvironmentProbe.State.NotInstalled, safeMode = false, detail = "state=NotInstalled")
        }
        val outcome = EnvironmentResolver(probes, store).resolve(boot)
        assertEquals(EnvironmentVerdict.NOT_INSTALLED, outcome.verdict)
        assertTrue(outcome.terminal)
        assertNull(store.snapshot()?.mode)
        assertEquals(boot, store.snapshot()?.bootId)
    }

    @Test fun aLaterCurrentOwnerObservationResolvesWithoutReusingAnUnconfirmedVerdict() {
        var currentFingerprint: String? = null
        var recoveries = 0
        val previous = EnvironmentSnapshot(WorkMode.LITE, false, "ultra:running", "older-boot", 1L)
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
        val probes = object : EnvironmentProbes {
            override fun ksuNormal() = false
            override fun currentUltraFingerprint() = currentFingerprint
            override fun suReadiness() = SuReadiness.UNKNOWN
            override fun suPresent() = error("tri-state probe required")
            override fun ultraFingerprint(): String? {
                recoveries++
                return UltraEnvironmentProbe.recoveryFingerprint(UltraEnvironmentProbe.State.Unknown,
                    safeMode = false, kernelChannelAbsent = true, detail = detail)
            }
        }
        val resolver = EnvironmentResolver(probes, store)
        assertEquals(EnvironmentVerdict.UNKNOWN, resolver.resolve(boot).verdict)
        assertSame(previous, store.snapshot())
        currentFingerprint = "ultra:running"
        val confirmed = resolver.resolve(boot)
        assertEquals(EnvironmentVerdict.ULTRA, confirmed.verdict)
        assertEquals(1, recoveries)
        assertEquals(boot, store.snapshot()?.bootId)
        assertEquals(WorkMode.LITE, store.snapshot()?.mode)
        assertFalse(store.snapshot()!!.suAvailable)
    }

    @Test fun currentNormalOwnershipResolvesBeforeOtherChannelsWithoutGrantingUntestedSu() {
        val previous = EnvironmentSnapshot(WorkMode.LITE, true, "ultra:running", "older-boot", 1L)
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
        val probes = object : EnvironmentProbes {
            override fun ksuNormal() = true
            override fun currentUltraFingerprint(): String? = error("Normal owner is already confirmed")
            override fun suPresent(): Boolean = error("ordinary SU was not tested")
            override fun ultraFingerprint(): String? = error("recovery is unnecessary")
        }
        val outcome = EnvironmentResolver(probes, store).resolve(boot)
        assertEquals(EnvironmentVerdict.KSU_NORMAL, outcome.verdict)
        assertEquals(listOf("ksu"), outcome.probes)
        assertEquals(WorkMode.KSU, store.snapshot()?.mode)
        assertNull(store.snapshot()?.ultraEnvFingerprint)
        assertFalse(store.snapshot()!!.suAvailable)
    }

    @Test fun currentUltraOwnershipResolvesBeforeOrdinarySuAndRecovery() {
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence())
        val probes = object : EnvironmentProbes {
            override fun ksuNormal() = false
            override fun currentUltraFingerprint() = "ultra:running"
            override fun suPresent(): Boolean = error("ordinary SU was not tested")
            override fun ultraFingerprint(): String? = error("recovery is unnecessary")
        }
        val outcome = EnvironmentResolver(probes, store).resolve(boot)
        assertEquals(EnvironmentVerdict.ULTRA, outcome.verdict)
        assertEquals(listOf("ksu", "ultraCurrent"), outcome.probes)
        assertEquals(WorkMode.LITE, store.snapshot()?.mode)
        assertEquals("ultra:running", store.snapshot()?.ultraEnvFingerprint)
        assertFalse(store.snapshot()!!.suAvailable)
    }
}
