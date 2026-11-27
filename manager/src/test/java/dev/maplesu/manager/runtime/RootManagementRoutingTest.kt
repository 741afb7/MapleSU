package dev.maplesu.manager.runtime

import com.topjohnwu.superuser.Shell
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RootManagementRoutingTest {
    @Test fun currentSdkAndHookRoutesRemainIndependentOfModeDetection() {
        for (mode in listOf(WorkMode.LITE, null)) for (sdk in listOf(false, true)) {
            val expected = FakeShell()
            assertSame(expected, RootManagement.openShellRouted(mode, sdk,
                openSdk = { if (!sdk) error("unexpected SDK") else expected },
                openKsu = { error("unexpected KSU") }, usesHook = true,
                openHook = { if (sdk) error("unexpected Hook") else expected }))
        }
    }

    @Test fun selectedLiteSdkIsStillAskedWhenReadinessIsPending() {
        val expected = FakeShell()
        assertSame(expected, RootManagement.openShellRouted(WorkMode.LITE, false,
            openSdk = { expected }, openKsu = { error("unexpected KSU") }))
    }

    @Test fun unresolvedModeWithoutAnObservedSourceStaysUnresolved() {
        val error = assertThrows(IllegalStateException::class.java) {
            RootManagement.openShellRouted(null, false, openSdk = { error("unexpected SDK") },
                openKsu = { error("unexpected KSU") })
        }
        assertEquals("MANAGEMENT_MODE_UNRESOLVED", error.message)
    }

    @Test fun ksuModeUsesOnlyItsRegisteredSource() {
        val expected = FakeShell()
        assertSame(expected, RootManagement.openShellRouted(WorkMode.KSU, true,
            openSdk = { error("unexpected SDK") }, openKsu = { expected },
            usesHook = true, openHook = { error("unexpected Hook") }))
    }

    @Test fun eachSelectedBackendPreservesItsFailureWithoutReplay() {
        for (source in listOf("sdk", "hook", "ksu")) {
            val expected = IllegalStateException("$source transport lost")
            var calls = 0
            val actual = assertThrows(IllegalStateException::class.java) {
                RootManagement.openShellRouted(if (source == "ksu") WorkMode.KSU else WorkMode.LITE,
                    source == "sdk", openSdk = { calls++; throw expected },
                    openKsu = { calls++; throw expected }, usesHook = source == "hook",
                    openHook = { calls++; throw expected })
            }
            assertSame(expected, actual)
            assertEquals(1, calls)
            assertEquals(0, actual.suppressed.size)
        }
    }
}

/** Minimal libsu shell double: enough to be a distinct `Shell` instance in routing tests. */
private class FakeShell : Shell() {
    override fun isAlive(): Boolean = true

    override fun execTask(task: Shell.Task) = throw UnsupportedOperationException("execTask")

    override fun submitTask(task: Shell.Task) = throw UnsupportedOperationException("submitTask")

    override fun newJob(): Shell.Job = throw UnsupportedOperationException("newJob")

    override fun getStatus(): Int = Shell.ROOT_SHELL

    override fun waitAndClose(timeout: Long, unit: TimeUnit): Boolean = false

    override fun close() = Unit
}

