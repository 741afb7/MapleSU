package com.maplesu.core.ui.screen.settings

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test

class UltraEnvironmentInstallEligibilityTest {
    @Test fun noPriorInformationStillAllowsManualCompleteInstallation() {
        assertTrue(environmentInstallEligible(null, null, false, false))
    }

    @Test fun historyAndAnUnboundUltraInstallationDoNotRequireActivation() {
        assertTrue(environmentInstallEligible(WorkMode.LITE, null, false, false))
        assertTrue(environmentInstallEligible(null, WorkMode.LITE, false, false))
        assertTrue(environmentInstallEligible(WorkMode.LITE, WorkMode.LITE, false, false))
    }

    @Test fun anExplicitKsuModeKeepsTheUltraInstallationEntryClosed() {
        for (other in listOf(null, WorkMode.LITE, WorkMode.KSU)) {
            assertFalse(environmentInstallEligible(WorkMode.KSU, other, false, false))
            assertFalse(environmentInstallEligible(other, WorkMode.KSU, false, false))
        }
    }

    @Test fun actualHidingAndPendingTransactionsKeepTheEntryClosed() {
        for (mode in listOf(null, WorkMode.LITE)) {
            assertFalse(environmentInstallEligible(mode, null, true, false))
            assertFalse(environmentInstallEligible(mode, null, false, true))
            assertFalse(environmentInstallEligible(mode, null, true, true))
        }
    }

    @Test fun onlyAnUnconfirmedUnknownHomeIdentityAsksForConfirmation() {
        assertTrue(environmentInstallNeedsConfirmation(null, false))
        assertFalse(environmentInstallNeedsConfirmation(null, true))
        for (mode in listOf(WorkMode.LITE, WorkMode.KSU)) {
            assertFalse(environmentInstallNeedsConfirmation(mode, false))
            assertFalse(environmentInstallNeedsConfirmation(mode, true))
        }
        // The display hint does not add an installation precondition.
        assertTrue(environmentInstallEligible(null, null, false, false))
    }
}
