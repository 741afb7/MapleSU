package dev.maplesu.manager.installer;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** Authenticated current-boot transactions. No caller-supplied shell commands or device paths. */
interface IInstallerService {
    Bundle observeBootChain();
    ParcelFileDescriptor openBootImage(out Bundle snapshot);
    Bundle prepareBootInstall(in ParcelFileDescriptor candidate, in ParcelFileDescriptor existingRecovery,
        in Bundle expectedSnapshot, String candidateSha256, String taskId);
    Bundle commitBootInstall(String taskId);
    Bundle cancelBootInstall(String taskId);
    Bundle getBootInstall(String taskId);
    Bundle verifyBootInstall(String taskId);
    Bundle restoreBootInstall(String taskId);
    boolean rebootAfterBootInstall(String taskId);

    // Mode-switch multi-partition transaction (02-switch-contract §5.1). Appended only: the nine
    // methods above, their Bundle keys and record.json schema stay untouched. Write bytes still go
    // through BlockDeviceTransaction inside the service.
    Bundle preflightModeSwitch(String switchId, in Bundle plan);
    Bundle beginModeSwitch(String switchId, in Bundle plan,
        in ParcelFileDescriptor superuserJson, in ParcelFileDescriptor settingsMirrorJson);
    Bundle snapshotModeSwitch(String switchId);
    ParcelFileDescriptor openSwitchImage(String switchId, String kind, String partition, out Bundle meta);
    Bundle submitSwitchCandidate(String switchId, String partition,
        in ParcelFileDescriptor candidate, String candidateSha256);
    Bundle commitModeSwitch(String switchId);
    Bundle rollbackModeSwitch(String switchId);
    Bundle abortModeSwitch(String switchId);
    Bundle getModeSwitch(String switchId);
    Bundle verifyModeSwitch(String switchId);
    boolean rebootAfterModeSwitch(String switchId);
    ParcelFileDescriptor openSwitchLog(String switchId);

    // First installation and reinstallation do not claim an existing MapleSU source mode.
    Bundle inspectStockInstall(String selectedManager);
    Bundle prepareNativeInstall(String taskId, String targetMode, String partition,
        boolean restoreOriginal, String selectedManager, boolean conflictAccepted);
    ParcelFileDescriptor openNativeInstallImage(String taskId, String partition, out Bundle meta);
    Bundle commitNativeInstall(String taskId, in ParcelFileDescriptor candidate, String candidateSha256);
    Bundle cancelNativeInstall(String taskId);
    Bundle restoreStockImages(String taskId, String targetMode);

    // Private mode-switch session. Token bytes and bounded frames stay on the returned FD.
    ParcelFileDescriptor openModeSwitchRoot(in ParcelFileDescriptor tokenFile, int timeoutMillis,
        inout Bundle identity);

    // Explicit user choice before installing Ultra; does not create a boot transaction.
    Bundle removeKsuModulesForUltra();

    // Fixed identity protocol, authenticated Manager only. Existing transaction IDs stay stable.
    Bundle readUltraIdentity(boolean withBuild);
    // Rebuild the saved package allowlist's current UIDs; adds no package grants.
    String refreshUltraAuthorization();

    // Opt-in AVB batch: companions share PRE, write-ahead journal and reverse rollback.
    Bundle prepareNativeSignedInstall(String taskId, String targetMode, String partition,
        boolean restoreOriginal, String selectedManager, boolean conflictAccepted, String signingMode);
    Bundle commitNativeSignedInstall(String taskId, in ParcelFileDescriptor candidate,
        String candidateSha256, in Bundle companionImages);

    // Appended read-only carrier snapshot; preserves all existing method transaction IDs.
    ParcelFileDescriptor openPartitionImage(String partition, out Bundle snapshot);
}
