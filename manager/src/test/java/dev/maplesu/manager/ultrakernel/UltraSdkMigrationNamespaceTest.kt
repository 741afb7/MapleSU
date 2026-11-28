package dev.maplesu.manager.ultrakernel

import org.junit.Assert.*
import org.junit.Test

class UltraSdkMigrationNamespaceTest {
    private fun legacy(vararg names: String): Pair<UltraKernelRecoveryRecord, ByteArray> {
        val replacement = ("prefix\u0000" + names.joinToString("\u0000") { "/data/$it/init" } + "\u0000tail").toByteArray()
        val original = ByteArray(replacement.size)
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        val plan = UltraKernelPlan.fromSpec(UltraKernelSpec.parse("#kernel=6.6.1-test\n8|${hex(original)}|${hex(replacement)}"))
        val current = ByteArray(8 + replacement.size).also { replacement.copyInto(it, 8) }
        return UltraKernelRecoveryRecord.fromPlan(plan, current.size, 1, 1) to current
    }

    @Test fun verifiedLegacyImageRetainsItsDataDirectoryWithoutAStoredCredential() {
        val (record, current) = legacy("Abcd1234Efgh5678")
        val recovered = UltraKernelRecoveryRecord.decode(record.encode()).deploymentNamespace(current)
        assertNotNull("Fresh owner identity must retain existing modules and authorizations", recovered)
        assertEquals("Abcd1234Efgh5678", recovered!!.name())
        assertEquals(24, recovered.record().size)
    }

    @Test fun identicalLegacyPathsResolveToTheSamePublicNamespace() {
        val (record, current) = legacy("Abcd1234Efgh5678", "Abcd1234Efgh5678")
        assertEquals("Abcd1234Efgh5678", record.deploymentNamespace(current)?.name())
    }

    @Test fun alteredOwnedBytesDoNotBecomeAMigrationSource() {
        val (record, current) = legacy("Abcd1234Efgh5678")
        current[12] = 3
        try { record.deploymentNamespace(current); fail("modified image accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun pathsOutsideVerifiedPatchRangesAreNotUsed() {
        val (record, current) = legacy()
        assertNull(record.deploymentNamespace(current))
    }

    @Test fun conflictingLegacyDirectoriesAreNotSilentlySelected() {
        val (record, current) = legacy("Abcd1234Efgh5678", "DifferentPath123")
        try { record.deploymentNamespace(current); fail("conflicting directories accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
