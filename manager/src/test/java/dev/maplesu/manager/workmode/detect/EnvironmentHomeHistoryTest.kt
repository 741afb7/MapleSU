package dev.maplesu.manager.workmode.detect

import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.*
import org.junit.Test

class EnvironmentHomeHistoryTest {
    private fun snapshot(mode: WorkMode?, boot: String = "previous", dirty: Boolean = false) =
        EnvironmentSnapshot(mode, false, "ultra:running", boot, 1000L, dirty)

    @Test fun previousBootModesRemainHistoryWithoutBecomingCurrentInstallation() {
        for (mode in WorkMode.entries) {
            val previous = snapshot(mode, dirty = true)
            for (readiness in listOf(null, SuReadiness.UNKNOWN, SuReadiness.NOT_USABLE, SuReadiness.USABLE)) {
                val result = EnvironmentHomeProjection.decide(
                    EnvironmentHomeProjection.Facts(previous, "current", liveReadiness = readiness),
                )
                assertTrue(result.bootChanged)
                assertFalse(result.confirmedNotInstalled)
                assertNull(result.cachedMode)
                assertEquals(mode, result.previousMode)
            }
        }
    }

    @Test fun currentBootModesAreNotDuplicatedAsPreviousBootHistory() {
        for (mode in WorkMode.entries) {
            val result = EnvironmentHomeProjection.decide(
                EnvironmentHomeProjection.Facts(snapshot(mode, boot = "current"), "current"),
            )
            assertEquals(mode, result.cachedMode)
            assertNull(result.previousMode)
        }
    }

    @Test fun aHistoricalAbsenceDoesNotBecomeAnInstalledHistoryOrCurrentAbsence() {
        val result = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(snapshot(null), "current"),
        )
        assertTrue(result.bootChanged)
        assertFalse(result.confirmedNotInstalled)
        assertNull(result.cachedMode)
        assertNull(result.previousMode)
    }

    @Test fun missingBootIdentityDoesNotInventHistoricalOrCurrentInstallation() {
        val result = EnvironmentHomeProjection.decide(
            EnvironmentHomeProjection.Facts(snapshot(WorkMode.LITE), ""),
        )
        assertFalse(result.bootChanged)
        assertFalse(result.confirmedNotInstalled)
        assertNull(result.cachedMode)
        assertNull(result.previousMode)
    }
}
