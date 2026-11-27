package com.maplesu.core.ui.util

import com.maplesu.core.ksuApp
import dev.maplesu.manager.runtime.ultra.NativeReceipt
import dev.maplesu.manager.runtime.ultra.SELinuxBoundary as B
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient.ManagerOwnerIdentity
import dev.maplesu.manager.workmode.ModeActivation
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val ownerA = ManagerOwnerIdentity(10123, 11, "host-a")
private val keyA = "a".repeat(48)
private val keyB = "b".repeat(48)

private fun reset() {
    invalidateUltraSELinuxStatus()
    B.key = null
    B.boot = "boot-a"
    B.owner = ownerA
    B.liveOwner = ownerA
    ModeRuntime.activation = ModeActivation.Active(WorkMode.LITE)
    B.ultra = true
    B.hook = false
    B.legacyCalls = 0
    B.hookCalls = 0
    B.hookChecks.set(0)
    B.environmentChecks.set(0)
    B.statusCalls.set(0)
    B.rootCalls.set(0)
    B.ownerCalls.set(0)
    B.submittedKeys.clear()
    B.status = { NativeReceipt(true, 1, true) }
    B.root = { NativeReceipt(true, result = "Enforcing") }
    android.os.SystemClock.now = 1_000L
}

private fun expect(value: Any?, actual: Any?) {
    check(value == actual) { "expected=$value actual=$actual" }
}
private fun deniedLocal() = getSELinuxStatusRaw { throw java.io.IOException("Permission denied") }
private fun worker(action: () -> String): Pair<Thread, AtomicReference<String>> {
    val value = AtomicReference<String>()
    val thread = Thread { value.set(runCatching(action).getOrElse { "THREW:$it" }) }
    thread.start()
    return thread to value
}
private fun await(latch: CountDownLatch) { check(latch.await(3, TimeUnit.SECONDS)) { "barrier timeout" } }
private fun finish(worker: Pair<Thread, AtomicReference<String>>): String {
    worker.first.join(3_000)
    check(!worker.first.isAlive) { "worker did not finish" }
    return worker.second.get()
}

fun main(args: Array<String>) {
    var passed = 0
    fun scenario(name: String, body: () -> Unit) {
        reset()
        body()
        check(B.legacyCalls == 0) { "Ultra check started another Root transport" }
        check(B.ownerCalls.get() == 0) { "SELinux read added an Owner identity query" }
        passed++
        println("PASS $name")
    }
    scenario("verified-legacy-root-fallback-after-denied-local-read") {
        B.owner = null; B.liveOwner = null; B.key = keyA
        B.status = { NativeReceipt(true) }
        expect("Enforcing", deniedLocal())
        expect(listOf(keyA), B.submittedKeys.toList())
        expect("Enforcing", peekCachedSELinuxStatus())
    }
    if (args.contains("--owner-only")) return
    scenario("cache-peek-does-not-probe-hook-or-environment") {
        expect("Enforcing", deniedLocal())
        B.hookChecks.set(0); B.environmentChecks.set(0)
        B.hook = true; B.ultra = false
        expect("Enforcing", peekCachedSELinuxStatus())
        expect(0, B.hookChecks.get()); expect(0, B.environmentChecks.get())
    }
    scenario("sdk-root-fallback-routes-once") {
        B.owner = null; B.liveOwner = null; B.key = keyA
        B.status = { NativeReceipt(true) }
        expect("Enforcing", deniedLocal())
        expect(1, B.hookChecks.get()); expect(1, B.environmentChecks.get())
        expect(1, B.statusCalls.get()); expect(1, B.rootCalls.get())
    }
    scenario("owner-no-key-real-entry") {
        expect("Enforcing", getSELinuxStatusRaw())
        expect(1, B.statusCalls.get())
    }
    scenario("owner-no-key-sdk-root-fallback") {
        B.status = { NativeReceipt(true) }
        expect("Enforcing", deniedLocal())
        expect(listOf<String?>(null), B.submittedKeys.toList())
    }
    scenario("independent-system-status-needs-no-owner-or-key") {
        B.owner = null; B.liveOwner = null
        expect("Enforcing", deniedLocal())
        expect(0, B.rootCalls.get())
    }
    scenario("foreign-owner-does-not-gate-local-system-fact") {
        B.owner = ownerA.copy(fullUid = 110123); B.liveOwner = B.owner
        expect("Enforcing", deniedLocal())
        expect(0, B.rootCalls.get())
    }
    scenario("snapshot-live-owner-mismatch-does-not-gate-system-fact") {
        B.liveOwner = ownerA.copy(epoch = 12)
        expect("Enforcing", deniedLocal())
        expect(0, B.rootCalls.get())
    }
    scenario("local-enforce-file-does-not-query-sdk") {
        val file = File.createTempFile("maplesu-enforce-", ".txt")
        try {
            file.writeText("1\n")
            B.status = { error("local file should be sufficient") }
            expect("Enforcing", getSELinuxStatusRaw { file.readText() })
            file.writeText("0\n")
            expect("Permissive", getSELinuxStatusRaw { file.readText() })
            expect(0, B.statusCalls.get()); expect(0, B.rootCalls.get())
        } finally { file.delete() }
    }
    scenario("hook-keeps-existing-status-route") {
        B.hook = true
        expect("Enforcing", deniedLocal())
        expect(1, B.hookCalls); expect(0, B.statusCalls.get())
    }
    scenario("missing-authentication-and-unreadable-system-remain-unknown") {
        B.owner = null; B.liveOwner = null
        B.status = { NativeReceipt(true) }
        expect("Unknown", deniedLocal())
        expect(null, peekCachedSELinuxStatus())
    }
    for (invalid in listOf("unverified", "fingerprint", "suspended", "removal")) {
        scenario("legacy-$invalid-does-not-authorize-root-fallback") {
            B.owner = null; B.liveOwner = null; B.key = keyA
            val prefs = ksuApp.getSharedPreferences("maplesu-ultra", 0)
            when (invalid) {
                "unverified" -> prefs.values["keyVerified"] = false
                "fingerprint" -> prefs.values["keyVerifiedFor"] = "wrong"
                "suspended" -> prefs.values["rootKeySuspended"] = "paused"
                "removal" -> prefs.values["environmentRemoval"] = "pending"
            }
            B.status = { NativeReceipt(true) }
            expect("Unknown", deniedLocal())
            expect(null, peekCachedSELinuxStatus())
            expect(0, B.rootCalls.get())
        }
    }
    scenario("known-owner-keeps-existing-owner-transport") {
        B.key = keyA; B.status = { NativeReceipt(true) }
        expect("Enforcing", deniedLocal())
        expect(listOf<String?>(null), B.submittedKeys.toList())
    }
    scenario("known-cache-is-reused-without-revalidating-owner-or-key") {
        expect("Enforcing", deniedLocal())
        B.owner = null; B.liveOwner = null; B.key = keyA
        expect("Enforcing", deniedLocal())
        expect("Enforcing", peekCachedSELinuxStatus())
        expect(1, B.statusCalls.get())
    }
    for (change in listOf("boot", "activation", "revision")) {
        scenario("$change-change-invalidates-cache-and-token") {
            val token = captureUltraSELinuxObservation()
            expect("Enforcing", deniedLocal())
            when (change) {
                "boot" -> B.boot = "boot-b"
                "activation" -> ModeRuntime.activation = ModeActivation.Active(WorkMode.LITE)
                "revision" -> invalidateUltraSELinuxStatus()
            }
            check(!rememberUltraSELinuxStatus("Permissive", token))
            expect(null, peekCachedSELinuxStatus())
            B.status = { NativeReceipt(true, 0, true) }
            expect("Permissive", deniedLocal())
            expect(2, B.statusCalls.get())
        }
    }
    scenario("concurrent-same-environment-coalesces-request") {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        B.status = { entered.countDown(); await(release); NativeReceipt(true, 1, true) }
        val first = worker { deniedLocal() }; await(entered)
        val second = worker { deniedLocal() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (second.first.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1)
            check(second.first.state == Thread.State.WAITING); expect(1, B.statusCalls.get())
        } finally { release.countDown() }
        expect("Enforcing", finish(first)); expect("Enforcing", finish(second))
    }
    for (change in listOf("boot", "activation", "revision")) {
        scenario("concurrent-$change-discards-old-reply") {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            B.status = { entered.countDown(); await(release); NativeReceipt(true, 1, true) }
            val old = worker { deniedLocal() }; await(entered)
            when (change) {
                "boot" -> B.boot = "boot-b"
                "activation" -> ModeRuntime.activation = ModeActivation.Active(WorkMode.LITE)
                "revision" -> invalidateUltraSELinuxStatus()
            }
            B.status = { NativeReceipt(true, 0, true) }
            try { expect("Permissive", deniedLocal()) } finally { release.countDown() }
            expect("Unknown", finish(old)); expect("Permissive", peekCachedSELinuxStatus())
            expect(2, B.statusCalls.get())
        }
    }
    scenario("owner-change-during-independent-system-read-preserves-fact") {
        B.status = { B.owner = null; B.liveOwner = null; NativeReceipt(true, 0, true) }
        expect("Permissive", deniedLocal())
    }
    scenario("legacy-key-change-during-root-command-discards-reply") {
        B.owner = null; B.liveOwner = null; B.key = keyA
        B.status = { NativeReceipt(true) }
        B.root = { B.key = keyB; NativeReceipt(true, result = "Permissive") }
        expect("Unknown", deniedLocal()); expect(null, peekCachedSELinuxStatus())
    }
    scenario("failed-and-malformed-values-preserve-unknown") {
        B.status = { NativeReceipt(false) }; B.root = { NativeReceipt(false) }
        expect("Unknown", deniedLocal()); expect(null, peekCachedSELinuxStatus())
        B.root = { NativeReceipt(true, result = "Enforcing\nextra") }
        expect("Unknown", deniedLocal())
    }
    scenario("known-cache-expires-after-five-seconds") {
        expect("Enforcing", deniedLocal()); android.os.SystemClock.now += 5_000
        expect(null, peekCachedSELinuxStatus())
        B.status = { NativeReceipt(true, 0, true) }
        expect("Permissive", deniedLocal()); expect(2, B.statusCalls.get())
    }
    println("PASS_TOTAL=$passed")
}
