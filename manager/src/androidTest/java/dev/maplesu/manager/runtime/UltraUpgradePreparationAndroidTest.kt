package dev.maplesu.manager.runtime

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentUpgrade
import dev.maplesu.manager.runtime.ultra.UltraInstalledIdentityProbe
import dev.maplesu.manager.runtime.ultra.UltraSdkCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises the real upgrade preparation without submitting a Boot installation. */
class UltraUpgradePreparationAndroidTest {
    @Test fun runningEnvironmentReadsCurrentIdentityBeforeUpgradeSubmission() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyUpgradePreparation") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(UltraEnvironmentProbe.State.Running, UltraEnvironmentProbe.refresh(context))

        val current = UltraSdkCredentials.captureOwnerMarker(context)
        val client = UltraSdkCredentials.client(context, current)
        val observed = client.queryManagerOwnerIdentity()
        assertTrue("Current installation identity query failed: ${observed.receipt.sdkError}",
            observed.receipt.succeeded)
        val owner = requireNotNull(observed.identity) { "Current installation identity is absent" }
        assertEquals(android.os.Process.myUid(), owner.fullUid)
        UltraInstalledIdentityProbe.invalidate()
        val prepared = UltraEnvironmentUpgrade.readFacts(context)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("upgradePreparationOk", (prepared.running && !prepared.readUnknown && !prepared.quiescing).toString())
            putString("upgradeInstalledHelperSha256", prepared.installedHelperSha256)
            putString("upgradeObservationScope", prepared.observationScope)
        })
        assertTrue("Upgrade preparation did not confirm the running environment: $prepared",
            prepared.running && !prepared.readUnknown && !prepared.quiescing)
        assertNotNull("Upgrade preparation returned no live helper identity", prepared.installedHelperSha256)
        assertNotNull("Upgrade preparation returned no installation scope", prepared.observationScope)
        assertEquals(prepared.observationScope, UltraEnvironmentUpgrade.observationScope(context))
        assertTrue("Installation identity changed during upgrade preparation", current())
        assertEquals(owner, client.queryManagerOwnerIdentity().identity)
    }
}
