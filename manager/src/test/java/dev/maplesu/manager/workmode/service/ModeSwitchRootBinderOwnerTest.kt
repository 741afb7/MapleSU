package dev.maplesu.manager.workmode.service

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class ModeSwitchRootBinderOwnerTest {
    private val token = ByteArray(32) { it.toByte() }

    @Test fun mutationBeforeReadinessDoesNotBindOrOpenAProvider() {
        val fixture = Fixture()
        assertEquals(ModeSwitchRootProtocol.UNAVAILABLE, fixture.channel.execute("write").detail)
        assertEquals(0, fixture.bindings)
        assertEquals(0, fixture.service.opens)
    }

    @Test fun failedReadinessReportsTheFixedTransportStageWithoutPayload() {
        val failures = mutableListOf<String>()
        val channel = ModeSwitchRootChannel({ token.copyOf() }, { throw IOException("private") },
            { throw IllegalStateException("private") }, {}, { stage, error ->
                failures += "$stage:${error.javaClass.simpleName}"
            })
        assertEquals(ModeSwitchRootProtocol.UNAVAILABLE, channel.ensureReady().code)
        assertEquals(listOf("connect:IOException", "launch:IllegalStateException"), failures)
    }

    @Test fun readinessBindsOnceAndKeepsTheDaemonAfterTheProviderWasRemoved() {
        val fixture = Fixture()
        assertTrue(fixture.channel.ensureReady().ok)
        fixture.providerAvailable = false
        assertTrue(fixture.channel.acquire(ID).ok)
        assertTrue(fixture.channel.uninstallUltra().ok)
        assertTrue(fixture.channel.execute("restore").ok)
        assertTrue(fixture.channel.release(ID).ok)
        assertEquals(1, fixture.bindings)
        assertEquals(listOf("uninstall", "restore"), fixture.service.calls)
    }

    @Test fun deadBinderDoesNotTriggerAReplacementForAMutation() {
        val fixture = Fixture()
        assertTrue(fixture.channel.ensureReady().ok)
        fixture.service.alive = false
        assertEquals(ModeSwitchRootProtocol.UNAVAILABLE, fixture.channel.execute("write").detail)
        assertEquals(1, fixture.bindings)
        assertEquals(1, fixture.service.opens)
        assertTrue(fixture.service.calls.isEmpty())
    }

    @Test fun aLostMutationResponseIsNotSentAgainOrBoundAgain() {
        val fixture = Fixture()
        assertTrue(fixture.channel.ensureReady().ok)
        val pin = ModeSwitchRootPin(fixture.channel::ensureReady, fixture.channel::execute,
            fixture.channel::uninstallUltra)
        assertTrue(pin.ensureReady().ok)
        fixture.service.dropReply = true
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, pin.execute("write").detail)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, pin.execute("rollback").detail)
        assertEquals(listOf("write"), fixture.service.calls)
        assertEquals(1, fixture.bindings)
    }

    @Test fun anotherManagerSessionSeesTheSameQuarantinedDaemon() {
        val service = Service()
        val first = Fixture(service)
        assertTrue(first.channel.ensureReady().ok)
        assertTrue(first.channel.acquire(ID).ok)
        service.uncertainUninstall = true
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, first.channel.uninstallUltra().code)
        val restarted = Fixture(service)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, restarted.channel.ensureReady().code)
        assertEquals(ModeSwitchRootProtocol.UNCERTAIN, restarted.channel.execute("rollback").detail)
        assertEquals(listOf("uninstall"), service.calls)
        assertTrue(service.held)
    }

    @Test fun onlyTheManagersPrivateRegularTokenDescriptorIsAccepted() {
        fun metadata(regular: Boolean = true, uid: Int = UID, mode: Int = 0x8180,
                     links: Long = 1, size: Long = 32) =
            ModeSwitchRootTokenMetadata.requireValid(regular, uid, mode, links, size, UID)
        metadata()
        assertThrows(IllegalStateException::class.java) { metadata(regular = false) }
        assertThrows(IllegalStateException::class.java) { metadata(uid = 0) }
        assertThrows(IllegalStateException::class.java) { metadata(mode = 0x81a4) }
        assertThrows(IllegalStateException::class.java) { metadata(links = 2) }
        assertThrows(IllegalStateException::class.java) { metadata(size = 31) }
        assertThrows(IllegalStateException::class.java) { metadata(size = 33) }
    }

    private inner class Fixture(val service: Service = Service()) {
        var bindings = 0
        var providerAvailable = true
        private val owner = ModeSwitchRootBinderOwner(bind = {
            bindings++
            check(providerAvailable)
            service
        }, alive = { it.alive })
        val channel = ModeSwitchRootChannel({ token.copyOf() }, {
            owner.current().open()
        }, owner::prepare, {})
    }

    private inner class Service {
        var alive = true
        var held = false
        var dropReply = false
        var uncertainUninstall = false
        var opens = 0
        val calls = mutableListOf<String>()
        val requests = ModeSwitchRootRequests(
            execute = { calls += it; ModeSwitchRootProtocol.Reply(0) },
            uninstall = {
                calls += "uninstall"
                if (uncertainUninstall) ModeSwitchRootProtocol.Reply(-1, detail = ModeSwitchRootProtocol.UNCERTAIN)
                else ModeSwitchRootProtocol.Reply(0)
            },
            acquire = { held = true; dev.maplesu.manager.workmode.switch.HookResult.OK },
            release = { held = false; dev.maplesu.manager.workmode.switch.HookResult.OK },
            isHeld = { held },
        )

        fun open(): ModeSwitchRootConnection {
            check(alive)
            opens++
            val request = ByteArrayOutputStream()
            return object : ModeSwitchRootConnection {
                override val peerUid = 0
                override val output = request
                override val input = object : InputStream() {
                    var response: ByteArrayInputStream? = null
                    override fun read(): Int {
                        if (response == null) {
                            val reply = ByteArrayOutputStream()
                            ModeSwitchRootProtocol.serve(UID, UID, request.toByteArray().inputStream(), reply,
                                token, requests::handle)
                            if (dropReply) throw IOException("reply lost")
                            response = reply.toByteArray().inputStream()
                        }
                        return response!!.read()
                    }
                }
                override fun close() = Unit
            }
        }
    }

    private companion object {
        const val UID = 10481
        const val ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
