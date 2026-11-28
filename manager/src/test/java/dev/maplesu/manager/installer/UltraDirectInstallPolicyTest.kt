package dev.maplesu.manager.installer

import dev.maplesu.manager.runtime.ultra.UltraEnvFacts
import dev.maplesu.manager.runtime.ultra.UltraEnvUpgrade
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test

class UltraDirectInstallPolicyTest {
    private val direct = UltraDirectInstallRequest(WorkMode.LITE, InstallTargetMode.ULTRA,
        InstallSourceKind.CURRENT_PARTITION, "boot")

    @Test fun explicitInstallationDoesNotRequireAHistoricalUpdateVerdict() {
        val facts = listOf(UltraEnvFacts(), UltraEnvFacts(readUnknown = true),
            UltraEnvFacts(running = false, installedVersion = "5.2.1"),
            UltraEnvFacts(running = true, installedVersion = "5.2.1", bundledVersion = "5.2.1",
                identityKnown = true, installedHelperSha256 = "a".repeat(64), bundledHelperSha256 = "a".repeat(64)))
        for (partition in listOf("boot", "init_boot")) for (observation in facts) {
            var submits = 0
            assertEquals("INSTALL", UltraDirectInstallPolicy.dispatch(direct.copy(partition = partition),
                { observation }, { submits++; "INSTALL" }, { error("old records must not gate installation") },
                { error("the full installer owns carrier replacement") }))
            assertEquals(1, submits)
        }
    }

    @Test fun observationFailureStillReachesTheCompleteInstaller() {
        assertEquals("INSTALL", UltraDirectInstallPolicy.dispatch(direct,
            { error("READ_UNAVAILABLE") }, { "INSTALL" }, { error("metadata is optional") },
            { error("no standalone patch fallback") }))
    }

    @Test fun installerFailureOrLostReplyNeverStartsASecondOperation() {
        for (failure in listOf("FAILED", "UNVERIFIED", "PENDING")) {
            assertEquals(failure, UltraDirectInstallPolicy.dispatch(direct, { UltraEnvFacts() },
                { failure }, { error("no extra gate") }, { error("no second write") }))
        }
        assertTrue(runCatching { UltraDirectInstallPolicy.dispatch(direct, { UltraEnvFacts() },
            { error("LOST_INSTALL_REPLY") }, { error("no extra gate") }, { error("no second write") }) }.isFailure)
    }

    @Test fun anExistingTransactionOrExplicitDisableIsPreserved() {
        for (facts in listOf(UltraEnvFacts(suspended = true), UltraEnvFacts(quiescing = true))) {
            assertEquals("WAIT", UltraDirectInstallPolicy.dispatch(direct, { facts },
                { error("conflicting write") }, { "WAIT" }, { error("conflicting patch") }))
        }
        assertEquals("REBOOT", UltraDirectInstallPolicy.dispatch(direct,
            { UltraEnvFacts(submittedThisBoot = true, bundledVersion = "5.2.1") },
            { error("duplicate installation") }, { decision ->
                assertTrue(decision is UltraEnvUpgrade.Rebooting); "REBOOT"
            }, { error("duplicate patch") }))
    }

    @Test fun cancellationDoesNotBecomeAnInstallationRequest() {
        assertTrue(runCatching { UltraDirectInstallPolicy.dispatch(direct,
            { throw java.util.concurrent.CancellationException("cancelled") },
            { error("cancelled write") }, { "WAIT" }, { error("cancelled patch") }) }.exceptionOrNull()
            is java.util.concurrent.CancellationException)
    }

    @Test fun explicitImageChoiceAndOtherModesKeepTheSelectedImageOperation() {
        for (request in listOf(direct.copy(target = InstallTargetMode.NORMAL),
            direct.copy(activeMode = WorkMode.KSU), direct.copy(activeMode = null),
            direct.copy(source = InstallSourceKind.IMAGE), direct.copy(source = InstallSourceKind.DOWNLOADED_BOOT),
            direct.copy(defaultKernel = false), direct.copy(signing = true), direct.copy(bootOptions = true),
            direct.copy(forceRepatch = true))) {
            assertEquals("IMAGE", UltraDirectInstallPolicy.dispatch(request,
                { error("must not read SDK here") }, { error("must retain selected image operation") },
                { error("must retain selected image operation") }, { "IMAGE" }))
        }
    }

    @Test fun sameModeUltraPreservesModulesAndOtherModesKeepTheirChoice() {
        assertFalse(UltraDirectInstallPolicy.requiresModuleChoice(InstallTargetMode.ULTRA, WorkMode.LITE))
        assertTrue(UltraDirectInstallPolicy.requiresModuleChoice(InstallTargetMode.ULTRA, WorkMode.KSU))
        assertTrue(UltraDirectInstallPolicy.requiresModuleChoice(InstallTargetMode.ULTRA, null))
        assertFalse(UltraDirectInstallPolicy.requiresModuleChoice(InstallTargetMode.NORMAL, WorkMode.LITE))
    }
}
