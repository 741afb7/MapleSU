package com.maplesu.core.ui.util

import com.topjohnwu.superuser.LocalShellBoundary as S
import dev.maplesu.manager.runtime.ultra.SELinuxBoundary as B
import dev.maplesu.manager.workmode.detect.ProcBootIdSource

private fun expect(expected: Any?, actual: Any?) { check(expected == actual) { "expected=$expected actual=$actual" } }
fun main() {
    var passed=0
    fun scenario(name:String, body:()->Unit) {
        S.reset(); B.owner=null; B.liveOwner=null; B.key=null; B.ultra=true; B.hook=false
        B.hookChecks.set(0); B.environmentChecks.set(0); B.statusCalls.set(0); B.rootCalls.set(0); B.ownerCalls.set(0)
        B.legacyCalls=0; B.hookCalls=0
        body()
        expect(0,B.hookChecks.get()); expect(0,B.environmentChecks.get()); expect(0,B.statusCalls.get())
        expect(0,B.rootCalls.get()); expect(0,B.ownerCalls.get()); expect(0,B.legacyCalls); expect(0,B.hookCalls)
        passed++; println("PASS $name")
    }
    scenario("ordinary-shell-reads-permissive-with-no-sdk-or-owner") {
        S.stdout=listOf("Permissive")
        expect("Permissive",getSELinuxStatusRaw())
        expect("sh",S.program); expect("getenforce",S.command); expect(1,S.starts); expect(1,S.closes)
    }
    for(value in listOf("Enforcing","Permissive","Disabled")) scenario("successful-$value-is-exact") {
        S.stdout=listOf("  $value  ")
        expect(value,getSELinuxStatusRaw()); expect(1,S.closes)
    }
    scenario("sdk-and-hook-state-do-not-route-system-fact") {
        B.ultra=false; B.hook=true
        S.stdout=listOf("Permissive")
        expect("Permissive",getSELinuxStatusRaw()); expect(1,S.starts)
    }
    scenario("known-sdk-observation-is-not-a-shell-result") {
        val token=captureUltraSELinuxObservation()
        rememberUltraSELinuxStatus("Enforcing",token)
        S.stdout=listOf("Permissive")
        expect("Permissive",getSELinuxStatusRaw()); expect(1,S.starts)
    }
    scenario("permission-denied-keeps-fixed-ksu-compatibility-inference") {
        S.success=false; S.stdout=emptyList()
        S.stderr=listOf("getenforce: Couldn't get enforcing status: Permission denied")
        expect("Enforcing",getSELinuxStatusRaw()); expect(1,S.closes)
    }
    for(value in listOf("","denied","1","Enforcing\nextra")) scenario("malformed-success-$value-remains-unknown") {
        S.stdout=listOf(value)
        expect("Unknown",getSELinuxStatusRaw())
    }
    for(value in listOf("not found","Operation not permitted","Permission denied extra")) scenario("other-failure-$value-remains-unknown") {
        S.success=false; S.stdout=emptyList(); S.stderr=listOf(value)
        expect("Unknown",getSELinuxStatusRaw())
    }
    scenario("successful-malformed-output-is-not-replaced-by-denied-stderr") {
        S.stdout=listOf("bad"); S.stderr=listOf("Permission denied")
        expect("Unknown",getSELinuxStatusRaw())
    }
    scenario("sdk-cache-changes-do-not-affect-repeated-shell-reads") {
        S.stdout=listOf("Enforcing"); expect("Enforcing",getSELinuxStatusRaw())
        invalidateUltraSELinuxStatus(); B.ultra=false
        S.stdout=listOf("Permissive"); expect("Permissive",getSELinuxStatusRaw())
        expect(2,S.starts); expect(2,S.closes)
    }
    scenario("compatibility-observation-keeps-boot-and-revision-lifetime") {
        invalidateUltraSELinuxStatus(); ProcBootIdSource.boot="boot-a"
        val token=captureUltraSELinuxObservation()
        check(rememberUltraSELinuxStatus("Permissive",token))
        expect("Permissive",peekCachedSELinuxStatus())
        ProcBootIdSource.boot="boot-b"
        expect(null,peekCachedSELinuxStatus()); check(!rememberUltraSELinuxStatus("Enforcing",token))
        val next=captureUltraSELinuxObservation()
        invalidateUltraSELinuxStatus(); check(!rememberUltraSELinuxStatus("Enforcing",next))
        expect(null,peekCachedSELinuxStatus())
    }
    scenario("compatibility-cache-expires-and-never-publishes-unknown") {
        invalidateUltraSELinuxStatus()
        val token=captureUltraSELinuxObservation()
        check(rememberUltraSELinuxStatus("Enforcing",token))
        check(!rememberUltraSELinuxStatus("Unknown",token))
        expect("Enforcing",peekCachedSELinuxStatus())
        android.os.SystemClock.now+=5_000
        expect(null,peekCachedSELinuxStatus())
    }
    println("PASS_TOTAL=$passed")
}
