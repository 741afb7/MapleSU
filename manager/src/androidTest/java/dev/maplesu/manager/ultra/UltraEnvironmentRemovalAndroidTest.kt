package dev.maplesu.manager.ultra

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.modules.UltraRootKeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated preference migration and explicit removal lifecycle; no device uninstall. */
class UltraEnvironmentRemovalAndroidTest {
    @Test fun removalKeepsTemporaryCredentialsAndRepairsLegacySuspension() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("environmentRemovalDevice") == "true")
        val base = InstrumentationRegistry.getInstrumentation().context
        val prefix = "removal-identity-${UUID.randomUUID()}-"
        val stores = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = prefix + name
                stores += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val prefs = context.getSharedPreferences("maplesu-ultra", Context.MODE_PRIVATE)
        val exports = context.getSharedPreferences("maplesu-ultra-image-exports", Context.MODE_PRIVATE)
        // Historical secrets are input fixtures for retirement, never standard Ultra credentials.
        val retired = listOf("rootKey", "rootKeySuspended", "pendingKernelCredential", "previousInstallCredential",
            "keyVerified", "keyVerifiedFor", "installCredentialTaskId", "installCredentialForKey",
            "installCredentialSourceBoot", "installCredentialProvenance", "installAuthorizationObservedForKey")
        try {
            assertTrue(prefs.edit().apply { retired.forEach { putString(it, "retired-fixture") } }.commit())
            assertTrue(exports.edit().putString("old-export", "retired-fixture").commit())
            UltraRootKeyStore.clearLegacy(context)
            assertTrue("Legacy secret fields survived migration", retired.none(prefs::contains))
            assertTrue("Legacy export credential store survived migration", exports.all.isEmpty())
            assertFalse(UltraRootKeyStore.isSuspended(context))

            UltraRootKeyStore.recordRemoval(context, permanent = false)
            assertEquals("temporary", prefs.getString("environmentRemoval", null))
            assertTrue(UltraRootKeyStore.isSuspended(context))
            UltraRootKeyStore.clearLegacy(context)
            assertEquals("temporary", prefs.getString("environmentRemoval", null))
            assertTrue("Cleanup must not reactivate an explicitly removed environment", UltraRootKeyStore.isSuspended(context))
            UltraRootKeyStore.allowEnvironmentInstall(context)
            assertFalse(UltraRootKeyStore.isSuspended(context))
            assertFalse(prefs.contains("environmentRemoval"))

            UltraRootKeyStore.recordRemoval(context, permanent = true)
            assertEquals("permanent", prefs.getString("environmentRemoval", null))
            assertTrue(UltraRootKeyStore.isSuspended(context))
            assertTrue("Removal must not restore historical secrets", retired.none(prefs::contains))
            UltraRootKeyStore.allowEnvironmentInstall(context)
            assertFalse(UltraRootKeyStore.isSuspended(context))
        } finally {
            stores.forEach(base::deleteSharedPreferences)
        }
    }
}
