package dev.maplesu.manager.runtime

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.ultra.UltraIdentityRecovery
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Selected after booting a candidate with the packaged manager claim carrier. */
class UltraManagerIdentityClaimAndroidTest {
    @Test fun keylessBridgeRecoversTheRunningKernelIdentityWithoutElevatingItsCaller() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("managerClaimKernel") == "true")
        val caller = Process.myUid()
        assertTrue(caller % 100_000 in 10_000..19_999)
        val client = UltraRuntimeClient()
        val recovered = requireNotNull(client.queryManagerOwnerIdentity().identity) { "Kernel owner identity reply absent" }
        assertEquals(caller, recovered.fullUid)
        assertTrue(recovered.epoch in 1L..0xffff_ffffL)
        assertTrue(recovered.hostInstance.matches(Regex("[0-9a-f]{32}")))
        assertFalse(recovered.hostInstance.all { it == '0' })
        assertEquals("Repeated kernel identity mismatch", recovered, client.queryManagerOwnerIdentity().identity)
        assertEquals(0, UltraIdentityRecovery.uid(client))
        assertEquals(caller, Process.myUid())
    }
}
