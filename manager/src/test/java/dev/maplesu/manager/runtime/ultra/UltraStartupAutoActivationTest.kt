package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.*
import org.junit.Test

/** Current startup/setup/installer/readiness state machines with explicit SDK observations. */
class UltraStartupAutoActivationTest {
    private val input = UltraStartupWarmup.Input(null, true, false, false, false, false, false, false)

    private inner class Fixture : UltraAutoSetupAttempt.Port {
        var owner = true
        var managerSu = true
        var state = UltraEnvironmentProbe.State.NotInstalled
        var guard = false
        var uncertain = false
        var installs = 0
        var detections = 0
        val events = mutableListOf<String>()
        override fun blockedReason(): String? = null
        override fun state() = state
        override fun recordAttempt() { events += "attempt" }
        override fun alreadySubmitted() = guard
        override fun markSubmitted(): Boolean { guard = true; return true }
        override fun clearUnsubmitted() { guard = false }
        override fun refresh() { events += "refresh" }
        override fun install(): UltraEnvironmentInstaller.Outcome = UltraEnvironmentInstaller.install(
            object : UltraEnvironmentInstaller.InstallPort {
                override fun environmentState() = receipt("Running".takeIf { state == UltraEnvironmentProbe.State.Running } ?: "NotInstalled")
                override fun submitInstall(): NativeReceipt {
                    installs++; events += "submit"
                    if (uncertain) return receipt(error = "HOTLOAD_REPLY_LOST", code = -21002)
                    state = UltraEnvironmentProbe.State.Running
                    return receipt("Running")
                }
            }, UltraEnvironmentInstaller.Intent.ReuseRunning,
            UltraEnvironmentInstaller.Attachments(
                whitelist = { UltraSelfWhitelist.Result(true, true, false, true, true) },
                verify = { events += "readback"; IdentityObservation(InstalledIdentity.unknown(), IdentityVerification.passed(null)) },
                persist = { events += "persist" }))
        fun run() = UltraStartupWarmup.run(input,
            recoverIdentity = { events += "owner"; owner },
            setupEnvironment = { UltraAutoSetupAttempt.run(this) },
            pairEnvironment = {
                UltraAutoPair.complete(UltraEnvironmentProbe.Snapshot(state, kernelRootVerified = owner),
                    current = { owner }, verifyManagerSu = { managerSu }, maintain = {}).paired
            }, retryDetection = { detections++ })
    }

    private fun receipt(state: String? = null, error: String = "OK", code: Int = 0) = NativeReceipt.parse(
        """{"backend":"maplesu_ultra","operation":"install_maplesu_environment","ok":${error == "OK"},"sdkError":"$error","sdkErrorCode":$code,"data":{"state":"$state"}}""")!!

    @Test fun currentOwnerInstallsOnceAndOnlyReadbackAndActualSuPermitActivation() {
        val fixture = Fixture()
        assertTrue(fixture.run().paired)
        assertEquals(1, fixture.installs)
        assertTrue(fixture.guard)
        assertTrue(fixture.events.indexOf("submit") < fixture.events.indexOf("readback"))
        assertTrue(fixture.events.indexOf("readback") < fixture.events.indexOf("persist"))
        assertTrue(fixture.run().paired)
        assertEquals(1, fixture.installs)
    }

    @Test fun committedReplyLossKeepsTheSubmissionAcrossFreshStartupRequests() {
        val fixture = Fixture().apply { uncertain = true; managerSu = false }
        repeat(3) { assertFalse(fixture.run().paired) }
        assertEquals(1, fixture.installs)
        assertTrue(fixture.guard)
        assertEquals(0, fixture.detections)
        assertFalse("unconfirmed installation must not persist a helper", fixture.events.contains("persist"))
    }

    @Test fun aFreshManagerWithoutCurrentOwnerKeepsUnknownWithoutSetup() {
        val fixture = Fixture().apply { owner = false }
        assertFalse(fixture.run().ran)
        assertEquals(0, fixture.installs)
        assertEquals(listOf("owner"), fixture.events)
    }

    @Test fun aRunningEnvironmentStillRequiresActualManagerSu() {
        val fixture = Fixture().apply { state = UltraEnvironmentProbe.State.Running; managerSu = false }
        assertFalse(fixture.run().paired)
        assertEquals(0, fixture.installs)
        assertEquals(0, fixture.detections)
    }
}
