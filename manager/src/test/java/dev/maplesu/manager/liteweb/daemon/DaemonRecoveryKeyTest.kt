package dev.maplesu.manager.liteweb.daemon

import dev.maplesu.manager.litehidden.RecoveryIdentity
import dev.maplesu.manager.liteweb.LiteWebUnhideCodes
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import org.junit.Assert.*
import org.junit.Test

/** Historical key-recovery regressions now exercise protected identity + current-task SDK access. */
class DaemonRecoveryKeyTest {
    private val pkg = "dev.maplesu.manager"
    private val appAndroidId = "0123456789abcdef"
    private val systemAndroidId = "fedcba9876543210"
    private fun identity(androidId: String = appAndroidId, packageName: String = pkg) =
        requireNotNull(RecoveryIdentity.of(androidId, packageName, 10_123))
    private fun sdk() = DaemonUltraSdkResult.Ready(UltraRuntimeClient(), "current-task")

    @Test fun protectedAppIdentityDoesNotNeedTheSystemAndroidIdOrAStoredSecret() {
        var calls = 0
        val readiness = DaemonManagerRecoveryReadiness({ identity().toJson() }, { calls++; true }, ::sdk)
        assertTrue(readiness.ready())
        assertEquals(1, calls)
        assertNotEquals(appAndroidId, systemAndroidId)
    }

    @Test fun currentTaskAccessDoesNotDependOnEitherAndroidIdAsADecryptionKey() {
        for (androidId in listOf(appAndroidId, systemAndroidId)) {
            assertTrue(DaemonManagerRecoveryReadiness({ identity(androidId).toJson() }, { true }, ::sdk).ready())
        }
    }

    @Test fun staleProtectedIdentityNeverFallsBackToAnotherPackage() {
        var sdkCalls = 0
        val readiness = DaemonManagerRecoveryReadiness(
            { identity(packageName = "other.manager").toJson() }, { error("foreign identity") },
            { sdkCalls++; sdk() })
        assertFalse(readiness.ready())
        assertEquals(0, sdkCalls)
    }

    @Test fun renamedPackageDoesNotBorrowTheFixedManagersRecoveryAuthority() {
        val renamed = "dev.maplesu.manager.beta"
        assertFalse(DaemonManagerRecoveryReadiness({ identity(packageName = renamed).toJson() },
            { error("wrong manager") }, ::sdk).ready())
    }

    @Test fun unavailableSdkCannotClaimRecoveryAndDoesNotPublishIdentityMaterial() {
        val missing = DaemonUltraSdkResult.Unavailable("SU_AUTH_BACKEND_UNAVAILABLE", "sdk_channel")
        assertFalse(DaemonManagerRecoveryReadiness({ identity().toJson() },
            { error("no current-task channel") }, { missing }).ready())
        assertFalse(missing.toString().contains(appAndroidId))
        assertFalse(missing.toString().contains(systemAndroidId))
    }

    @Test fun absentAndMalformedIdentityFailBeforeAnySdkAccess() {
        var calls = 0
        for (text in listOf(null, "   ", "bm90LW91cnM=", "{}")) {
            assertFalse(DaemonManagerRecoveryReadiness({ text }, { error("identity not established") },
                { calls++; sdk() }).ready())
        }
        assertEquals(0, calls)
    }

    @Test fun missingBootBindingFailsBeforeLoadingOrRunningSdk() {
        var loads = 0
        val access = DaemonUltraSdkAccess(
            DaemonShell { error("no shell probe before boot binding") },
            loadSdk = { loads++; true }, bootId = { null })
        val result = access.open() as DaemonUltraSdkResult.Unavailable
        assertEquals("boot_id_unavailable", result.detail)
        assertEquals(0, loads)
    }

    @Test fun corruptedIdentityIsRejectedInsteadOfUsingASystemValueFallback() {
        assertNull(RecoveryIdentity.parse("{\"v\":1,\"androidId\":\"$appAndroidId\"}"))
        assertFalse(DaemonManagerRecoveryReadiness({ "garbage" },
            { error("corrupted identity") }, ::sdk).ready())
    }

    // ── 回读判定（remove_su_auth 之后） ───────────────────────────────────

    @Test
    fun readbackWithoutHostPackageIsSuccess() {
        val result = SuAuthReadbackPolicy.verify(
            hostPackage = "com.example.host",
            packages = listOf("bin.mt.plus"),
            listReadable = true,
            remaining = 1,
            source = "current-task",
        )
        assertTrue(result.ok)
        assertTrue("回执要能区分用的是 identity 还是系统值：${result.detail}", result.detail.contains("id=current-task"))
        assertTrue(result.detail.contains("remaining=1"))
    }

    @Test
    fun readbackStillListingHostIsFailure() {
        val result = SuAuthReadbackPolicy.verify(
            hostPackage = "com.example.host",
            packages = listOf("com.example.host", "bin.mt.plus"),
            listReadable = true,
            remaining = 2,
            source = "current-task",
        )
        assertFalse("宿主还在授权列表里绝不能报成功", result.ok)
        assertEquals(LiteWebUnhideCodes.SU_AUTH_REVOKE_FAILED, result.code)
        assertTrue(result.detail.contains("still_listed"))
    }

    @Test
    fun unparsableReadbackIsFailureNotSuccess() {
        assertFalse(
            SuAuthReadbackPolicy.verify(
                hostPackage = "com.example.host",
                packages = emptyList(),
                listReadable = false,
                remaining = 0,
                source = "current-task",
            ).ok,
        )
        val nullPackages = SuAuthReadbackPolicy.verify(
            hostPackage = "com.example.host",
            packages = null,
            listReadable = true,
            remaining = 0,
            source = "current-task",
        )
        assertFalse("读不到列表 ≠ 已撤销", nullPackages.ok)
        assertEquals(SuAuthReadbackPolicy.READBACK_UNPARSABLE, nullPackages.detail)
    }
}
