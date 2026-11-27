package com.maplesu.core.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SELinuxCheckerTest {
    @Test fun ordinaryFactDoesNotWaitForBackendOrOwnerIdentity() {
        // These reads succeed without initializing Android, an SDK or a root source.
        assertEquals("Enforcing", getSELinuxStatusRaw { "1\n" })
        assertEquals("Permissive", getSELinuxStatusRaw { "0\n" })
    }

    @Test fun unresolvedModeLocalReadKeepsKnownStatusAndDeniedReadUnknown() {
        assertEquals("Enforcing", getSELinuxStatusLocal { "1\n" })
        assertEquals("Permissive", getSELinuxStatusLocal { "0\n" })
        assertEquals("Unknown", getSELinuxStatusLocal { throw IOException("Permission denied") })
        assertEquals("Unknown", getSELinuxStatusLocal { "" })
        assertEquals("Unknown", getSELinuxStatusLocal { "2" })
    }

    @Test fun readsEnforcingAndPermissiveWithoutStartingAShell() {
        assertEquals("Enforcing", resolveSELinuxStatus({ "1\n" }, { error("Unexpected shell") }))
        assertEquals("Permissive", resolveSELinuxStatus({ "0\n" }, { error("Unexpected shell") }))
    }

    @Test fun deniedAppReadUsesTheAuthenticatedQuery() {
        for (status in listOf("Enforcing", "Permissive", "Disabled")) {
            assertEquals(status, resolveSELinuxStatus({ throw IOException("Permission denied") }, { "$status\n" }))
        }
    }

    @Test fun missingOrMalformedFileIsNotEvidenceOfDisabledSelinux() {
        for (value in listOf("", "2", "denied")) {
            assertEquals("Enforcing", resolveSELinuxStatus({ value }, { "Enforcing" }))
            assertEquals("Unknown", resolveSELinuxStatus({ value }, { "" }))
        }
    }

    @Test fun queryFailureAndUnexpectedOutputRemainUnknown() {
        assertEquals("Unknown", resolveSELinuxStatus({ throw IOException() }, { throw IOException() }))
        for (value in listOf("1", "0", "Enforcing\nextra", "Permission denied")) {
            assertEquals("Unknown", resolveSELinuxStatus({ "" }, { value }))
        }
    }
}
