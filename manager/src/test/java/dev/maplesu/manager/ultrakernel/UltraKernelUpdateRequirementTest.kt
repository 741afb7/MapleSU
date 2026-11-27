package dev.maplesu.manager.ultrakernel

import org.junit.Assert.*
import org.junit.Test

class UltraKernelUpdateRequirementTest {
    private val credential = UltraKernelCredential.of("a".repeat(48)).withMetadataDisplacement(-208)
    private fun spec(bytes: Int, call: Int, invalid: Boolean = false, legacyKey: Boolean = false): UltraKernelSpec {
        val body = ByteArray(bytes)
        fun put(at: Int, word: Int) = UltraKernelA64.writeLe32(body, at, word.toLong() and 0xffffffffL)
        put(0, UltraKernelA64.RET)
        put(4, UltraKernelA64.addImm(17, 17, 16))
        put(8, UltraKernelA64.stpPre(11, 17, 31, -2))
        put(call, if (invalid) UltraKernelA64.NOP else UltraKernelA64.blr(10))
        val key = credential.copySecret().let { secret ->
            try { secret.toString(Charsets.US_ASCII) } finally { secret.fill(0) }
        }
        val encoded = body.joinToString("") { "%02x".format(it.toInt() and 255) }
        return UltraKernelSpec.parse("#kernel=6.6.89-fixture\n${if (legacyKey) "#key=$key\n" else ""}4096|${"00".repeat(bytes)}|$encoded\n")
    }

    @Test fun recognizedRetired140And180ExecutorsRequireKernelUpdates() {
        assertTrue(UltraKernelUpdateRequirement.required(spec(140, 0x40)))
        assertTrue(UltraKernelUpdateRequirement.required(spec(180, 0x68)))
    }

    @Test fun currentFutureUnknownMalformedAndUnpairedKernelsAreNotGuessedOld() {
        assertFalse(UltraKernelUpdateRequirement.required(spec(188, 0x40)))
        assertFalse(UltraKernelUpdateRequirement.required(spec(196, 0x40)))
        assertFalse(UltraKernelUpdateRequirement.required(null))
        assertFalse(UltraKernelUpdateRequirement.required(spec(140, 0x40, invalid = true)))
    }

    @Test fun aHistoricCredentialRequiresMigrationEvenWithTheCurrentExecutor() {
        assertTrue(UltraKernelUpdateRequirement.required(spec(188, 0x40, legacyKey = true)))
    }
}
