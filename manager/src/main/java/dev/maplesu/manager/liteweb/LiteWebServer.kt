package dev.maplesu.manager.liteweb

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/** 绑定结果（端口候选 18427/18437/18447/18457 依次回退）。 */
sealed interface LiteWebStartResult {
    data class Started(val port: Int, val attempts: List<String>) : LiteWebStartResult

    data class Failed(val code: String, val detail: String, val attempts: List<String>) : LiteWebStartResult
}

fun interface LiteWebSocketFactory {
    fun create(): ServerSocket
}

/**
 * H2：只绑 loopback 的 HTTP/1.1 服务器（纯 JVM，可主机测试）。
 *
 * - 监听端口按 [LiteWebConstants.PORT_CANDIDATES] 顺序回退；
 * - 每个连接：soTimeout（慢连接超时）→ 限制请求头/体 → 鉴权+路由 → 关闭（Connection: close）；
 * - 日志只记录脱敏路径（token 替换为 `<token>`，query 丢弃）；
 * - `stop()` 关闭监听与连接池；`start()` 幂等（已启动返回当前端口）。
 */
class LiteWebServer(
    private val handler: LiteWebHandler,
    private val address: InetAddress = InetAddress.getByName(LiteWebConstants.LOOPBACK),
    private val portCandidates: List<Int> = LiteWebConstants.PORT_CANDIDATES,
    private val socketFactory: LiteWebSocketFactory = LiteWebSocketFactory { ServerSocket() },
    private val logger: (String) -> Unit = {},
    private val socketTimeoutMs: Int = LiteWebConstants.SOCKET_TIMEOUT_MS,
    private val connectionThreads: Int = 4,
    /** 服务器层日志脱敏用的 token 提供者（token 绝不进日志）。 */
    private val secretProvider: () -> String? = { null },
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val attempts = ArrayList<String>()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var executor: java.util.concurrent.ExecutorService? = null

    @Volatile
    var boundPort: Int = -1
        private set

    private val factory: ThreadFactory = ThreadFactory { task ->
        Thread(task, "liteweb-conn").apply { isDaemon = true }
    }

    @Synchronized
    fun start(): LiteWebStartResult {
        if (running.get()) return LiteWebStartResult.Started(boundPort, attempts.toList())
        attempts.clear()
        for (candidate in portCandidates) {
            var socket: ServerSocket? = null
            try {
                socket = socketFactory.create()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(address, candidate), LiteWebConstants.SOCKET_BACKLOG)
            } catch (error: IOException) {
                attempts.add("$candidate:${error.javaClass.simpleName}:${error.message.orEmpty()}")
                runCatching { socket?.close() }
                continue
            } catch (error: SecurityException) {
                attempts.add("$candidate:security:${error.message.orEmpty()}")
                runCatching { socket?.close() }
                continue
            }
            serverSocket = socket
            boundPort = socket.localPort
            running.set(true)
            executor = Executors.newFixedThreadPool(connectionThreads, factory)
            acceptThread = Thread({ acceptLoop(socket) }, "liteweb-accept").apply {
                isDaemon = true
                start()
            }
            logger("liteweb listening on ${LiteWebConstants.LOOPBACK}:$boundPort")
            return LiteWebStartResult.Started(boundPort, attempts.toList())
        }
        return LiteWebStartResult.Failed(
            "LITEWEB_BIND_FAILED",
            "no candidate port available (${portCandidates.joinToString()})",
            attempts.toList(),
        )
    }

    fun isRunning(): Boolean = running.get()

    /** Replace only the listening socket; the current HTTP reply keeps its connection and pool. */
    @Synchronized
    fun rebind(port: Int, persist: () -> Unit): LiteWebStartResult {
        LiteWebPortPolicy.validate(port)?.let {
            return LiteWebStartResult.Failed(it, "invalid port", emptyList())
        }
        if (!running.get()) return LiteWebStartResult.Failed("LITEWEB_NOT_RUNNING", "listener stopped", emptyList())
        var replacement: ServerSocket? = null
        if (port != boundPort) {
            try {
                replacement = socketFactory.create()
                replacement.reuseAddress = true
                replacement.bind(InetSocketAddress(address, port), LiteWebConstants.SOCKET_BACKLOG)
            } catch (error: Exception) {
                runCatching { replacement?.close() }
                logger("liteweb rebind $boundPort->$port bind failed: ${error.javaClass.simpleName}")
                return LiteWebStartResult.Failed(LiteWebPortPolicy.CODE_CONFLICT, error.javaClass.simpleName, emptyList())
            }
        }
        try {
            persist()
        } catch (error: Exception) {
            runCatching { replacement?.close() }
            val code = error.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]+")) }
                ?: "LITEWEB_PORT_PERSIST_FAILED"
            val cleanup = error.suppressed.joinToString(",") {
                it.message?.takeIf { message -> message.matches(Regex("[A-Z][A-Z0-9_]+")) } ?: it.javaClass.simpleName
            }
            val detail = error.javaClass.simpleName + if (cleanup.isEmpty()) "" else "; rollback=$cleanup"
            logger("liteweb rebind $boundPort->$port persist failed: $code $detail")
            return LiteWebStartResult.Failed(code, detail, emptyList())
        }
        replacement?.let { next ->
            val previous = serverSocket
            serverSocket = next
            boundPort = next.localPort
            acceptThread = Thread({ acceptLoop(next) }, "liteweb-accept").apply { isDaemon = true; start() }
            runCatching { previous?.close() }
        }
        logger("liteweb listening on ${LiteWebConstants.LOOPBACK}:$boundPort")
        return LiteWebStartResult.Started(boundPort, emptyList())
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        connections.forEach(::closeConnection)
        executor?.shutdownNow()
        executor = null
        if (boundPort >= 0) logger("liteweb stopped on $boundPort")
    }

    override fun close() = stop()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                continue
            }
            connections.add(client)
            if (!running.get() || serverSocket !== socket) {
                closeConnection(client)
                return
            }
            val pool = executor
            if (pool == null) {
                closeConnection(client)
                return
            }
            runCatching { pool.execute { handleConnection(client) } }.onFailure { closeConnection(client) }
        }
    }

    /** 连接处理（internal 供慢连接/上限测试复用）。 */
    internal fun handleConnection(client: Socket) {
        try {
            client.soTimeout = socketTimeoutMs
            client.tcpNoDelay = true
            val parsed = LiteWebHttpParser.parse(client.getInputStream())
            val response = when (parsed) {
                is LiteWebParseResult.Rejected -> parsed.response
                is LiteWebParseResult.Parsed -> {
                    val request = parsed.request.copy(remoteLoopback = client.inetAddress.isLoopbackAddress)
                    val handled = try {
                        handler.handle(request)
                    } catch (error: Throwable) {
                        if (error is Error) throw error
                        LiteWebHttpResponse.error(500, "INTERNAL_ERROR", "handler failure")
                    }
                    logRequest(request, handled)
                    handled
                }
            }
            client.getOutputStream().use { output ->
                output.write(LiteWebResponses.render(response))
                output.flush()
            }
            response.afterResponse?.let { completed -> runCatching(completed) }
        } catch (_: SocketTimeoutException) {
            logger("liteweb slow-client timeout")
        } catch (_: IOException) {
            logger("liteweb connection error")
        } finally {
            closeConnection(client)
        }
    }

    private fun closeConnection(client: Socket) {
        runCatching { client.close() }
        connections.remove(client)
    }

    private fun logRequest(request: LiteWebHttpRequest, response: LiteWebHttpResponse) {
        val target = LiteWebRedaction.redactTarget(request.target, secretProvider())
        logger("liteweb ${request.method} $target -> ${response.status}")
    }

    internal fun readAllBounded(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (out.size() <= limit) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        fun serverAddress(): String = LiteWebConstants.LOOPBACK
    }
}

/** URL 构造（token 只放内存；日志/持久化不得出现完整 URL）。 */
object LiteWebUrls {
    fun url(port: Int, token: String, prefix: String = LiteWebConstants.PATH_PREFIX): String {
        require(LiteWebToken.isValid(token)) { "LITEWEB_TOKEN_INVALID" }
        return "http://${LiteWebConstants.LOOPBACK}:$port/$prefix/$token/"
    }

    fun apiUrl(port: Int, token: String, apiPath: String): String {
        val base = url(port, token)
        return base + apiPath.trimStart('/')
    }
}
