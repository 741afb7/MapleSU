package dev.maplesu.manager.ultra

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraInstalledIdentityProbe
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.Source
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live comparison; expected SHA/size come from an independently accepted helper artifact. */
@RunWith(AndroidJUnit4::class)
class UltraInstalledIdentityAndroidTest {
    @Test fun runningSdkReadsTheInstalledHelperIdentity() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifyUltraIdentity") == "true")
        val expectedSha = arguments.getString("expectedInstalledHelperSha256")
        val expectedSize = arguments.getString("expectedInstalledHelperSizeBytes")?.toLongOrNull()
        assumeTrue("Supply the canonical SHA from an independent accepted installed artifact",
            expectedSha != null && Regex("[0-9a-f]{64}").matches(expectedSha))
        assumeTrue("Supply that artifact's actual installed size", expectedSize != null && expectedSize >= 4096L)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(UltraEnvironmentProbe.State.Running, UltraEnvironmentProbe.refresh(context))
        val client = UltraRuntimeClient()
        UltraInstalledIdentityProbe.invalidate()
        val observed = UltraInstalledIdentityProbe.read(context, client, force = true)
        assertEquals(Source.Readback, observed.source)
        assertEquals(requireNotNull(UltraInstalledIdentityProbe.installedHelperPath()), observed.helperPath)
        assertEquals(expectedSha, observed.helperSha256)
        assertEquals(expectedSize, observed.helperSize)
    }
}
