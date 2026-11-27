package dev.maplesu.manager.workmode.service

import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.runtime.RootExecResult
import dev.maplesu.manager.workmode.switch.HookResult
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ModeSwitchRootSessionTest {
    private val token = ByteArray(32) { it.toByte() }

    @Test fun authenticatedRequestReachesTheHandler() {
        val request = ByteArrayOutputStream()
        ModeSwitchRootProtocol.request(request, token, ModeSwitchRootProtocol.EXEC, "printf ok")
        val response = ByteArrayOutputStream()
        var received = ""
        ModeSwitchRootProtocol.serve(10345, 10345, request.toByteArray().inputStream(), response, token) { op, payload ->
            assertEquals(ModeSwitchRootProtocol.EXEC, op)
            received = payload
            ModeSwitchRootProtocol.Reply(7, "result", "detail")
        }
        assertEquals("printf ok", received)
        assertEquals(ModeSwitchRootProtocol.Reply(7, "result", "detail"),
            ModeSwitchRootProtocol.response(response.toByteArray().inputStream()))
    }

    @Test fun anotherUidIsRejectedBeforeAnyInputRead() {
        val response = ByteArrayOutputStream()
        ModeSwitchRootProtocol.serve(10346, 10345, byteArrayOf().inputStream(), response, token) { _, _ ->
            fail("handler must not be called")
            ModeSwitchRootProtocol.Reply(0)
        }
        assertEquals(0, response.size())
    }

    @Test fun wrongTokenDoesNotExecute() {
        val request = ByteArrayOutputStream()
        ModeSwitchRootProtocol.request(request, ByteArray(32), ModeSwitchRootProtocol.EXEC, "write")
        val response = ByteArrayOutputStream()
        ModeSwitchRootProtocol.serve(10345, 10345, request.toByteArray().inputStream(), response, token) { _, _ ->
            fail("handler must not be called")
            ModeSwitchRootProtocol.Reply(0)
        }
        assertEquals(0, response.size())
    }

    @Test fun oversizedAuthenticatedPayloadIsRejectedBeforeAllocation() {
        val request = ByteArrayOutputStream()
        DataOutputStream(request).apply {
            writeInt(ModeSwitchRootProtocol.MAGIC)
            writeInt(ModeSwitchRootProtocol.VERSION)
            write(token)
            writeByte(ModeSwitchRootProtocol.EXEC)
            writeInt(Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModeSwitchRootProtocol.serve(10345, 10345, request.toByteArray().inputStream(), ByteArrayOutputStream(), token) { _, _ ->
                fail("handler must not be called")
                ModeSwitchRootProtocol.Reply(0)
            }
        }
    }

    @Test fun reconnectingToAnExistingHelperDoesNotUseUltraRoot() {
        var launches = 0
        val channel = ModeSwitchRootChannel({ token.copyOf() }, { connection(ModeSwitchRootProtocol.Reply(0, "0")) },
            { launches++; rootResult() }, {})
        assertTrue(channel.ensureReady().ok)
        assertTrue(channel.ensureReady().ok)
        assertEquals(0, launches)
    }

    @Test fun missingHelperIsLaunchedOnlyByEnsureReady() {
        var active = false
        var launches = 0
        val channel = ModeSwitchRootChannel({ token.copyOf() }, {
            if (!active) throw IOException()
            connection(ModeSwitchRootProtocol.Reply(0, "0"))
        }, { launches++; active = true; rootResult() }, {})
        assertTrue(channel.ensureReady().ok)
        assertEquals(1, launches)
        assertTrue(channel.ensureReady().ok)
        assertEquals(1, launches)
    }

    @Test fun quarantinedHelperIsNotRelaunchedByReadiness() {
        var launches = 0
        val channel = ModeSwitchRootChannel({ token.copyOf() }, {
            connection(ModeSwitchRootProtocol.Reply(-1, detail = ModeSwitchRootProtocol.UNCERTAIN))
        }, { launches++; rootResult() }, {})
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, channel.ensureReady().code)
        assertEquals(0, launches)
    }

    @Test fun readinessHasShortTimeoutAndWritesKeepTheirOwnTimeout() {
        val timeouts = mutableListOf<Int>()
        val channel = ModeSwitchRootChannel({ token.copyOf() }, { timeoutMillis ->
            timeouts += timeoutMillis
            connection(ModeSwitchRootProtocol.Reply(0, "0"))
        }, { rootResult() }, {})
        assertTrue(channel.ensureReady().ok)
        assertTrue(channel.execute("write").ok)
        assertEquals(listOf(1_000, 180_000), timeouts)
    }

    @Test fun pinPreparesOnceAndKeepsUsingTheHelperAfterSdkRemoval() {
        var preparations = 0
        val calls = mutableListOf<String>()
        val pin = ModeSwitchRootPin({ preparations++; HookResult.OK },
            { calls += it; rootResult() }, { calls += "uninstall"; HookResult.OK })
        assertTrue(pin.ensureReady().ok)
        assertTrue(pin.execute("archive").ok)
        assertTrue(pin.uninstallUltra().ok)
        assertTrue(pin.execute("restore").ok)
        assertEquals(1, preparations)
        assertEquals(listOf("archive", "uninstall", "restore"), calls)
    }

    @Test fun pinBlocksRecoveryAndRelaunchAfterLostWriteReply() {
        var preparations = 0
        var executions = 0
        var uninstalls = 0
        val pin = ModeSwitchRootPin({ preparations++; HookResult.OK },
            { executions++; rootResult().copy(code = -1, detail = ModeSwitchRootProtocol.UNCERTAIN) },
            { uninstalls++; HookResult.OK })
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, pin.execute("write").detail)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, pin.execute("rollback").detail)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, pin.ensureReady().code)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, pin.uninstallUltra().code)
        assertEquals(1, preparations)
        assertEquals(1, executions)
        assertEquals(0, uninstalls)
    }

    @Test fun pinDoesNotReplaceAnUnavailablePinnedHelper() {
        var preparations = 0
        var executions = 0
        val pin = ModeSwitchRootPin({ preparations++; HookResult.OK },
            { executions++; rootResult().copy(code = -1, detail = ModeSwitchRootProtocol.UNAVAILABLE) },
            { HookResult.OK })
        assertEquals(ModeSwitchRootProtocol.UNAVAILABLE, pin.execute("read").detail)
        assertEquals(ModeSwitchRootProtocol.UNAVAILABLE, pin.execute("rollback").detail)
        assertEquals(1, preparations)
        assertEquals(1, executions)
    }

    @Test fun ordinaryShellFailureStillAllowsVerifiedRollback() {
        var executions = 0
        val pin = ModeSwitchRootPin({ HookResult.OK },
            { executions++; if (executions == 1) rootResult().copy(code = 7) else rootResult() },
            { HookResult.OK })
        assertFalse(pin.execute("write").ok)
        assertTrue(pin.execute("rollback").ok)
        assertEquals(2, executions)
    }

    @Test fun initialReadinessFailureMayBeRetriedBeforePinning() {
        var preparations = 0
        val pin = ModeSwitchRootPin({
            preparations++
            if (preparations == 1) HookResult.failed(ModeSwitchRootProtocol.UNAVAILABLE) else HookResult.OK
        }, { rootResult() }, { HookResult.OK })
        assertFalse(pin.ensureReady().ok)
        assertTrue(pin.execute("archive").ok)
        assertEquals(2, preparations)
    }

    @Test fun uncertainSdkResultBlocksAConcurrentRecovery() {
        val entered = CountDownLatch(1)
        val complete = CountDownLatch(1)
        var executions = 0
        val pin = ModeSwitchRootPin({ HookResult.OK }, { executions++; rootResult() }, {
            entered.countDown()
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            HookResult.failed(ModeSwitchRootProtocol.UNCERTAIN)
        })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val removed = workers.submit<HookResult> { pin.uninstallUltra() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val rollback = workers.submit<RootExecResult> { pin.execute("rollback") }
            complete.countDown()
            assertEquals(ModeSwitchRootProtocol.UNCERTAIN, removed.get(5, TimeUnit.SECONDS).code)
            assertEquals(ModeSwitchRootProtocol.UNCERTAIN, rollback.get(5, TimeUnit.SECONDS).detail)
            assertEquals(0, executions)
        } finally {
            complete.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun managerRejectsNonRootPeerBeforeSendingCredentials() {
        val output = ByteArrayOutputStream()
        val channel = ModeSwitchRootChannel({ token.copyOf() }, {
            connection(ModeSwitchRootProtocol.Reply(0), peerUid = 10345, output = output)
        }, { rootResult() }, {})
        assertFalse(channel.execute("write").ok)
        assertEquals(0, output.size())
    }

    @Test fun lostWriteReplyIsUncertainAndNeverRelaunchedOrRetried() {
        var connections = 0
        var launches = 0
        val channel = ModeSwitchRootChannel({ token.copyOf() }, {
            connections++
            object : ModeSwitchRootConnection {
                override val peerUid = 0
                override val input = ByteArrayInputStream(byteArrayOf())
                override val output = ByteArrayOutputStream()
                override fun close() = Unit
            }
        }, { launches++; rootResult() }, {})
        val result = channel.execute("write")
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, result.detail)
        assertEquals(1, connections)
        assertEquals(0, launches)
    }

    @Test fun acceptedUninstallRebootsEvenWhenManagerDisconnectsBeforeReply() {
        val calls = mutableListOf<String>()
        val request = ByteArrayOutputStream()
        ModeSwitchRootProtocol.request(request, token, ModeSwitchRootProtocol.UNINSTALL, "")
        val disconnected = object : java.io.OutputStream() {
            override fun write(value: Int) { throw IOException("manager exited") }
        }
        val requests = ModeSwitchRootRequests({ ModeSwitchRootProtocol.Reply(0) }, {
            ModeSwitchRootProtocol.uninstallAndReboot(
                { calls += "sdk-uninstall"; ModeSwitchRootProtocol.Reply(0) },
                { calls += "reboot"; ModeSwitchRootProtocol.Reply(0) },
            )
        }, isHeld = { true })
        assertThrows(IOException::class.java) {
            ModeSwitchRootProtocol.serve(10345, 10345, request.toByteArray().inputStream(),
                disconnected, token, requests::handle)
        }
        assertEquals(listOf("sdk-uninstall", "reboot"), calls)
    }

    @Test fun failedSdkRemovalDoesNotRebootAndFailedRebootIsNotAcknowledged() {
        val failed = ModeSwitchRootProtocol.Reply(-1, detail = "SDK_FAILED")
        assertEquals(failed, ModeSwitchRootProtocol.uninstallAndReboot({ failed }, {
            fail("failed SDK must not reboot"); ModeSwitchRootProtocol.Reply(0)
        }))
        val rebootFailure = ModeSwitchRootProtocol.Reply(1, detail = "REBOOT_FAILED")
        assertEquals(rebootFailure, ModeSwitchRootProtocol.uninstallAndReboot(
            { ModeSwitchRootProtocol.Reply(0) }, { rebootFailure }))
    }

    @Test fun channelPreservesRootHelpersRebootAcknowledgement() {
        val channel = ModeSwitchRootChannel({ token.copyOf() }, {
            connection(ModeSwitchRootProtocol.Reply(0, ModeSwitchRootProtocol.REBOOT_REQUESTED))
        }, { rootResult() }, {})
        val result = channel.uninstallUltra()
        assertTrue(result.ok)
        assertEquals(ModeSwitchRootProtocol.REBOOT_REQUESTED, result.code)
    }

    @Test fun helperRunsAnotherExecAfterSuccessfulSdkRemoval() {
        val calls = mutableListOf<String>()
        val requests = ModeSwitchRootRequests({ calls += "exec:$it"; ModeSwitchRootProtocol.Reply(0, "written") },
            { calls += "uninstall"; ModeSwitchRootProtocol.Reply(0) }, isHeld = { true })
        assertEquals(0, requests.handle(ModeSwitchRootProtocol.UNINSTALL, "").code)
        assertEquals("written", requests.handle(ModeSwitchRootProtocol.EXEC, "restore boot").output)
        assertEquals(listOf("uninstall", "exec:restore boot"), calls)
    }

    @Test fun uncertainSdkCallQuarantinesLaterMutationAndReadiness() {
        var executed = false
        val requests = ModeSwitchRootRequests({ executed = true; ModeSwitchRootProtocol.Reply(0) },
            { ModeSwitchRootProtocol.Reply(-1, detail = ModeSwitchRootProtocol.UNCERTAIN) }, isHeld = { true })
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN,
            requests.handle(ModeSwitchRootProtocol.UNINSTALL, "").detail)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN,
            requests.handle(ModeSwitchRootProtocol.EXEC, "rollback").detail)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, requests.handle(ModeSwitchRootProtocol.PING, "").detail)
        assertFalse(executed)
    }

    @Test fun uninstallUsesEmptyPayloadAndRejectsUnexpectedData() {
        var uninstallCalls = 0
        val requests = ModeSwitchRootRequests({ fail("payload must not become a shell command"); ModeSwitchRootProtocol.Reply(0) },
            { uninstallCalls++; ModeSwitchRootProtocol.Reply(0) }, isHeld = { true })
        assertEquals(ModeSwitchRootProtocol.BAD_REQUEST, requests.handle(ModeSwitchRootProtocol.UNINSTALL, "short").detail)
        assertEquals(0, uninstallCalls)
        assertEquals(0, requests.handle(ModeSwitchRootProtocol.UNINSTALL, "").code)
        assertEquals(1, uninstallCalls)
    }

    @Test fun uninstallRequiresALeaseBeforeCallingTheSdk() {
        var uninstalls = 0
        val requests = ModeSwitchRootRequests({ ModeSwitchRootProtocol.Reply(0) },
            { uninstalls++; ModeSwitchRootProtocol.Reply(0) })
        assertEquals(ModeSwitchRootProtocol.LEASE_REQUIRED,
            requests.handle(ModeSwitchRootProtocol.UNINSTALL, "").detail)
        assertEquals(0, uninstalls)
    }

    @Test fun leaseOperationsUseValidatedIdsAndTheSameSharedLease() {
        var held = false
        var id = ""
        val requests = ModeSwitchRootRequests({ ModeSwitchRootProtocol.Reply(0) },
            { ModeSwitchRootProtocol.Reply(0) },
            acquire = { id = it; held = true; HookResult.OK },
            release = { assertEquals(id, it); held = false; HookResult.OK }, isHeld = { held })
        assertEquals(ModeSwitchRootProtocol.BAD_REQUEST, requests.handle(ModeSwitchRootProtocol.ACQUIRE, "invalid").detail)
        assertEquals(0, requests.handle(ModeSwitchRootProtocol.ACQUIRE, SWITCH_ID).code)
        assertEquals(SWITCH_ID, id)
        assertEquals(0, requests.handle(ModeSwitchRootProtocol.UNINSTALL, "").code)
        assertEquals(0, requests.handle(ModeSwitchRootProtocol.RELEASE, SWITCH_ID).code)
        assertFalse(held)
        assertEquals(ModeSwitchRootProtocol.LEASE_REQUIRED,
            requests.handle(ModeSwitchRootProtocol.UNINSTALL, "").detail)
    }

    @Test fun uncertainSdkCallRetainsLeaseAndBlocksItsRelease() {
        var held = false
        var releases = 0
        val requests = ModeSwitchRootRequests({ ModeSwitchRootProtocol.Reply(0) },
            { ModeSwitchRootProtocol.Reply(-1, detail = ModeSwitchRootProtocol.UNCERTAIN) },
            acquire = { held = true; HookResult.OK }, release = { releases++; held = false; HookResult.OK },
            isHeld = { held })
        assertEquals(0, requests.handle(ModeSwitchRootProtocol.ACQUIRE, SWITCH_ID).code)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN,
            requests.handle(ModeSwitchRootProtocol.UNINSTALL, "").detail)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, requests.handle(ModeSwitchRootProtocol.RELEASE, SWITCH_ID).detail)
        assertEquals(0, releases)
        assertTrue(held)
    }

    @Test fun channelLeaseRequestsPreserveTheUuidAndRejectBadIdsBeforeConnecting() {
        val output = ByteArrayOutputStream()
        var connections = 0
        val channel = ModeSwitchRootChannel({ token.copyOf() }, {
            connections++; connection(ModeSwitchRootProtocol.Reply(0), output = output)
        }, { rootResult() }, {})
        assertEquals(ModeSwitchRootProtocol.BAD_REQUEST, channel.acquire("invalid").code)
        assertEquals(0, connections)
        assertTrue(channel.acquire(SWITCH_ID).ok)
        var received = ""
        ModeSwitchRootProtocol.serve(10345, 10345, output.toByteArray().inputStream(), ByteArrayOutputStream(), token) { op, payload ->
            assertEquals(ModeSwitchRootProtocol.ACQUIRE, op)
            received = payload
            ModeSwitchRootProtocol.Reply(0)
        }
        assertEquals(SWITCH_ID, received)
    }

    @Test fun pinnedUncertaintyDoesNotSendLeaseReleaseOrReacquire() {
        var releases = 0
        var acquires = 0
        val pin = ModeSwitchRootPin({ HookResult.OK }, { rootResult() },
            { HookResult.failed(ModeSwitchRootProtocol.UNCERTAIN) },
            acquireLease = { acquires++; HookResult.OK }, releaseLease = { releases++; HookResult.OK })
        assertTrue(pin.acquire(SWITCH_ID).ok)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, pin.uninstallUltra().code)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, pin.release(SWITCH_ID).code)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, pin.acquire(SWITCH_ID).code)
        assertEquals(0, releases)
        assertEquals(1, acquires)
    }

    @Test fun launchContainsOnlyValidatedPublicArgumentsAndNoSecret() {
        val launch = ModeSwitchRootLaunch(10345, "/data/app/~~ABC/dev.maplesu.manager-DEF/base.apk",
            "/data/app/~~ABC/dev.maplesu.manager-DEF/lib/arm64", "/data/user/0/dev.maplesu.manager/files/mode-switch-root.token")
        val script = launch.script()
        assertTrue(script.contains("CLASSPATH='/data/app/~~ABC/dev.maplesu.manager-DEF/base.apk'"))
        assertTrue(script.contains("maplesu-mode-switch-10345"))
        assertFalse(script.contains("A".repeat(48)))
        assertThrows(IllegalArgumentException::class.java) { launch.copy(nativeDirectory = "/data/app/evil;id").script() }
        assertThrows(IllegalArgumentException::class.java) { launch.copy(tokenFile = "/data/user/0/another/files/key").script() }
        assertThrows(IllegalArgumentException::class.java) { launch.copy(managerUid = 0).script() }
    }

    @Test fun primaryUserCanonicalDataAliasCanStartTheSameRootSession() {
        val launch = ModeSwitchRootLaunch(10481, "/data/app/~~ABC/dev.maplesu.manager-DEF/base.apk",
            "/data/app/~~ABC/dev.maplesu.manager-DEF/lib/arm64",
            "/data/data/dev.maplesu.manager/files/mode-switch-root.token")
        launch.validate()
        assertTrue(launch.script().contains("'/data/data/dev.maplesu.manager/files/mode-switch-root.token'"))
    }

    @Test fun canonicalDataAliasDoesNotAuthorizeAnotherAndroidUserOrPackage() {
        val launch = ModeSwitchRootLaunch(110481, "/data/app/~~ABC/dev.maplesu.manager-DEF/base.apk",
            "/data/app/~~ABC/dev.maplesu.manager-DEF/lib/arm64",
            "/data/user/1/dev.maplesu.manager/files/mode-switch-root.token")
        launch.validate()
        assertThrows(IllegalArgumentException::class.java) {
            launch.copy(tokenFile = "/data/data/dev.maplesu.manager/files/mode-switch-root.token").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            launch.copy(managerUid = 10481,
                tokenFile = "/data/data/other.manager/files/mode-switch-root.token").validate()
        }
    }

    @Test fun rootCommandRunnerUsesStdinAndPreservesExitCode() {
        var process: FakeProcess? = null
        val runner = ModeSwitchRootCommandRunner(start = { FakeProcess("hello", 17).also { process = it } })
        val reply = runner.execute("printf hello")
        assertEquals(17, reply.code)
        assertEquals("hello", reply.output)
        assertEquals("printf hello\n", process!!.written.toString("UTF-8"))
    }

    @Test fun rootCommandTimeoutIsUncertain() {
        val process = FakeProcess("", 0, complete = false)
        val reply = ModeSwitchRootCommandRunner({ process }, 1).execute("write")
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, reply.detail)
        assertTrue(process.destroyed)
    }

    @Test fun outputLimitNeverProducesAFalseSuccess() {
        val output = "a".repeat(ModeSwitchRootProtocol.MAX_OUTPUT_BYTES + 1)
        val reply = ModeSwitchRootCommandRunner({ FakeProcess(output, 0) }).execute("read")
        assertTrue(reply.code != 0)
        assertEquals("MODE_SWITCH_ROOT_OUTPUT_LIMIT", reply.detail)
        assertTrue(reply.output.toByteArray().size <= ModeSwitchRootProtocol.MAX_OUTPUT_BYTES)
    }

    private fun rootResult() = RootExecResult(RootBackend.ULTRA_ENV, 0, "")

    private fun connection(reply: ModeSwitchRootProtocol.Reply, peerUid: Int = 0,
                           output: ByteArrayOutputStream = ByteArrayOutputStream()): ModeSwitchRootConnection {
        val response = ByteArrayOutputStream().also { ModeSwitchRootProtocol.respond(it, reply) }
        return object : ModeSwitchRootConnection {
            override val peerUid = peerUid
            override val input = response.toByteArray().inputStream()
            override val output = output
            override fun close() = Unit
        }
    }

    private class FakeProcess(text: String, private val code: Int, private val complete: Boolean = true) : Process() {
        val written = ByteArrayOutputStream()
        var destroyed = false
        private val captured = text.toByteArray().inputStream()
        override fun getOutputStream() = written
        override fun getInputStream() = captured
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = code
        override fun waitFor(timeout: Long, unit: TimeUnit) = complete
        override fun exitValue() = code
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
    }

    private companion object { const val SWITCH_ID = "0f8fad5b-d9cb-469f-a165-70867728950e" }
}
