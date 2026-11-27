package dev.maplesu.manager.liteweb.daemon

import dev.maplesu.manager.litehidden.RecoveryIdentity
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import org.junit.Assert.*
import org.junit.Test

class DaemonManagerRecoveryReadinessTest {
    private val pkg = DaemonManagerUninstall.PACKAGE
    private val identity = requireNotNull(RecoveryIdentity.of("0123456789abcdef", pkg, 10489))
    private var identityText: String? = identity.toJson()
    private var available = true
    private var readable = true
    private var checks = 0
    private val client by lazy { UltraRuntimeClient() }

    private fun readiness() = DaemonManagerRecoveryReadiness(
        identityText = { identityText },
        sdkReady = { checks++; readable },
        sdkAccess = {
            if (available) DaemonUltraSdkResult.Ready(client, "current-task")
            else DaemonUltraSdkResult.Unavailable("SU_AUTH_BACKEND_UNAVAILABLE", "sdk_channel")
        },
    )

    @Test fun currentTaskAndProtectedIdentityNeedNoStoredSecret() {
        assertTrue(readiness().ready())
        assertEquals(1, checks)
    }

    @Test fun unavailableChannelNeverReportsRecoveryReady() {
        available = false
        assertFalse(readiness().ready())
        assertEquals(0, checks)
    }

    @Test fun runtimeFailurePreservesTheCurrentIdentityAndCanBeRetried() {
        val readiness = readiness()
        readable = false
        assertFalse(readiness.ready())
        readable = true
        assertTrue(readiness.ready())
        assertEquals(identity.toJson(), identityText)
        assertEquals(2, checks)
    }

    @Test fun missingMalformedOrWrongManagerIdentityRejectsBeforeSdkWork() {
        for (text in listOf(null, "corrupt", requireNotNull(RecoveryIdentity.of(identity.androidId, "dev.other.manager", 10489)).toJson())) {
            identityText = text
            assertFalse(readiness().ready())
        }
        assertEquals(0, checks)
    }

    @Test fun sdkAuthorizationIsReadAgainForEachRequest() {
        val readiness = readiness()
        assertTrue(readiness.ready())
        available = false
        assertFalse(readiness.ready())
        available = true
        assertTrue(readiness.ready())
        assertEquals(2, checks)
    }
}
