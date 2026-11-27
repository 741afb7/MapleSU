package dev.maplesu.manager.runtime

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootExecutionHookTest {
    private class Executor(override val backend: RootBackend) : RootExecutor {
        var available = true
        var code = 0
        var calls = 0
        override fun available() = available
        override fun run(script: String): RootExecResult {
            calls++
            return RootExecResult(backend, code, script)
        }
    }

    @Test fun ultraHookProvidesRootWithoutAUltraPairing() {
        val hook = Executor(RootBackend.HOOK)
        val router = RootExecutionRouter(null, modeProvider = { WorkMode.LITE }, hook = hook)
        assertTrue(router.execute("id -u").ok)
        assertEquals(1, hook.calls)
        assertEquals(RootBackend.HOOK, router.capability().backend)
        assertTrue(router.rootAvailableForLite())
    }

    @Test fun aFailedHookMutationIsNeverReplayedOnAnotherRootBackend() {
        val hook = Executor(RootBackend.HOOK).apply { code = 137 }
        val ultra = Executor(RootBackend.ULTRA_ENV)
        val lite = Executor(RootBackend.LITE_KERNEL)
        val router = RootExecutionRouter(ultra, modeProvider = { WorkMode.LITE }, hook = hook)
        assertFalse(router.execute("write-once").ok)
        assertEquals(1, hook.calls)
        assertEquals(0, ultra.calls)
        assertEquals(0, lite.calls)
    }

    @Test fun anUnresolvedModeCanUseAnActualHookGate() {
        val hook = Executor(RootBackend.HOOK)
        val router = RootExecutionRouter(null, modeProvider = { null }, hook = hook)
        assertEquals(RootBackend.HOOK, router.execute("id -u").backend)
    }

    @Test fun normalKeepsItsOwnBackendWhenAHookGateExists() {
        val hook = Executor(RootBackend.HOOK)
        val ksu = Executor(RootBackend.KSU)
        val router = RootExecutionRouter(null, modeProvider = { WorkMode.KSU }, ksuProvider = { ksu }, hook = hook)
        assertEquals(RootBackend.KSU, router.execute("id -u").backend)
        assertEquals(0, hook.calls)
    }

    @Test fun anExplicitUltraHookRequestDoesNotUseTheCurrentNormalBackend() {
        val hook = Executor(RootBackend.HOOK)
        val ksu = Executor(RootBackend.KSU)
        val router = RootExecutionRouter(null, modeProvider = { WorkMode.KSU }, ksuProvider = { ksu }, hook = hook)
        RootExec.installForTest(router)
        try {
            assertEquals(RootBackend.HOOK, RootExec.executeHook(null, "install-once").backend)
            assertEquals(0, ksu.calls)
        } finally { RootExec.resetForTest() }
    }

    @Test fun anExpiredExplicitHookRequestNeverFallsBackToNormal() {
        val hook = Executor(RootBackend.HOOK).apply { available = false }
        val ksu = Executor(RootBackend.KSU)
        val router = RootExecutionRouter(null, modeProvider = { WorkMode.KSU }, ksuProvider = { ksu }, hook = hook)
        assertFalse(router.executeHook("install-once").ok)
        assertEquals(0, hook.calls)
        assertEquals(0, ksu.calls)
    }

    @Test fun aVerifiedUltraIdentityKeepsItsOwnBackend() {
        val hook = Executor(RootBackend.HOOK)
        val ultra = Executor(RootBackend.ULTRA_ENV)
        val router = RootExecutionRouter(ultra, modeProvider = { WorkMode.LITE }, ultraIdentity = { true }, hook = hook)
        assertEquals(RootBackend.ULTRA_ENV, router.execute("id -u").backend)
        assertEquals(0, hook.calls)
    }
}
