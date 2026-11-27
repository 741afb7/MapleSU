package dev.maplesu.manager.liteweb

import android.os.Bundle
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.litehidden.RecoveryIdentity
import dev.maplesu.manager.litehidden.RecoveryIdentityScripts
import dev.maplesu.manager.litehidden.ParasiteMarkers
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly selected evidence only. Never installs an environment or changes any grant. */
class LiteWebRuntimeDeviceEvidenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun sdkFactsAreReadableWithoutManagerBootstrap() {
        requireOptIn()
        val client = client()
        val environment = client.environmentState()
        assertTrue("environment receipt", environment.succeeded)
        assertEquals("Running", environment.string("state"))
        val modules = client.moduleList()
        assertTrue("module receipt", modules.succeeded)
        val grants = client.suAuthSnapshot()
        assertTrue("grant receipt", grants.receipt.succeeded)
        assertTrue("grant array is explicit", grants.receipt.array("packages") != null)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("ultraState", environment.string("state"))
            putInt("ultraModuleCount", checkNotNull(modules.array("modules")).length())
            putInt("ultraGrantCount", grants.count)
        })
    }

    @Test fun repairDaemonIdentityWithCurrentInstalledOwner() {
        requireOptIn()
        assumeTrue(InstrumentationRegistry.getArguments().getString("repairLiteWebIdentity") == "true")
        val client = client()
        val environment = client.environmentState()
        assertTrue("environment receipt", environment.succeeded)
        assertEquals("Running", environment.string("state"))
        val owner = client.queryManagerOwnerIdentity()
        assertTrue("current installed owner receipt", owner.succeeded)
        val installedOwner = checkNotNull(owner.identity)
        assertEquals("current caller fullUID", context.applicationInfo.uid, installedOwner.fullUid)
        // This root-only record identifies the revocation target; it grants no SDK authority.
        val identity = checkNotNull(RecoveryIdentity.of(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID),
            context.packageName, installedOwner.fullUid,
        ))
        val write = client.runRootCommand(RecoveryIdentityScripts.write(identity))
        assertTrue("identity write receipt", write.succeeded)
        assertTrue("identity permissions were checked by the production script",
            client.decodeText(write.string("result")).orEmpty().lineSequence().any {
                it == ParasiteMarkers.IDENTITY_OK
            })
        val read = client.runRootCommand(RecoveryIdentityScripts.read())
        assertTrue("identity read receipt", read.succeeded)
        assertEquals(identity, RecoveryIdentity.parse(client.decodeText(read.string("result"))))
        val after = client.queryManagerOwnerIdentity()
        assertTrue("installed owner remains readable", after.succeeded)
        assertEquals("metadata repair preserves installed SDK identity", installedOwner, after.identity)
        instrumentation.sendStatus(2, Bundle().apply { putString("daemonIdentity", "verified; installed owner retained") })
    }

    private fun client(): UltraRuntimeClient {
        return UltraRuntimeClient()
    }

    private fun requireOptIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyLiteWebRuntime") == "true")
    }
}

