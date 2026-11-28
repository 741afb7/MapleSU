package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test

class UltraEvidenceFirstTest {
    private val boot = "current-boot"

    @Test fun currentUltraIdentityDoesNotWaitForOrdinarySuOrCredentialRecovery() {
        for (fingerprint in listOf("ultra:running", "ultra:fault", "ultra:kernel", "hook:ready")) {
            val calls = mutableListOf<String>()
            val probes = object : EnvironmentProbes {
                override fun currentUltraFingerprint(): String {
                    calls += "current"
                    return fingerprint
                }
                override fun suPresent(): Boolean = error("ordinary SU must not delay current Ultra identity")
                override fun ultraFingerprint(): String? = error("confirmed identity must not recover credentials")
                override fun ksuNormal(): Boolean = error("current evidence already excluded Normal")
            }
            val previous = EnvironmentSnapshot(WorkMode.LITE, true, "ultra:running", "old-boot", 1)
            val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
            val outcome = EnvironmentResolver(probes, store).resolve(boot)

            assertEquals(fingerprint, EnvironmentVerdict.ULTRA, outcome.verdict)
            assertEquals(WorkMode.LITE, outcome.mode)
            assertEquals(listOf("current"), calls)
            assertEquals(fingerprint, store.snapshot()?.ultraEnvFingerprint)
            assertEquals(boot, store.snapshot()?.bootId)
            assertFalse("mode evidence does not grant an untested SU capability", store.snapshot()!!.suAvailable)
        }
    }

    @Test fun absentCurrentEvidenceRetainsCredentialRecoveryAndNormalFallback() {
        val calls = mutableListOf<String>()
        var normalReads = 0
        val probes = object : EnvironmentProbes {
            override fun currentUltraFingerprint(): String? { calls += "current"; return null }
            override fun suPresent(): Boolean { calls += "su"; return false }
            override fun ultraFingerprint(): String? { calls += "recovery"; return null }
            override fun ksuNormal(): Boolean {
                calls += "normal"
                // The initial native read has not confirmed Normal; the final read does.
                return ++normalReads > 1
            }
        }
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence())
        val outcome = EnvironmentResolver(probes, store).resolve(boot)

        assertEquals(EnvironmentVerdict.KSU_NORMAL, outcome.verdict)
        assertEquals(listOf("normal", "current", "su", "recovery", "normal"), calls)
        assertFalse(store.snapshot()!!.suAvailable)
    }

    @Test fun unreadableCurrentChannelDoesNotEraseTheRecoveryFallback() {
        val probes = object : EnvironmentProbes {
            override fun currentUltraFingerprint(): String? = error("read failed")
            override fun suPresent(): Boolean = false
            override fun ultraFingerprint(): String = "ultra:running"
            override fun ksuNormal(): Boolean = error("Ultra recovered")
        }
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence())

        val outcome = EnvironmentResolver(probes, store).resolve(boot)

        assertEquals(EnvironmentVerdict.ULTRA, outcome.verdict)
        assertEquals("ultra:running", store.snapshot()?.ultraEnvFingerprint)
        assertFalse(store.snapshot()!!.suAvailable)
    }

    @Test fun laterLiveRunningEvidenceReplacesFaultWithoutRestartingTheResolver() {
        var fingerprint = "ultra:fault"
        val probes = object : EnvironmentProbes {
            override fun currentUltraFingerprint(): String = fingerprint
            override fun suPresent(): Boolean = error("current identity is independent of ordinary SU")
            override fun ultraFingerprint(): String? = error("current identity is already confirmed")
            override fun ksuNormal(): Boolean = error("current evidence already excluded Normal")
        }
        val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence())
        val resolver = EnvironmentResolver(probes, store)
        resolver.resolve(boot)
        assertEquals("ultra:fault", store.snapshot()?.ultraEnvFingerprint)

        fingerprint = "ultra:running"
        resolver.resolve(boot)

        assertEquals("ultra:running", store.snapshot()?.ultraEnvFingerprint)
        assertFalse(store.snapshot()!!.suAvailable)
    }
}
