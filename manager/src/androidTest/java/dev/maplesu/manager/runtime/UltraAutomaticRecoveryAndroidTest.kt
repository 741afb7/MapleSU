package dev.maplesu.manager.runtime

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraIdentityRecovery
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The host reinstalls the signed Manager with empty data before selecting this test. */
class UltraAutomaticRecoveryAndroidTest {
    @Test fun startupRestoresTheRunningUltraIdentityWithoutManualPairing() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyUltraRecovery") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val deadline = SystemClock.elapsedRealtime() + 45_000L
        fun ready(): Boolean = ModeRuntime.active == WorkMode.LITE &&
            UltraEnvironmentProbe.currentSnapshot(context).managerOwnerIdentity?.fullUid == Process.myUid()
        while (!ready() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(250L)

        val owner = requireNotNull(UltraEnvironmentProbe.currentSnapshot(context).managerOwnerIdentity) {
            "Startup did not recover the current signed caller identity"
        }
        assertEquals(Process.myUid(), owner.fullUid)
        assertEquals(WorkMode.LITE, ModeRuntime.active)
        val client = UltraRuntimeClient()
        assertEquals(owner, client.queryManagerOwnerIdentity().identity)
        assertEquals(0, UltraIdentityRecovery.uid(client))
        assertEquals(UltraEnvironmentProbe.State.Running, UltraEnvironmentProbe.refresh(context))
        instrumentation.sendStatus(2, Bundle().apply {
            putString("automaticRecoveryUid", Process.myUid().toString())
            putString("automaticRecoveryMode", ModeRuntime.active?.name)
            putString("automaticRecoveryRootUid", "0")
            putString("automaticRecoveryOwnerVerified", "true")
        })
    }
}
