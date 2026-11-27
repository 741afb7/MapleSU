package dev.maplesu.manager.runtime

import android.content.Context
import android.system.Os
import dev.maplesu.manager.runtime.ultra.*
import dev.maplesu.manager.workmode.*
import java.io.File
import java.io.FileDescriptor
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow

/** Real transport/receipt code; Android, admission, libsu and FD endpoints are fixtures. */
class UltraTransportBoundaryTest {
    private val context = Context(requireNotNull(System.getProperty("maplesu.transport.fixture")))

    @Before fun reset() {
        ModeRuntime.active = WorkMode.LITE
        ModeRuntime.activation = ModeActivation.Active()
        UltraEnvironmentAutoSetup.boot = "boot-one"
        UltraEnvironmentProbe.backend = true
        UltraEnvironmentProbe.owner = UltraRuntimeClient.ManagerOwnerIdentity(10123, 1, "fixture")
        UltraSdkCredentials.generation = 0
        UltraSdkSession.session = UltraBootSdkSession("boot-one")
        ProcessSuExec.probe = { true }
        MapleSuCredentialStore.loads = 0
        MapleSuCredentialStore.credential = ByteArray(48) { 65 }
        NativeSessionProcess.replies = byteArrayOf('R'.code.toByte())
        NativeSessionProcess.last = null
        Os.uid = 10123
        Os.closed = 0
    }

    private fun collect(process: Process, input: ByteArray): Pair<Int, String> {
        try {
            process.outputStream.use { it.write(input) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            return process.exitValue() to output
        } finally {
            process.destroyForcibly()
            runCatching { process.errorStream.close() }
        }
    }

    @Test fun binaryAndEmptyInputReachTheCommandWithoutAFrameInBothNamespaces() {
        for (global in listOf(false, true)) {
            for (input in listOf(byteArrayOf(), ByteArray(65536) { it.toByte() })) {
                val (code, output) = collect(MapleSuCommandExecutor(context).start(
                    listOf("/fixture/program", "literal ; 你好"), globalMnt = global), input)
                assertEquals(0, code)
                assertTrue(output.contains("MODE:${if (global) "--ultra-global" else "--ultra"}\n"))
                assertTrue(output.contains("INPUT:${Base64.getEncoder().encodeToString(input)}\n"))
                assertTrue(output.contains("ARGS:/fixture/program|literal ; 你好"))
            }
        }
        assertEquals(0, MapleSuCredentialStore.loads)
    }

    @Test fun moduleEnvironmentAndStdinRemainSeparateFromAdmission() {
        val (code, output) = collect(MapleSuCommandExecutor(context).startModule(
            listOf("/fixture/program"), arrayOf("MODULE_MARKER=literal")), byteArrayOf(1, 0, 2))
        assertEquals(0, code)
        assertTrue(output.contains("MODE:--ultra\n"))
        assertTrue(output.contains("INPUT:AQAC\n"))
        assertEquals(0, MapleSuCredentialStore.loads)
    }

    @Test fun admissionFailureKeepsTheRealExitAndDoesNotReplayAnotherTransport() {
        val (code, output) = collect(MapleSuCommandExecutor(context).start(
            listOf("/fixture/program"), environment = mapOf("FIXTURE_DENIED" to "1")), byteArrayOf())
        assertEquals(69, code)
        assertEquals(1, output.lineSequence().count { it == "MODE:--ultra" })
        assertEquals(0, MapleSuCredentialStore.loads)
    }

    @Test fun directOperationUsesNoAdmissionInputAndPreservesExit23() {
        val result = DirectRoot.run(context, "fixture-exit-23")
        assertEquals(23, result.code)
        val echo = DirectRoot.run(context, "fixture-report")
        assertEquals(0, echo.code)
        assertTrue(echo.output.contains("MODE:--ultra\nINPUT:\n"))
        assertEquals(0, MapleSuCredentialStore.loads)
    }

    @Test fun readinessWithoutACurrentOwnerStaysUnknownWithoutExecutingTheProbe() {
        UltraEnvironmentProbe.backend = false
        MapleSuRuntime.invalidate()
        val trace = File(context.libraryDirectory, "invocations.log")
        val before = trace.length()
        assertFalse(MapleSuRuntime.refresh(context))
        assertEquals(before, trace.length())
        assertEquals(0, MapleSuCredentialStore.loads)
        // The same fixture executor would succeed for an explicit operation.
        assertEquals("0", DirectRoot.run(context, "/system/bin/id -u").output.trim())
        assertTrue(trace.length() > before)
    }

    @Test fun interruptedDirectWaitClosesTheProcessAndPreservesTheInterrupt() {
        val pidFile = File(context.libraryDirectory, "wait.pid")
        assertFalse(pidFile.exists())
        var result: DirectRoot.Result? = null
        var interrupted = false
        val caller = Thread {
            result = DirectRoot.run(context, "fixture-interrupted-wait")
            interrupted = Thread.currentThread().isInterrupted
        }
        caller.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!pidFile.isFile && System.nanoTime() < deadline) Thread.sleep(10)
        var child: ProcessHandle? = null
        try {
            assertTrue("Native wait fixture did not start", pidFile.isFile)
            val pid = pidFile.readText().toLong()
            child = ProcessHandle.of(pid).orElse(null)
            assertNotNull(child)
            caller.interrupt()
            caller.join(5_000)
            assertFalse("Direct wait retained its caller", caller.isAlive)
            assertTrue("InterruptedException lost its interrupt flag", interrupted)
            assertEquals(-1, result?.code)
            assertEquals("direct operation failed: InterruptedException", result?.output)
            val retired = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child?.isAlive == true && System.nanoTime() < retired) Thread.sleep(10)
            assertFalse("Failed direct operation retained its native process", child?.isAlive == true)
        } finally {
            caller.interrupt()
            if (child?.isAlive == true) child.destroyForcibly()
            caller.join(5_000)
            pidFile.delete()
        }
    }

    @Test fun malformedArgumentsAreRejectedBeforeLaunch() {
        for (argv in listOf(emptyList(), listOf("relative"), listOf("/program", "bad\u0000argument"))) {
            assertTrue(runCatching { MapleSuCommandExecutor(context).start(argv) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun supervisedUltraUsesAThenRAndLeavesBusinessInputUnmodified() {
        for (global in listOf(false, true)) {
            val process = MapleSuSupervisedProcess.start(context, listOf("/program"), global)
            val native = checkNotNull(NativeSessionProcess.last)
            assertEquals(if (global) "--ultra-global-session" else "--ultra-session", NativeSessionProcess.argv[1])
            assertArrayEquals(byteArrayOf('A'.code.toByte()), native.control.outputStream.toByteArray())
            assertEquals(0, native.stdin.size())
            process.process.outputStream.write(byteArrayOf(1, 0, 2))
            assertArrayEquals(byteArrayOf(1, 0, 2), native.stdin.toByteArray())
            assertEquals(listOf(20_000, 0), native.control.timeouts)
            assertEquals(23, process.process.waitFor())
            process.cancel()
            assertTrue(native.control.closed)
        }
        assertEquals(0, MapleSuCredentialStore.loads)
    }

    @Test fun ultraSuStartsWithTheRequestAndTransfersThreeDescriptors() {
        val process = MapleSuSupervisedProcess.startSu(context, NativeSuRequest(terminals = 1), Array(3) { FileDescriptor() })
        val native = checkNotNull(NativeSessionProcess.last)
        assertEquals("--ultra-su", NativeSessionProcess.argv[1])
        assertArrayEquals(byteArrayOf(11, 12, 13, 14), native.stdin.toByteArray())
        assertEquals(3, native.control.descriptors)
        assertNotNull(process.openTerminal())
        process.cancel()
        assertTrue(native.control.closed)
        assertEquals(1, Os.closed)
        assertEquals(0, MapleSuCredentialStore.loads)
    }

    @Test fun rootSuRetainsPThenGThenRWithoutAnyKeyPrefix() {
        Os.uid = 0
        NativeSessionProcess.replies = byteArrayOf('P'.code.toByte(), 'R'.code.toByte())
        val process = MapleSuSupervisedProcess.prepareRootSu(context, NativeSuRequest(), Array(3) { FileDescriptor() })
        val native = checkNotNull(NativeSessionProcess.last)
        assertEquals("--root-su", NativeSessionProcess.argv[1])
        assertArrayEquals(byteArrayOf(11, 12, 13, 14), native.stdin.toByteArray())
        assertArrayEquals(byteArrayOf('A'.code.toByte()), native.control.outputStream.toByteArray())
        process.commitNoWait()
        process.commitNoWait()
        process.awaitStarted()
        assertArrayEquals(byteArrayOf('A'.code.toByte(), 'G'.code.toByte()), native.control.outputStream.toByteArray())
        process.cancel()
        assertTrue(native.control.closed)
        assertEquals(0, MapleSuCredentialStore.loads)
    }

    @Test fun rootNamespaceModesAndCallerIdentityGuardArePreserved() {
        assertEquals("SU_ROOT_OWNER_REQUIRED", runCatching {
            MapleSuSupervisedProcess.startRoot(context, listOf("/program"))
        }.exceptionOrNull()?.message)
        Os.uid = 0
        for (global in listOf(false, true)) {
            val process = MapleSuSupervisedProcess.startRoot(context, listOf("/program"), global)
            assertEquals(if (global) "--root-global-session" else "--root-session", NativeSessionProcess.argv[1])
            assertEquals(0, checkNotNull(NativeSessionProcess.last).stdin.size())
            process.cancel()
        }
    }

    @Test fun failedNativeHandshakeRetiresTheOriginalProcess() {
        NativeSessionProcess.replies = byteArrayOf('X'.code.toByte())
        assertEquals("MAPLESU_SESSION_ROOT_NOT_ACCEPTED", runCatching {
            MapleSuSupervisedProcess.start(context, listOf("/program"))
        }.exceptionOrNull()?.message)
        val native = checkNotNull(NativeSessionProcess.last)
        assertTrue(native.retired)
        assertTrue(native.control.closed)
    }

    private fun publication(): Any {
        val method = RuntimeDeployment::class.java.declaredMethods.first { it.name == "publicationSession" }
        method.isAccessible = true
        return checkNotNull(method.invoke(RuntimeDeployment, context))
    }

    private fun current(expected: Any): Boolean {
        val generation = RuntimeDeployment::class.java.getDeclaredField("publicationGeneration")
        generation.isAccessible = true
        (generation.get(RuntimeDeployment) as AtomicLong).set(7)
        val method = RuntimeDeployment::class.java.declaredMethods.first { it.name == "publicationCurrent" }
        method.isAccessible = true
        return method.invoke(RuntimeDeployment, context, RuntimePublicationRetry(7), expected) as Boolean
    }

    @Test fun pendingInstallObservationDoesNotChangePublicationIdentity() {
        val expected = publication()
        assertTrue(current(expected))
        UltraSdkSession.session = UltraBootSdkSession("boot-one", installSubmitted = true)
        assertTrue(current(expected))
        assertEquals(0, MapleSuCredentialStore.loads)
    }

    @Test fun oldPublicationIsRejectedAfterBootActivationOwnerOrRemovalChanges() {
        val boot = publication()
        UltraEnvironmentAutoSetup.boot = "boot-two"
        assertFalse(current(boot))
        reset()
        val activation = publication()
        ModeRuntime.activation = ModeActivation.Active()
        assertFalse(current(activation))
        reset()
        val owner = publication()
        UltraEnvironmentProbe.owner = UltraEnvironmentProbe.owner.copy(epoch = 2)
        assertFalse(current(owner))
        reset()
        val removal = publication()
        UltraSdkCredentials.generation++
        assertFalse(current(removal))
    }

    @Test fun hookPublicationUsesTheSameBootAndCurrentOwnerFence() {
        val status = HookSuCompatibility.publish(context,
            SuEntryMode.Mode.TRADITIONAL_COMPAT, prepareEnvironment = false)
        assertEquals("/system/bin/su", status.standardPath)
        val state = RuntimeDeployment::class.java.getDeclaredField("_status")
        state.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = state.get(RuntimeDeployment) as MutableStateFlow<RuntimeDeployment.Status>
        flow.value = status
        assertTrue(HookSuCompatibility.traditionalReady(context))
        UltraSdkCredentials.generation++
        assertFalse(HookSuCompatibility.traditionalReady(context))
        ProcessSuExec.probe = { UltraSdkCredentials.generation++; true }
        assertEquals("SDK_SESSION_CHANGED", runCatching {
            HookSuCompatibility.publish(context, SuEntryMode.Mode.TRADITIONAL_COMPAT, prepareEnvironment = false)
        }.exceptionOrNull()?.message)
        assertEquals(0, MapleSuCredentialStore.loads)
    }

    @Test fun nonTerminalSuAcceptsSignalsAfterTheNativeStartedReceipt() {
        val process = MapleSuSupervisedProcess.startSu(context, NativeSuRequest(), Array(3) { FileDescriptor() })
        process.signal(android.system.OsConstants.SIGTERM)
        val control = checkNotNull(NativeSessionProcess.last).control
        assertArrayEquals(byteArrayOf('A'.code.toByte(), 'S'.code.toByte(), 0, 0, 0, 15),
            control.outputStream.toByteArray())
        process.cancel()
        assertTrue(control.closed)
    }

    @Test fun legacyClientLocatorIsOnlyAPersistedNonSecretName() {
        val first = RuntimeDeployment.clientPath(context)
        MapleSuCredentialStore.credential = ByteArray(48) { 66 }
        assertEquals(first, RuntimeDeployment.clientPath(context))
        assertTrue(first.matches(Regex("/data/[0-9a-f]{16}/su")))
        assertEquals(0, MapleSuCredentialStore.loads)
    }
}
