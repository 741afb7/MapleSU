package dev.maplesu.manager.workmode.detect.probes

import dev.maplesu.manager.workmode.detect.KsuFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 探针 unknown 语义：native 不可用/版本不可读时不得造数据（02 §3.9、01 §2.7）。 */
class KsuProbeMappingTest {
    @Test
    fun fastNormalProbeRequiresManagerOwnershipLikeFullDetection() {
        val owned = KsuNativeSnapshot(true, 32650, 7, true, true)
        assertEquals(true, KsuProbeMapping.normalStatus(owned))
        assertEquals(false, KsuProbeMapping.normalStatus(owned.copy(isManager = false)))
        assertEquals(false, KsuProbeMapping.normalStatus(owned.copy(version = 0)))
        assertEquals(null, KsuProbeMapping.normalStatus(KsuNativeSnapshot.UNAVAILABLE))
    }

    @Test
    fun ultraCompatibilityVersionDoesNotEstablishNormalOwnership() {
        // manager_ksu_compat.c GET_INFO exposes 10940 with flags=0 and uapi=0.
        val compatible = KsuNativeSnapshot(true, 10940, 0, false, false)
        assertTrue(KsuProbeMapping.from(compatible).responded)
        assertFalse(KsuProbeMapping.from(compatible).isManager)
        assertEquals(false, KsuProbeMapping.normalStatus(compatible))
    }

    @Test
    fun unavailableSnapshotIsUnknown() {
        val facts = KsuProbeMapping.from(KsuNativeSnapshot.UNAVAILABLE)
        assertEquals(KsuFacts(), facts)
        assertFalse(facts.responded)
        assertEquals(-1, facts.version)
        assertEquals(-1, facts.uapi)
        assertFalse(facts.isManager)
        assertFalse(facts.isLkm)
    }

    @Test
    fun availableZeroVersionIsNotResponded() {
        val facts = KsuProbeMapping.from(
            KsuNativeSnapshot(available = true, version = 0, kernelUapi = 7, isManager = true, isLkm = true),
        )
        assertFalse(facts.responded)
        assertEquals(0, facts.version)
        // 版本不可读时仍透传其它已读到的字段，不伪造为 false/0。
        assertEquals(7, facts.uapi)
        assertTrue(facts.isManager)
        assertTrue(facts.isLkm)
    }

    @Test
    fun activeKernelMapsAllFields() {
        val facts = KsuProbeMapping.from(
            KsuNativeSnapshot(available = true, version = 32650, kernelUapi = 7, isManager = true, isLkm = true),
        )
        assertTrue(facts.responded)
        assertEquals(32650, facts.version)
        assertEquals(7, facts.uapi)
        assertTrue(facts.isManager)
        assertTrue(facts.isLkm)
    }

    @Test
    fun nonManagerKernelStillResponded() {
        val facts = KsuProbeMapping.from(
            KsuNativeSnapshot(available = true, version = 32650, kernelUapi = 7, isManager = false, isLkm = false),
        )
        assertTrue(facts.responded)
        assertFalse(facts.isManager)
    }

    @Test
    fun unavailableIsNotFabricatedAsForeign() {
        // unknown 的 version/uapi=-1 不得被当成"外来内核"证据；由 DetectionRules 的
        // responded=false 分支保证（R4 需要 responded）。
        assertEquals(-1, KsuProbeMapping.from(KsuNativeSnapshot.UNAVAILABLE).uapi)
        assertFalse(KsuProbeMapping.from(KsuNativeSnapshot.UNAVAILABLE).responded)
    }
}
