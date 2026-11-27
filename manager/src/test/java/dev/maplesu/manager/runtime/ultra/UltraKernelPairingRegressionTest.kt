package dev.maplesu.manager.runtime.ultra

import dev.maplesu.manager.runtime.UltraEnvRootExecutor
import dev.maplesu.manager.runtime.UltraRootCommandResult
import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.runtime.RootExecutionRouter
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test

class UltraKernelPairingRegressionTest {
    @Test fun aLiveKernelPairsDespiteUnconfirmedHelperAndRunsTheNormalMaintenance() {
        for (state in listOf(UltraEnvironmentProbe.State.Unknown, UltraEnvironmentProbe.State.NotInstalled)) {
            val events = mutableListOf<String>()
            val result = UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(state, kernelRootVerified = true),
                current = { events += "scope"; true }, verifyManagerSu = { true }, maintain = { events += "maintenance" })
            assertTrue("$state: current Key-based uid 0 must pair", result.paired)
            assertEquals(state, result.state)
            assertEquals(listOf("scope", "scope", "maintenance", "scope"), events)
        }
    }

    @Test fun staleFlagsForeignRootAndFaultNeverAuthorizePairing() {
        for (state in UltraEnvironmentProbe.State.entries.filter { it != UltraEnvironmentProbe.State.Running }) {
            val result = UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(state), current = { true },
                verifyManagerSu = { true }, maintain = { fail("no live proof") })
            assertFalse(result.paired)
        }
        val fault = UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Fault, kernelRootVerified = true),
            current = { true }, verifyManagerSu = { true }, maintain = { fail() })
        assertFalse(fault.paired)
    }

    @Test fun identityChangesCancelBeforePairingOrMaintenance() {
        val result = UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running, kernelRootVerified = true),
            current = { false }, verifyManagerSu = { true }, maintain = { fail() })
        assertFalse(result.paired)
        assertEquals("SDK_IDENTITY_SCOPE_CHANGED", result.reason)
    }

    @Test fun alreadyVerifiedKeyStillCompletesPairingAndSchedulesMaintenanceWithoutAnotherWrite() {
        val events = mutableListOf<String>()
        val result = UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Unknown, kernelRootVerified = true),
            current = { true }, verifyManagerSu = { true }, maintain = { events += "maintenance" })
        assertTrue(result.paired)
        assertEquals("RUNNING", result.reason)
        assertEquals(listOf("maintenance"), events)
    }
}
