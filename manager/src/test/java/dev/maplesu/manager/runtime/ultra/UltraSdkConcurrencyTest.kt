package dev.maplesu.manager.runtime.ultra

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class UltraSdkConcurrencyTest {
    private class Deferred : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(task: Runnable) { tasks.addLast(task) }
        fun runNext() { tasks.removeFirst().run() }
    }

    @Test fun pairingMaintenanceReturnsImmediatelyAndCoalescesRepeatedRequests() {
        val executor = Deferred()
        var at = 100L
        val queue = UltraMaintenanceQueue(executor) { at }
        var calls = 0
        assertTrue(queue.request("key:boot", { true }) { calls++; true })
        repeat(20) { assertFalse(queue.request("key:boot", { true }) { error("duplicate") }) }
        assertEquals(0, calls)
        executor.runNext()
        assertEquals(1, calls)
        assertFalse(queue.request("key:boot", { true }) { error("cached") })
        at += 60_000
        assertTrue(queue.request("key:boot", { true }) { calls++; true })
        executor.runNext()
        assertEquals(2, calls)
    }

    @Test fun modeOrKeyChangesBeforeExecutionCancelMaintenance() {
        val executor = Deferred()
        val queue = UltraMaintenanceQueue(executor) { 0 }
        var allowed = true
        queue.request("first", { allowed }) { error("stale work") }
        allowed = false
        executor.runNext()
        assertTrue(queue.request("new-key", { true }) { true })
        executor.runNext()
    }

    @Test fun failedOrRejectedMaintenanceCanBeRetried() {
        val executor = Deferred()
        var at = 0L
        val queue = UltraMaintenanceQueue(executor) { at }
        queue.request("first", { true }) { throw IllegalStateException() }
        executor.runNext()
        assertFalse(queue.request("first", { true }) { true })
        at = 10_000
        assertTrue(queue.request("first", { true }) { true })
        executor.runNext()
        var reject = true
        val rejecting = UltraMaintenanceQueue(Executor { if (reject) throw RejectedExecutionException() else it.run() }) { at }
        assertFalse(rejecting.request("key", { true }) { true })
        reject = false
        assertTrue(rejecting.request("key", { true }) { true })
    }

    @Test fun localIdentityQueriesReturnWhileAStatefulSdkCallIsSubmitted() {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null) as sun.misc.Unsafe
        val caller = unsafe.allocateInstance(UltraRuntimeClient::class.java) as UltraRuntimeClient
        val local = unsafe.allocateInstance(UltraRuntimeClient::class.java) as UltraRuntimeClient
        UltraRuntimeClient::class.java.getDeclaredField("loadFailure").apply {
            isAccessible = true
        }.set(local, "HOST_JNI_ABSENT")
        val call = UltraRuntimeClient::class.java.getDeclaredMethod("call", String::class.java,
            kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val pending = workers.submit<NativeReceipt> {
                call.invoke(caller, "run_root_cmd", {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); null
                }) as NativeReceipt
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals("MAPLESU_BRIDGE_UNAVAILABLE", workers.submit<NativeReceipt> { local.sdkVersion() }
                .get(2, TimeUnit.SECONDS).sdkError)
            assertEquals("MAPLESU_BRIDGE_UNAVAILABLE", workers.submit<NativeReceipt> { local.sdkBuildIdentity() }
                .get(2, TimeUnit.SECONDS).sdkError)
            assertFalse(pending.isDone)
            release.countDown(); pending.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); workers.shutdownNow(); workers.awaitTermination(3, TimeUnit.SECONDS) }
    }

    private fun identity(boot: String = "boot") = InstalledIdentity("a".repeat(64), "/data/example/init", 1179976,
        "maplesu-ultra-5.1.0-optimized-r8-20261106", boot, Source.Readback)

    @Test fun identityCacheIsBoundToKeyBootAndMonotonicTime() {
        var at = 0L
        val cache = UltraIdentityReadCache({ at }, 60_000)
        var calls = 0
        fun read(key: String? = "key", boot: String? = "boot", force: Boolean = false) =
            cache.read(key, boot, force) { calls++; identity() }
        read(); read()
        assertEquals(1, calls)
        read("new-key"); read("new-key", "new-boot")
        assertEquals(3, calls)
        read("new-key", "new-boot", force = true)
        assertEquals(4, calls)
        at = -1
        read("new-key", "new-boot")
        assertEquals(5, calls)
        at = 60_000
        read("new-key", "new-boot")
        assertEquals(6, calls)
        cache.invalidate(); read("new-key", "new-boot")
        assertEquals(7, calls)
    }

    @Test fun unknownCredentialsOrBootNeverReuseCachedIdentity() {
        val cache = UltraIdentityReadCache({ 0 }, 60_000)
        var calls = 0
        repeat(2) { cache.read(null, "boot") { calls++; identity() } }
        repeat(2) { cache.read("key", null) { calls++; identity() } }
        assertEquals(4, calls)
    }

    @Test fun concurrentIdentityReadsShareOneActualObservation() {
        val cache = UltraIdentityReadCache({ 0 }, 60_000)
        val workers = Executors.newFixedThreadPool(4)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val first = workers.submit<InstalledIdentity> { cache.read("key", "boot") {
                calls.incrementAndGet(); started.countDown()
                check(release.await(5, TimeUnit.SECONDS)); identity()
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val others = (1..3).map { workers.submit<InstalledIdentity> {
                cache.read("key", "boot") { calls.incrementAndGet(); identity() }
            } }
            release.countDown()
            assertEquals(identity(), first.get(5, TimeUnit.SECONDS))
            others.forEach { assertEquals(identity(), it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }



}
