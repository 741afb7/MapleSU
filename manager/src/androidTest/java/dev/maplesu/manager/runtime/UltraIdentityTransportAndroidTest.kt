package dev.maplesu.manager.runtime

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.installer.InstallerServiceClient
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.UltraSdkBuild
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the real caller-verified root Binder and live broker; credentials never enter test output. */
class UltraIdentityTransportAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rootTransportAttestsTheDeployedBrokerWithoutAnAppSocketConnection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyUltraTransport") == "true")
        val observed = InstallerServiceClient.withService(context, persistent = true) { it.readUltraIdentity(true) }
        assertEquals(0, observed.getInt("serviceUid", -1))
        assertEquals(Process.myUid(), observed.getInt("callerUid", -1))
        assertTrue(observed.getInt("servicePid", -1) > 1)
        assertTrue(observed.getBoolean("identityRequiresCallerQuery"))
        assertFalse("Root transport must not return a reusable credential", observed.containsKey("key"))
        val owner = requireNotNull(UltraRuntimeClient().queryManagerOwnerIdentity().identity)
        assertEquals(Process.myUid(), owner.fullUid)
        assertEquals(UltraSdkBuild.BUILD_ID, observed.getString("buildId"))
        assertFalse("Root transport must not fabricate an app identity", observed.containsKey("deploymentId"))
    }

    @Test fun restoringCurrentUidMappingsPreservesExactlyTheSavedPackageGrants() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyUltraTransport") == "true")
        val client = UltraRuntimeClient()
        val before = client.suAuthSnapshot()
        assertTrue(before.ok)
        val beforeProfiles = client.suUidProfiles()
        assertTrue(beforeProfiles.ok)
        val result = InstallerServiceClient.withService(context, persistent = true) { it.refreshUltraAuthorization() }
        assertEquals("OK", result)
        val after = client.suAuthSnapshot()
        assertTrue(after.ok)
        assertEquals(before.packages.toSet(), after.packages.toSet())
        val afterProfiles = client.suUidProfiles()
        assertTrue(afterProfiles.ok)
        assertEquals(beforeProfiles.profiles.associateBy { it.uid }, afterProfiles.profiles.associateBy { it.uid })
    }
}
