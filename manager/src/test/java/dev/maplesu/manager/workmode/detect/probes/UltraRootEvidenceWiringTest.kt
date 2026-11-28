package dev.maplesu.manager.workmode.detect.probes

import android.os.Process
import dev.maplesu.manager.runtime.ultra.ManagerOwnerRootProof
import dev.maplesu.manager.runtime.ultra.ManagerOwnerRootResult
import dev.maplesu.manager.runtime.ultra.NativeReceipt
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.workmode.detect.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraRootEvidenceWiringTest {
    @Test fun currentIdentityUsesOwnerOnlyEvidenceBeforeHealthOrRecovery() {
        val path = "src/main/java/dev/maplesu/manager/workmode/detect/probes/EnvironmentProbesAdapter.kt"
        val file = listOf(File(path), File("manager/$path")).first { it.isFile }
        val body = file.readText().substringAfter("override fun currentUltraFingerprint()").substringBefore("override fun ultraFingerprint()")
        val identity = body.indexOf("UltraEnvironmentProbe.observeFreshIdentity(context.applicationContext)")
        assertTrue("current identity must query the owner-only port", identity >= 0)
        assertTrue("a live Hook must not start a competing SDK query", body.indexOf("HookRootExecutor.production(") in 0 until identity)
        assertTrue("Normal keeps priority without probing Ultra", body.indexOf("if (ksuNormal() == true) return UltraIdentityObservation(null)") in 0 until identity)
        assertFalse("current channel reads must not recover credentials", body.contains("UltraIdentityRecovery.recover("))
        assertFalse("current identity must not use a retained snapshot", body.contains("currentSnapshot("))
        assertFalse("current identity must not run health or Root", body.contains("UltraEnvironmentProbe.observeFresh("))

        val recovery = file.readText().substringAfter("override fun ultraFingerprint()").substringBefore("override fun ksuNormal()")
        assertTrue("Unknown must not be polled again before credentials change",
            recovery.indexOf("UltraIdentityRecovery.recover(") in 0 until recovery.indexOf("UltraEnvironmentProbe.observeFresh("))
    }

    @Test fun ultraChannelIsWiredToSdkInsteadOfLegacyCredentialShell() {
        val path = "src/main/java/dev/maplesu/manager/workmode/detect/probes/RootEvidenceReaderAdapter.kt"
        val file = listOf(File(path), File("manager/$path")).first { it.isFile }
        val source = file.readText()
        assertFalse(source.contains("RootChannelKind.LITE_DIRECT, RootChannelKind.LITE_ULTRA ->"))
        assertTrue(source.contains("RootChannelKind.LITE_ULTRA ->"))
        assertTrue(source.contains("UltraRuntimeClient"))
        assertTrue(source.contains("client.runRootCommand(command)"))
        assertTrue(source.contains("UltraRootShellSession.open("))
    }

    @Test fun ownerOnlyPortKeepsBootActivationRemovalUidAndCancellationScoping() {
        val probePath = "src/main/java/dev/maplesu/manager/runtime/ultra/UltraEnvironmentProbe.kt"
        val probe = listOf(File(probePath), File("manager/$probePath")).first { it.isFile }.readText()
        val scope = probe.substringAfter("internal fun observeFreshIdentity(context: Context)")
            .substringBefore("private fun observe(context:")
        assertTrue(scope.contains("ModeRuntime.activation === activation"))
        assertTrue(scope.contains("captureOwnerMarker(app)"))
        assertTrue(scope.contains("UltraEnvironmentAutoSetup.currentBootId()"))
        assertTrue(scope.contains("UltraSdkCredentials.suspended(app)"))
        assertTrue(scope.contains("Thread.currentThread().isInterrupted"))
        assertTrue(scope.contains("it.fullUid == Process.myUid()"))
        assertFalse(scope.contains("probeLock"))
        assertFalse(scope.contains("environmentState()"))
        assertFalse(scope.contains("probeManagerOwnerRoot()"))
    }

    @Test fun freshOwnerFlowsThroughTheActualResolverAndProjectionWithoutGrantingRoot() {
        val owner = UltraRuntimeClient.ManagerOwnerIdentity(Process.myUid(), 7, "ab".repeat(16))
        var ownerReads = 0
        val receipt = requireNotNull(NativeReceipt.parse("""{"schemaVersion":1,"backend":"maplesu_ultra","bridgeVersion":1,"operation":"query_manager_owner_identity","ok":true,"sdkError":"OK","sdkErrorCode":0,"data":{}}"""))
        val observed = UltraEnvironmentProbe.observeFreshIdentity(current = { true }, queryOwner = {
            ownerReads++
            UltraRuntimeClient.ManagerOwnerIdentityResult(receipt, owner)
        })
        val probes = object : EnvironmentProbes {
            override fun ksuNormal() = false
            override fun currentUltraIdentity() = UltraIdentityObservation("ultra:owner", observed.managerOwnerIdentity)
            override fun suPresent(): Boolean = error("Current owner must short circuit ordinary SU")
            override fun ultraFingerprint(): String? = error("Current owner must short circuit recovery")
        }
        val outcome = EnvironmentResolver(probes, EnvironmentStateStore(InMemoryEnvironmentStatePersistence()))
            .resolve("boot")
        val result = EnvironmentDetectionProjection.override(outcome)
        assertEquals(1, ownerReads)
        assertEquals(owner, outcome.ultraOwnerIdentity)
        assertEquals(ModeState.MODE_A_INSTALLED_UNBOUND, result?.state)
        assertNull(result?.state?.activeMode)
        assertNull(result?.rootChannel)
        assertFalse(observed.rootAvailable)
        assertFalse(observed.sdkChannelAvailable)
    }

    @Test fun anUnconfirmedOrChangedOwnerCannotTurnHealthProofIntoARootCapability() {
        val owner = UltraRuntimeClient.ManagerOwnerIdentity(Process.myUid(), 7, "ab".repeat(16))
        val environment = requireNotNull(NativeReceipt.parse("""{"schemaVersion":1,"backend":"maplesu_ultra","bridgeVersion":1,"operation":"get_env_state","ok":true,"sdkError":"OK","sdkErrorCode":0,"data":{"state":"Running"}}"""))
        val root = ManagerOwnerRootResult(environment, ManagerOwnerRootProof(owner, "0000000000000001", 1), true)
        for (confirmed in listOf(null, owner.copy(epoch = 8), owner.copy(hostInstance = "cd".repeat(16)))) {
            val health = UltraEnvironmentProbe.healthSnapshot(environment, root, owner, confirmed)
            val adapter = UltraEnvironmentAdapter(
                readSnapshot = { error("Retained health must not replace this read") },
                refreshSnapshot = { health }, hookActive = { false },
                readManagerOwnerIdentity = { UltraEnvironmentProbe.Snapshot(managerOwnerIdentity = owner) },
            )
            val result = adapter.observe(allowRefresh = true, allowOwnerQuery = true)
            assertFalse(health.rootAvailable)
            assertFalse(health.sdkChannelAvailable)
            assertFalse(result.kernelRootVerified)
            assertEquals(UltraState.UNKNOWN, result.state)
        }
    }
}
