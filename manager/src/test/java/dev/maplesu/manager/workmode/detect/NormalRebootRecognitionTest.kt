package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.ActivationSource
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.boot.BootstrapOutcome
import dev.maplesu.manager.workmode.boot.ModeBootstrapCoordinator
import dev.maplesu.manager.workmode.boot.ModeBootstrapPipeline
import dev.maplesu.manager.workmode.boot.ModeRuntimePort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Host audit: real resolver -> detector/projection -> bootstrap; native/boot/prefs ports are fixtures. */
class NormalRebootRecognitionTest {
    private class Probes(val su: Boolean, val ultra: String? = null, val ksu: Boolean? = true) : EnvironmentProbes {
        val calls = mutableListOf<String>()
        override fun suPresent(): Boolean { calls += "su"; return su }
        override fun ultraFingerprint(): String? { calls += "ultra"; return ultra }
        override fun ksuNormal(): Boolean? { calls += "ksu"; return ksu }
        override fun rootShellAvailable(): Boolean = error("root shell must not run")
    }

    private class Runtime : ModeRuntimePort {
        val calls = mutableListOf<String>()
        var active: WorkMode? = null
        var boot: String? = null
        override fun recordDetection(result: DetectionResult) { calls += "record:${result.state}" }
        override fun completeBootstrap(result: DetectionResult) { calls += "complete" }
        override fun activate(mode: WorkMode, bootId: String, source: ActivationSource): Boolean {
            assertEquals(ActivationSource.BOOT_DETECTION, source)
            calls += "activate"
            active = mode
            boot = bootId
            return true
        }
    }

    private fun normal(boot: String = "same-boot", dirty: Boolean = false) = EnvironmentSnapshot(
        mode = WorkMode.KSU, suAvailable = true, ultraEnvFingerprint = null,
        bootId = boot, checkedAtMillis = 1, dirty = dirty,
    )

    private fun store(initial: EnvironmentSnapshot? = null) =
        EnvironmentStateStore(InMemoryEnvironmentStatePersistence(initial))

    private suspend fun check(store: EnvironmentStateStore, probes: EnvironmentProbes, boot: String = "same-boot") =
        EnvironmentCheckRunner(EnvironmentResolver(probes, store), store, bootIdSource = { boot }).check()

    private suspend fun start(
        check: EnvironmentCheckOutcome,
        currentKernel: KsuFacts?,
        expectedPhaseOne: ModeState,
        expectedPhaseTwo: ModeState? = null,
    ): Runtime {
        val runtime = Runtime()
        var overrideReads = 0
        var rootReads = 0
        var handshakeReads = 0
        val pipeline = ModeBootstrapPipeline(
            detector = DefaultModeDetector(
                ksuProbe = KsuKernelProbe { currentKernel ?: error("Current native observation is unreadable") },
                liteHandshakeProbe = LiteHandshakeProbe { handshakeReads++; false },
                rootEvidenceReader = object : RootEvidenceReader {
                    override fun open(channel: RootChannelKind): RootEvidenceSession? { rootReads++; return null }
                },
                bootIdSource = BootIdSource { check.bootId },
            ),
            runtime = runtime,
            environmentOverride = { overrideReads++; EnvironmentDetectionProjection.override(check) },
        )
        val coordinator = ModeBootstrapCoordinator({ pipeline }, { runtime.active != null })
        val result = coordinator.runPhaseOne()
        assertEquals("Startup skips the slow environment precheck", 0, overrideReads)
        if (expectedPhaseOne == ModeState.MODE_B_KERNEL_ONLY) {
            assertTrue(result is BootstrapOutcome.Activated)
            assertEquals(WorkMode.KSU, runtime.active)
            assertEquals(check.bootId, runtime.boot)
            assertEquals(listOf("record:MODE_B_KERNEL_ONLY", "complete", "activate"), runtime.calls)
            assertNull("Normal must skip phase-two root detection", coordinator.runPhaseTwo())
        } else {
            assertTrue(result is BootstrapOutcome.Unresolved)
            assertEquals(expectedPhaseOne, (result as BootstrapOutcome.Unresolved).state)
            assertNull(runtime.active)
            assertEquals(listOf("record:$expectedPhaseOne", "complete"), runtime.calls)
        }
        if (expectedPhaseTwo != null) {
            val confirmed = coordinator.runPhaseTwo()
            assertTrue(confirmed is BootstrapOutcome.Unresolved)
            assertEquals(expectedPhaseTwo, (confirmed as BootstrapOutcome.Unresolved).state)
            assertEquals(1, overrideReads)
            assertNull(runtime.active)
            assertEquals(listOf("record:$expectedPhaseOne", "complete", "record:$expectedPhaseTwo", "complete"), runtime.calls)
        }
        assertEquals("Neither phase needs a Root session", 0, rootReads)
        assertEquals("Neither phase needs a credential handshake", 0, handshakeReads)
        return runtime
    }

    @Test fun firstNormalJailbreakSoftRebootActivatesNormalWithoutRootCycle() = runBlocking {
        val store = store()
        val probes = Probes(su = true)
        val check = check(store, probes)
        assertEquals(EnvironmentVerdict.KSU_NORMAL, check.verdict)
        assertEquals(listOf("ksu"), probes.calls)
        assertEquals(WorkMode.KSU, store.snapshot()?.mode)
        assertFalse("Current identity alone does not grant SU", store.snapshot()!!.suAvailable)
        start(check, KsuFacts(true, 100, 7, true), ModeState.MODE_B_KERNEL_ONLY)
        Unit
    }

    @Test fun previouslyNotInstalledIsRecheckedAfterSoftReboot() = runBlocking {
        val store = store(normal().copy(mode = null, suAvailable = false))
        val check = check(store, Probes(su = true))
        assertEquals(EnvironmentCheckTrigger.NOT_INSTALLED, check.trigger)
        assertEquals(EnvironmentVerdict.KSU_NORMAL, check.verdict)
        start(check, KsuFacts(true, 100, 7, true), ModeState.MODE_B_KERNEL_ONLY)
        Unit
    }

    @Test fun knownNormalSoftRebootRevalidatesAndSkipsResidualUltraRecords() = runBlocking {
        val store = store(normal())
        val probes = Probes(su = true, ultra = "ultra:paired")
        val check = check(store, probes)
        assertEquals(EnvironmentVerdict.KSU_NORMAL, check.verdict)
        assertEquals(listOf("ksu"), probes.calls)
        assertFalse("The previous SU capability is not inherited", store.snapshot()!!.suAvailable)
        start(check, KsuFacts(true, 100, 7, true), ModeState.MODE_B_KERNEL_ONLY)
        Unit
    }

    @Test fun fullRebootWithInstalledNormalRevalidatesTheNewBoot() = runBlocking {
        val store = store(normal(boot = "old-boot"))
        val check = check(store, Probes(su = true), boot = "new-boot")
        assertEquals(EnvironmentCheckTrigger.BOOT_CHANGED, check.trigger)
        assertEquals(EnvironmentVerdict.KSU_NORMAL, check.verdict)
        assertEquals("new-boot", store.snapshot()?.bootId)
        start(check, KsuFacts(true, 100, 7, true), ModeState.MODE_B_KERNEL_ONLY)
        Unit
    }

    @Test fun fullRebootLosingTemporaryNormalEndsNotInstalled() = runBlocking {
        val store = store(normal(boot = "old-boot"))
        val check = check(store, Probes(su = false, ksu = false), boot = "new-boot")
        assertEquals(EnvironmentVerdict.NOT_INSTALLED, check.verdict)
        assertNull(store.snapshot()?.mode)
        start(check, KsuFacts(), ModeState.INDETERMINATE, ModeState.NOT_INSTALLED)
        Unit
    }

    @Test fun missingNativeObservationPreservesTheLastNormalSnapshot() = runBlocking {
        val previous = normal(boot = "old-boot")
        val store = store(previous)
        val check = check(store, Probes(su = true, ksu = null), boot = "new-boot")
        assertEquals(EnvironmentVerdict.UNKNOWN, check.verdict)
        assertEquals(previous, store.snapshot())
        assertEquals(WorkMode.KSU, check.mode)
        assertNull(EnvironmentDetectionProjection.override(check))
        start(check, null, ModeState.INDETERMINATE, ModeState.INDETERMINATE)
        assertEquals(previous, store.snapshot())
    }

    @Test fun historicalNormalOutcomesNeverOverrideNegativeCurrentKernelInPhaseOne() = runBlocking {
        for (inherited in listOf(false, true)) {
            val previous = normal()
            val store = store(previous)
            var nativeReads = 0
            val probes = object : EnvironmentProbes {
                override fun suPresent(): Boolean = true
                override fun ultraFingerprint(): String? = null
                override fun ksuNormal(): Boolean? = if (inherited) ++nativeReads > 1 else null
                override fun rootShellAvailable(): Boolean = error("Root shell must not run")
            }
            val check = check(store, probes)
            assertEquals(if (inherited) EnvironmentVerdict.INHERITED else EnvironmentVerdict.UNKNOWN, check.verdict)
            assertEquals(WorkMode.KSU, check.mode)
            start(check, KsuFacts(), ModeState.INDETERMINATE)
            assertEquals(previous, store.snapshot())
        }
    }

    @Test fun dirtyNormalAfterJailbreakIsRevalidatedWithoutRoot() = runBlocking {
        val store = store(normal())
        store.markDirty(EnvironmentInvalidation.JAILBREAK_COMPLETED)
        val check = check(store, Probes(su = true))
        assertEquals(EnvironmentCheckTrigger.DIRTY, check.trigger)
        assertEquals(EnvironmentVerdict.KSU_NORMAL, check.verdict)
        assertFalse(store.isDirty())
        start(check, KsuFacts(true, 100, 7, true), ModeState.MODE_B_KERNEL_ONLY)
        Unit
    }

    @Test fun historicalUltraCredentialsDoNotOverrideTheRunningNormalKernel() = runBlocking {
        for (fingerprint in listOf("ultra:paired", "lite:credential")) {
            val store = store(normal().copy(mode = WorkMode.LITE, ultraEnvFingerprint = fingerprint))
            val probes = Probes(su = true)
            val outcome = check(store, probes)
            assertEquals(fingerprint, EnvironmentVerdict.KSU_NORMAL, outcome.verdict)
            assertEquals(WorkMode.KSU, store.snapshot()?.mode)
            assertEquals(listOf("ksu"), probes.calls)
            assertFalse("Historical credentials do not grant SU", store.snapshot()!!.suAvailable)
            start(outcome, KsuFacts(true, 100, 7, true), ModeState.MODE_B_KERNEL_ONLY)
        }
    }

    @Test fun runningUltraIsStillRecognizedAfterRecheckingAnOldCredentialSnapshot() = runBlocking {
        val store = store(normal().copy(mode = WorkMode.LITE, ultraEnvFingerprint = "ultra:paired"))
        val outcome = check(store, Probes(su = true, ultra = "ultra:running", ksu = false))
        assertEquals(EnvironmentVerdict.ULTRA, outcome.verdict)
        assertEquals(WorkMode.LITE, outcome.mode)
        assertEquals("ultra:running", store.snapshot()?.ultraEnvFingerprint)
    }

}
