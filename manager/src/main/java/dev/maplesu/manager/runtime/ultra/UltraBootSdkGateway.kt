package dev.maplesu.manager.runtime.ultra

import org.json.JSONObject

/** A control receipt is not proof that the SDK channel or environment works. */
internal object UltraBootSdkGateway {
    private const val PREFIX = "MAPLESU_BOOT_SDK:"
    private val BOOT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val BASE = Regex("0xffff[0-9a-f]{12}")

    class Receipt(val bootId: String, val state: Int, val error: Int,
                  val capabilities: Int, val kernelBase: String) {
        val ready: Boolean get() = state == 1 && error == 0 && capabilities and 3 == 3
        val environment: Boolean get() = ready && capabilities and 4 != 0
        override fun toString() = "UltraBootSdkGateway(state=$state, error=$error, capabilities=$capabilities)"
    }

    fun parse(output: String?, bootId: String): Receipt? = runCatching {
        if (output == null || output.length > 16_384 || !BOOT.matches(bootId)) return null
        val lines = output.lineSequence().filter { it.startsWith(PREFIX) }.toList()
        if (lines.size != 1) return null
        val json = JSONObject(lines.single().removePrefix(PREFIX))
        if (json.getInt("v") != 1 || json.getString("bootId") != bootId) return null
        val state = json.getInt("state")
        val error = json.getInt("error")
        val capabilities = json.getInt("capabilities")
        val base = json.getString("kernelBase")
        if (state !in 0..3 || capabilities and 7.inv() != 0) return null
        if (state == 1 && error == 0) {
            if (!BASE.matches(base) || capabilities and 3 != 3) return null
        } else if (base != "0x0000000000000000" || capabilities != 0) return null
        Receipt(bootId, state, error, capabilities, base)
    }.getOrNull()

    interface Port {
        fun current(): Boolean
        fun query(): Receipt?
        fun markSubmitted(): Boolean
        fun start(): String?
        fun bind(receipt: Receipt): Boolean
    }

    data class Result(val bound: Boolean, val submitted: Boolean, val reason: String)

    /** Keep helper stages and errno, never arbitrary output or gateway credentials. */
    fun commandFailure(code: Int, output: String): String =
        Regex("\\b(?:BOOT_SDK|MODULE_POLICY_LAYOUT)_[A-Z_]+(?::-?\\d{1,10}|:[a-z_]{1,64})?\\b")
            .find(output)?.value ?: "SDK_BOOTSTRAP_COMMAND_FAILED:$code"

    fun connect(alreadySubmitted: Boolean, port: Port): Result {
        var submitted = false
        var startFailure: String? = null
        fun fail(reason: String) = Result(false, submitted, reason)
        return try {
            if (!port.current()) return fail("SESSION_CHANGED")
            var receipt = port.query() ?: return fail("SDK_GATEWAY_UNAVAILABLE")
            if (!port.current()) return fail("SESSION_CHANGED")
            if (receipt.state == 0 && receipt.error == 0) {
                // EMPTY is a live kernel receipt: bootstrap has not entered INITIALIZING.
                // A failed userspace preflight must not poison the whole boot. The
                // resident state machine still prevents a second allocation.
                if (!alreadySubmitted && !port.markSubmitted()) return fail("SDK_BOOTSTRAP_GUARD_STORE_FAILED")
                if (!port.current()) return fail("SESSION_CHANGED")
                submitted = true
                // Initialization can finish before a transport exception. Only query follows.
                try { startFailure = port.start() } catch (error: Exception) {
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                    startFailure = "SDK_BOOTSTRAP_COMMAND_FAILED"
                }
                if (!port.current()) return fail("SESSION_CHANGED")
                receipt = port.query() ?: return fail(
                    "SDK_BOOTSTRAP_RECONCILIATION_REQUIRED" + (startFailure?.let { ":$it" } ?: ""))
            }
            if (!port.current()) return fail("SESSION_CHANGED")
            if (!receipt.ready) return fail(when (receipt.state) {
                2 -> "SDK_BOOTSTRAP_RECONCILIATION_REQUIRED"
                3 -> "SDK_GATEWAY_FAILED:${receipt.error}"
                else -> startFailure ?: "SDK_GATEWAY_NOT_READY"
            })
            if (!port.bind(receipt)) return fail(if (port.current()) "SDK_BIND_UNVERIFIED" else "SESSION_CHANGED")
            if (!port.current()) return fail("SESSION_CHANGED")
            Result(true, submitted, "SDK_BOUND")
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            fail("SDK_GATEWAY_OBSERVATION_FAILED")
        }
    }

    fun kernelBaseMatches(info: String?, expected: String): Boolean =
        BASE.matches(expected) && kernelBase(info) == expected

    fun kernelBase(info: String?): String? {
        if (info == null) return null
        val matches = Regex("(?m)^Output addr: (0x[0-9a-fA-F]+)$").findAll(info).toList()
        return matches.singleOrNull()?.groupValues?.get(1)?.lowercase()?.takeIf(BASE::matches)
    }
}
