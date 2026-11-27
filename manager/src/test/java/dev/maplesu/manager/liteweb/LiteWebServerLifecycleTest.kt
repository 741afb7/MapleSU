package dev.maplesu.manager.liteweb

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteWebServerLifecycleTest {
    @Test fun stopClosesActiveAndQueuedConnectionsBeforeRestart() {
        val accepted = CountDownLatch(6)
        val server = LiteWebServer(
            handler = LiteWebHandler { LiteWebHttpResponse.empty(200) },
            portCandidates = listOf(0),
            connectionThreads = 1,
            socketFactory = LiteWebSocketFactory {
                object : ServerSocket() {
                    override fun accept(): Socket = super.accept().also { accepted.countDown() }
                }
            },
        )
        val clients = mutableListOf<Socket>()
        try {
            server.start()
            repeat(6) { clients += Socket(LiteWebConstants.LOOPBACK, server.boundPort) }
            assertTrue("connections accepted", accepted.await(2, TimeUnit.SECONDS))
            server.stop()
            clients.forEach { client ->
                client.soTimeout = 500
                val end = try { client.getInputStream().read() } catch (_: SocketException) { -1 }
                assertEquals("stopped connection closed", -1, end)
            }
            assertTrue(server.start() is LiteWebStartResult.Started)
            Socket(LiteWebConstants.LOOPBACK, server.boundPort).use { client ->
                client.soTimeout = 2_000
                client.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".toByteArray())
                val response = client.getInputStream().bufferedReader().readText()
                assertTrue(response, response.startsWith("HTTP/1.1 200"))
            }
        } finally {
            clients.forEach { it.close() }
            server.stop()
        }
    }
}
