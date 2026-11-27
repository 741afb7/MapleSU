package dev.maplesu.manager.runtime.ultra

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class UltraSdkLogPolicyTest {
    private fun receipt(enabled: Boolean) = NativeReceipt.parse("""{
        "backend":"maplesu_ultra","operation":"set_maplesu_log_enabled","ok":true,"sdkError":"OK",
        "sdkErrorCode":0,"data":{"requested":$enabled}}
    """)!!

    @Test fun repeatedStartupPolicyReusesOnlyAnAcknowledgedWriteWithoutMutatingItsReceipt() {
        val policy = UltraSdkLogPolicy { 0 }
        val original = receipt(false)
        assertSame(original, policy.apply({ "key" }, false) { original })
        val cached = policy.apply({ "key" }, false) { error("duplicate exclusive SDK call") }!!
        assertTrue(cached.succeeded)
        assertEquals(true, cached.bool("cached"))
        assertEquals(false, cached.bool("requested"))
        assertEquals(true, NativeReceipt.parse(cached.raw)!!.bool("cached"))
        assertNull(original.bool("cached"))
    }

    @Test fun concurrentStartupPathsSetTheLogPolicyOnlyOnce() {
        val policy = UltraSdkLogPolicy { 0 }
        val workers = Executors.newFixedThreadPool(3)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val first = workers.submit<NativeReceipt?> { policy.apply({ "key" }, false) {
                calls.incrementAndGet(); started.countDown(); check(release.await(5, TimeUnit.SECONDS)); receipt(false)
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val peers = (1..2).map { workers.submit<NativeReceipt?> { policy.apply({ "key" }, false) {
                calls.incrementAndGet(); receipt(false)
            } } }
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS)!!.succeeded)
            peers.forEach { assertEquals(true, it.get(5, TimeUnit.SECONDS)!!.bool("cached")) }
            assertEquals(1, calls.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun credentialsDesiredValueAndExplicitInvalidationRequireNewWrites() {
        val policy = UltraSdkLogPolicy { 0 }
        var calls = 0
        fun apply(key: String, enabled: Boolean) = policy.apply({ key }, enabled) { calls++; receipt(enabled) }
        apply("first", false); apply("second", false); apply("second", true); apply("second", false)
        policy.invalidate(); apply("second", false)
        assertEquals(5, calls)
    }

    @Test fun failedMissingAndMismatchedAcknowledgementsAreNeverCached() {
        val failed = NativeReceipt.bridgeFailure("set_maplesu_log_enabled", "MAPLESU_SDK_BUSY", null)
        val missing = receipt(false).copy(data = null)
        val malformed = receipt(false).copy(data = org.json.JSONObject().put("requested", "false"))
        for (answer in listOf(null, failed, missing, malformed, receipt(true), receipt(false).copy(ok = false))) {
            val policy = UltraSdkLogPolicy { 0 }
            var calls = 0
            repeat(2) { assertSame(answer, policy.apply({ "key" }, false) { calls++; answer }) }
            assertEquals(2, calls)
            assertTrue(policy.apply({ "key" }, false) { receipt(false) }!!.succeeded)
        }
    }

    @Test fun expirationClockRollbackAndExceptionsClearPreviousSuccess() {
        var at = 100L
        val policy = UltraSdkLogPolicy { at }
        var calls = 0
        fun apply() = policy.apply({ "key" }, false) { calls++; receipt(false) }
        apply(); at = 60_099; apply(); assertEquals(1, calls)
        at = 60_100; apply(); assertEquals(2, calls)
        at = 60_099; apply(); assertEquals(3, calls)
        policy.invalidate()
        try { policy.apply({ "key" }, false) { throw IllegalStateException("fixture") }; fail("missing exception") }
        catch (_: IllegalStateException) { }
        apply(); assertEquals(4, calls)
    }

    @Test fun unknownOrChangingCredentialsNeverReuseAPreviousAcknowledgement() {
        val policy = UltraSdkLogPolicy { 0 }
        var current: String? = "key"
        var calls = 0
        policy.apply({ current }, false) { calls++; current = null; receipt(false) }
        current = "key"
        policy.apply({ current }, false) { calls++; receipt(false) }
        repeat(2) { policy.apply({ null }, false) { calls++; receipt(false) } }
        policy.apply({ current }, false) { calls++; receipt(false) }
        assertEquals(5, calls)
    }

    @Test fun productionKeepsInitialPolicyAndInvalidatesBeforeEnvironmentMutations() {
        val manager = File(System.getProperty("user.dir") ?: ".").let {
            if (File(it, "src/main/java").isDirectory) it else File(it, "manager")
        }
        val base = File(manager, "src/main/java/dev/maplesu/manager/runtime/ultra")
        val logging = File(base, "UltraSdkLogging.kt").readText()
        assertTrue(logging.contains("policy.apply(client::logPolicyScope"))
        val explicit = logging.substringAfter("fun setExplicitDiagnostics(").substringBefore("/**")
        assertTrue(explicit.contains("policy.invalidate()"))
        val source = File(base, "UltraRuntimeClient.kt").readText()
        for (operation in listOf("installEnvironment", "uninstallEnvironment")) {
            val body = source.substringAfter("fun $operation(").substringBefore("\n    fun ")
            assertTrue(operation, body.indexOf("UltraSdkLogging.invalidate()") in 0 until body.indexOf("val receipt = callWithIdentity("))
        }
    }
}
