package dev.maplesu.manager.runtime.ultra
import android.content.Context
import dev.maplesu.manager.workmode.ModeRuntime
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class UltraProtocolScopeTest {
 private val context=Context()
 @Before fun reset(){Fixture.reset()}
 private fun legacy(){Fixture.record="verified-fingerprint";Fixture.snapshot=UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running,true,legacyCredentialFingerprint=Fixture.record)}
 private fun owner(){Fixture.snapshot=UltraEnvironmentProbe.Snapshot(UltraEnvironmentProbe.State.Running,true,UltraRuntimeClient.ManagerOwnerIdentity(10123,2,"host"))}
 @Test fun verifiedLegacyPairsWithoutOwner(){legacy();assertTrue(UltraAutoPair.ensure(context).paired);assertEquals(1,Fixture.maintained)}
 @Test fun legacyScopeCarriesTheHistoricalCredential(){legacy();val scope=requireNotNull(UltraAuthorization.captureScope(context));assertEquals(Fixture.record,scope.client.legacyCredentialFingerprint);assertTrue(scope.current())}
 @Test fun ownerScopeKeepsOwnerWhenAStoredHistoricalRecordExists(){owner();Fixture.record="old-fingerprint";val scope=requireNotNull(UltraAuthorization.captureScope(context));assertNull(scope.client.legacyCredentialFingerprint);assertTrue(UltraAutoPair.ensure(context).paired)}
 @Test fun mismatchedLegacyObservationDoesNotProbeSu(){legacy();Fixture.record="changed-fingerprint";assertFalse(UltraAutoPair.ensure(context).paired);assertEquals(0,Fixture.suCalls)}
 @Test fun identityAbsentRetainsPending(){assertEquals("INSTALL_IDENTITY_PENDING",UltraAutoPair.ensure(context).reason);assertEquals(0,Fixture.suCalls)}
 @Test fun recordChangeInvalidatesExistingScope(){legacy();val scope=requireNotNull(UltraAuthorization.captureScope(context));Fixture.version++;assertFalse(scope.current())}
 @Test fun snapshotFingerprintChangeInvalidatesExistingScope(){legacy();val scope=requireNotNull(UltraAuthorization.captureScope(context));Fixture.snapshot=Fixture.snapshot.copy(legacyCredentialFingerprint="changed");assertFalse(scope.current())}
 @Test fun bootChangeInvalidatesScope(){legacy();val scope=requireNotNull(UltraAuthorization.captureScope(context));Fixture.boot="other";assertFalse(scope.current())}
 @Test fun activationChangeInvalidatesScope(){legacy();val scope=requireNotNull(UltraAuthorization.captureScope(context));ModeRuntime.activation=Any();assertFalse(scope.current())}
 @Test fun revokedDuringSuNeverRunsMaintenance(){legacy();Fixture.onSu={Fixture.version++};assertFalse(UltraAutoPair.ensure(context).paired);assertEquals(0,Fixture.maintained)}
 @Test fun legacyRefreshUsesTheSameCredential(){legacy();Fixture.su=false;assertFalse(UltraAutoPair.ensure(context).paired);assertEquals(1,Fixture.refreshCalls);assertEquals(Fixture.record,Fixture.selected);assertEquals(0,Fixture.maintained)}
 @Test fun rootUnverifiedNeverProbesSu(){legacy();Fixture.snapshot=Fixture.snapshot.copy(kernelRootVerified=false);assertFalse(UltraAutoPair.ensure(context).paired);assertEquals(0,Fixture.suCalls)}
 @Test fun verifiedLegacyMaintenanceUsesTheSameCredential(){legacy();UltraPairingMaintenanceProduction.request(context);assertEquals(1,Fixture.refreshCalls);assertEquals(Fixture.record,Fixture.selected)}
 @Test fun ownerMaintenanceKeepsOwnerWithHistoricalRecord(){owner();Fixture.record="old-fingerprint";UltraPairingMaintenanceProduction.request(context);assertEquals(1,Fixture.refreshCalls);assertNull(Fixture.selected)}
 @Test fun maintenanceRevokedBeforeWorkDoesNotSynchronize(){legacy();Fixture.onQueue={Fixture.version++};UltraPairingMaintenanceProduction.request(context);assertEquals(0,Fixture.refreshCalls)}

}
