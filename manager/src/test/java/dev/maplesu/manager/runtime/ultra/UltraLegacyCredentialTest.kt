package dev.maplesu.manager.runtime.ultra

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class UltraLegacyCredentialTest {
    private val key = "A".repeat(48)
    private fun verified(value: String = key): MutableMap<String, Any?> = mutableMapOf(
        "rootKey" to value, "keyVerified" to true,
        "keyVerifiedFor" to MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) },
    )

    private fun read(values: Map<String, Any?>): UltraLegacyCredential? {
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0]] as? String ?: args[1]
                "getBoolean" -> values[args[0]] as? Boolean ?: args[1]
                else -> error("Unexpected preference read: ${method.name}")
            }
        } as SharedPreferences
        return UltraLegacyCredential.read(prefs)
    }

    @Test fun existingVerifiedRecordRetainsItsActualCredential() {
        assertEquals(key, read(verified())?.value)
        assertEquals("UltraLegacyCredential(verifiedRecord)", read(verified()).toString())
    }

    @Test fun missingUnverifiedAndMismatchedRecordsStayAbsent() {
        assertNull(read(emptyMap()))
        assertNull(read(verified().apply { remove("keyVerified") }))
        assertNull(read(verified().apply { this["keyVerified"] = false }))
        assertNull(read(verified().apply { this["keyVerifiedFor"] = "different" }))
    }

    @Test fun suspendedAndRemovedRecordsDoNotSelectLegacy() {
        assertNull(read(verified().apply { this["rootKeySuspended"] = "suspended" }))
        assertNull(read(verified().apply { this["environmentRemoval"] = "temporary" }))
        assertNull(read(verified().apply { this["environmentRemoval"] = "permanent" }))
    }

    @Test fun malformedHistoricalValuesStayAbsent() {
        for (value in listOf("short", "A".repeat(1024), "A".repeat(16) + "\u0000", "A".repeat(16) + "中")) {
            assertNull(read(verified(value)))
        }
    }

    @Test fun kernelClaimRequiresExactlyFortyEightBase62Bytes() {
        assertNotNull(UltraLegacyCredential.fromKernelClaim(key))
        for (value in listOf("", "A".repeat(47), "A".repeat(49), "A".repeat(47) + "_")) {
            assertNull(UltraLegacyCredential.fromKernelClaim(value))
        }
    }
}
