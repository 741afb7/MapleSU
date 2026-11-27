package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.ActivationSource
import dev.maplesu.manager.workmode.boot.*
import dev.maplesu.manager.runtime.*
import dev.maplesu.manager.runtime.ultra.UltraAutoPair
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Enumerates equivalence classes through production algorithms, not a copied rules table. */
class ModeRecognitionMatrixTest {
    private val boot = "matrix-boot"
    private val bools = listOf(false, true)
    private fun priorStates(): List<EnvironmentSnapshot?> = listOf(null) +
        listOf(null, WorkMode.KSU, WorkMode.LITE).flatMap { mode ->
            listOf(boot, "old-boot", "").flatMap { previousBoot ->
                bools.flatMap { dirty -> bools.flatMap { su ->
                    listOf(null, "ultra:running").map { fingerprint ->
                        EnvironmentSnapshot(mode, su, fingerprint, previousBoot, 1, dirty)
                    }
                } }
            }
        }

    @Test fun currentIdentityWinsOverEveryCacheAndOrdinarySuOutcome() {
        var cases = 0
        var mismatches = 0
        val examples = mutableListOf<String>()
        for (previous in priorStates()) for (su in SuReadiness.entries + listOf(null))
            for (ksu in listOf(true, false, null)) for (ultra in listOf(null, "ultra:kernel", "failed")) {
            // The production Ultra adapter excludes a currently owned Normal kernel first.
            if (ksu == true && ultra != null) continue
            cases++
            val calls = mutableListOf<String>()
            val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
            val probes = object : EnvironmentProbes {
                override fun suPresent() = error("use the three-state port")
                override fun suReadiness(): SuReadiness {
                    calls += "su"
                    return su ?: error("ordinary su process failed")
                }
                override fun ultraFingerprint(): String? {
                    calls += "ultra"
                    if (ultra == "failed") error("SDK read unavailable")
                    return ultra
                }
                override fun currentUltraFingerprint(): String? {
                    calls += "currentUltra"
                    if (ultra == "failed") error("current SDK read unavailable")
                    return ultra
                }
                override fun ksuNormal(): Boolean? { calls += "ksu"; return ksu }
                override fun rootShellAvailable(): Boolean = error("no fallback shell")
            }
            val result = EnvironmentResolver(probes, store, Clock { 100 }).resolve(boot)
            val expected = when {
                ksu == true -> WorkMode.KSU
                ultra == "ultra:kernel" -> WorkMode.LITE
                else -> null
            }
            val unknown = expected == null && (ksu == null || ultra == "failed")
            val valid = if (unknown) {
                result.verdict == EnvironmentVerdict.UNKNOWN && store.snapshot() == previous &&
                    EnvironmentDetectionProjection.override(result) == null
            } else {
                result.verdict != EnvironmentVerdict.UNKNOWN && result.mode == expected &&
                    store.snapshot()?.mode == expected && store.snapshot()?.bootId == boot &&
                    !store.isDirty()
            }
            if (!valid) {
                mismatches++
                if (examples.size < 8) examples += "cache=$previous su=$su ksu=$ksu ultra=$ultra -> ${result.verdict}/${result.mode}; expected=$expected unknown=$unknown"
            }
            val currentIdentityConfirmed = ksu == true || ultra == "ultra:kernel"
            assertEquals(if (currentIdentityConfirmed) 0 else 1, calls.count { it == "su" })
            assertEquals(if (ksu == true) 0 else 1, calls.count { it == "currentUltra" })
            assertEquals(if (currentIdentityConfirmed) 0 else 1, calls.count { it == "ultra" })
            assertEquals(if (currentIdentityConfirmed) 1 else 2, calls.count { it == "ksu" })
        }
        println("MATRIX resolver: $cases combinations; $mismatches mismatches")
        assertEquals(examples.joinToString("\n"), 0, mismatches)
    }

    @Test fun selectedChannelMatchesTheConfirmedModeAndSkipsUnusedProbes() = runBlocking {
        val kernels = listOf(KsuFacts(), KsuFacts(true, 100, 7, true),
            KsuFacts(true, 100, 7, false), KsuFacts(true, 100, 8, false))
        val ultraStates = UltraState.entries.flatMap { state ->
            (if (state in listOf(UltraState.UNKNOWN, UltraState.NOT_INSTALLED)) bools else listOf(false))
                .map { UltraEnvironmentObservation(state, it) }
        }
        var cases = 0
        var mismatches = 0
        val examples = mutableListOf<String>()
        for (ksu in kernels) for (ultra in ultraStates) for (credential in bools)
            for (handshake in listOf(true, false, null)) for (hook in bools)
            for (rootAllowed in bools) for (liteAllowed in bools) for (hookAllowed in bools) {
            cases++
            var handshakes = 0
            var hookReads = 0
            var rootOpens = 0
            var refresh = false
            val ultraConfirmed = ultra.state in listOf(UltraState.RUNNING, UltraState.FAULT) || ultra.kernelRootVerified
            val legacy = !ksu.responded && liteAllowed && credential && !ultraConfirmed && handshake == true
            val liveHook = !ksu.responded && hookAllowed && !ultraConfirmed && hook
            val expectedMode = when {
                ksu.responded -> if (ksu.isManager) WorkMode.KSU else null
                ultraConfirmed || legacy || liveHook -> WorkMode.LITE
                else -> null
            }
            val expectedChannel = if (!rootAllowed) null else when {
                ksu.responded -> if (ksu.isManager) RootChannelKind.KSU_NATIVE else null
                ultra.state == UltraState.FAULT -> null
                ultraConfirmed -> RootChannelKind.LITE_ULTRA
                legacy -> RootChannelKind.LITE_DIRECT
                liveHook -> RootChannelKind.JAILBREAK
                credential -> RootChannelKind.LITE_DIRECT
                else -> null
            }
            val result = DefaultModeDetector(
                ksuProbe = KsuKernelProbe { ksu },
                liteProbe = LiteCredentialProbe { credential to null },
                liteHandshakeProbe = LiteHandshakeProbe { handshakes++; handshake },
                ultraPort = object : UltraEnvironmentPort {
                    override fun state() = ultra.state
                    override fun observe(allowRefresh: Boolean): UltraEnvironmentObservation { refresh = allowRefresh; return ultra }
                },
                jailbreakLease = JailbreakLeaseProbe { hookReads++; hook },
                bootIdSource = BootIdSource { boot },
                rootEvidenceReader = object : RootEvidenceReader {
                    override fun open(channel: RootChannelKind): RootEvidenceSession? { rootOpens++; return null }
                },
            ).detect(DetectionRequest(DetectionOrigin.AFTER_ROOT_GRANTED, rootAllowed, liteAllowed, hookAllowed))
            val expectedHandshakeReads = if (!ksu.responded && liteAllowed && credential && !ultraConfirmed) 1 else 0
            val expectedHookReads = if (!ksu.responded && hookAllowed && !ultraConfirmed && !legacy) 1 else 0
            if (result.state.activeMode != expectedMode || result.rootChannel != expectedChannel ||
                handshakes != expectedHandshakeReads || hookReads != expectedHookReads) {
                mismatches++
                if (examples.size < 8) examples += "ksu=$ksu ultra=$ultra cred=$credential handshake=$handshake hook=$hook flags=$rootAllowed/$liteAllowed/$hookAllowed -> ${result.state}/${result.rootChannel} probes=$handshakes/$hookReads expected=$expectedMode/$expectedChannel probes=$expectedHandshakeReads/$expectedHookReads"
            }
            val ultraIdentityDecisive = !ksu.responded && ultraConfirmed
            assertEquals(if (expectedChannel != null && !ultraIdentityDecisive) 1 else 0, rootOpens)
            if (ultraIdentityDecisive) assertEquals(RecoveryReadiness.NONE, result.recovery)
            assertEquals(liteAllowed && !ksu.responded, refresh)
        }
        println("MATRIX detector: $cases combinations; $mismatches mismatches")
        assertEquals(examples.joinToString("\n"), 0, mismatches)
    }

    @Test fun startupHomePairingAndExecutionAgreeForEveryLiveProvider() = runBlocking {
        var cases = 0
        val caches = listOf(null, EnvironmentSnapshot(WorkMode.KSU, true, null, boot, 1),
            EnvironmentSnapshot(WorkMode.LITE, true, "ultra:running", "old-boot", 1),
            EnvironmentSnapshot(null, false, null, boot, 1))
        val kernels = listOf(KsuFacts(), KsuFacts(true, 100, 7, true),
            KsuFacts(true, 100, 7, false), KsuFacts(true, 100, 8, false))
        for (ksu in kernels) for (source in listOf("none", "kernel", "running", "fault", "legacy", "hook"))
            for (previous in caches) for (su in SuReadiness.entries) for (accept in bools) {
            // Live providers are exclusive here; conflicting probe inputs are tested above.
            if (ksu.responded && source != "none") continue
            cases++
            val store = EnvironmentStateStore(InMemoryEnvironmentStatePersistence(previous))
            val ultra = when (source) {
                "running" -> UltraState.RUNNING
                "fault" -> UltraState.FAULT
                else -> UltraState.UNKNOWN
            }
            val quick = object : EnvironmentProbes {
                override fun suPresent() = su == SuReadiness.USABLE
                override fun suReadiness() = su
                override fun ultraFingerprint(): String? = when (source) {
                    "running" -> "ultra:running"
                    "fault" -> "ultra:fault"
                    "kernel" -> "ultra:kernel"
                    "hook" -> "hook:ready"
                    else -> null
                }
                override fun ksuNormal() = ksu.responded && ksu.isManager
            }
            var active: WorkMode? = null
            val pipeline = ModeBootstrapPipeline(DefaultModeDetector(
                ksuProbe = KsuKernelProbe { ksu },
                liteProbe = LiteCredentialProbe { (source == "legacy") to null },
                liteHandshakeProbe = LiteHandshakeProbe { source == "legacy" },
                ultraPort = object : UltraEnvironmentPort {
                    override fun state() = ultra
                    override fun observe(allowRefresh: Boolean) = UltraEnvironmentObservation(ultra, source == "kernel")
                },
                jailbreakLease = JailbreakLeaseProbe { source == "hook" },
                bootIdSource = BootIdSource { boot },
            ), runtime = object : ModeRuntimePort {
                override fun recordDetection(result: DetectionResult) {}
                override fun completeBootstrap(result: DetectionResult) {}
                override fun activate(mode: WorkMode, bootId: String, source: ActivationSource): Boolean {
                    if (accept) active = mode
                    return accept
                }
            }, environmentOverride = { EnvironmentDetectionProjection.override(EnvironmentResolver(quick, store).resolve(boot)) },
                environmentStore = store)
            val outcome = pipeline.run(DetectionRequest(DetectionOrigin.AFTER_ROOT_GRANTED, true, true, true))
            val expected = when {
                ksu.responded && ksu.isManager -> WorkMode.KSU
                ksu.responded || source == "none" -> null
                else -> WorkMode.LITE
            }
            assertEquals("source=$source ksu=$ksu cache=$previous su=$su accept=$accept", expected?.takeIf { accept }, active)
            assertEquals(active != null, outcome is BootstrapOutcome.Activated)
            val home = EnvironmentHomeProjection.decide(EnvironmentHomeProjection.Facts(store.snapshot(), boot))
            if (active != null) {
                assertEquals(active, store.snapshot()?.mode)
                assertFalse(home.confirmedNotInstalled)
                val ultraState = when (source) {
                    "running" -> UltraEnvironmentProbe.State.Running
                    "fault" -> UltraEnvironmentProbe.State.Fault
                    else -> UltraEnvironmentProbe.State.Unknown
                }
                val snapshot = UltraEnvironmentProbe.Snapshot(ultraState,
                    kernelRootVerified = source in listOf("kernel", "running"))
                val paired = UltraAutoPair.complete(snapshot, current = { true },
                    verifyManagerSu = { su == SuReadiness.USABLE }, maintain = {}).paired
                assertEquals(source in listOf("kernel", "running") && su == SuReadiness.USABLE, paired)
                fun executor(backend: RootBackend, usable: Boolean) = object : RootExecutor {
                    override val backend = backend
                    override fun available() = usable
                    override fun run(script: String) = RootExecResult(backend, 0, "verified")
                }
                val router = RootExecutionRouter(executor(RootBackend.ULTRA_ENV, paired), modeProvider = { active },
                    ksuProvider = { executor(RootBackend.KSU, ksu.isManager) },
                    ultraIdentity = { source in listOf("kernel", "running", "fault") },
                    hook = executor(RootBackend.HOOK, source == "hook"))
                val backend = when {
                    active == WorkMode.KSU -> RootBackend.KSU
                    paired -> RootBackend.ULTRA_ENV
                    source == "hook" -> RootBackend.HOOK
                    else -> RootBackend.NONE
                }
                assertEquals(backend, router.capability(true).backend)
                assertEquals(backend, router.execute("id -u").backend)
                if (source == "fault") assertFalse(store.snapshot()!!.suAvailable)
            }
        }
        println("MATRIX startup/home/backend: $cases combinations")
    }
}
