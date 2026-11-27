package dev.maplesu.manager.ultrakernel

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class UltraKernelTaskProfileProofSchemaTest {
    @Test fun imageProofContractContainsOnlyTheFiveConsumedProofs() {
        assertEquals("maplesu-task-profile-target-proof/3", UltraKernelTaskProfileTarget.PROOF_SCHEMA)
        assertEquals(linkedMapOf("slot63" to 1, "rcu" to 2, "bitops" to 4, "cfi" to 8,
            "syscallTracepoint" to 16), UltraKernelTaskProfileTarget.proofBits)
    }

    @Test fun targetTablePublishesMask31WithoutChangingItsNativeLayout() {
        val proof = proof()
        val bytes = UltraKernelTaskProfileTarget.targetBytes(proof)
        val words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(136, bytes.size)
        assertEquals(0x4d535450, words.getInt(0))
        assertEquals(1, words.getInt(4))
        assertEquals(136, words.getInt(8))
        assertEquals(1, words.getInt(12))
        assertEquals(31, words.getInt(16))
        assertEquals(63, words.getInt(24))
        assertEquals(proof.workMember, words.getInt(28))
        assertEquals(proof.workBit, words.getInt(32))
        assertEquals(0, words.getInt(36))
    }

    @Test fun missingConsumedProofDoesNotPublishATargetTable() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            UltraKernelTaskProfileTarget.targetBytes(proof().copy(missing = mapOf("cfi" to "missing")))
        }
        assertEquals("ULTRA_KERNEL_TASK_PROFILE_TARGET_INCOMPLETE", error.message)
    }

    private fun proof() = UltraKernelTaskProfileTarget.Proof("1".repeat(64), "2".repeat(64), "3".repeat(64),
        0, 10, emptyList(), JsonObject(emptyMap()), emptyMap())
}
