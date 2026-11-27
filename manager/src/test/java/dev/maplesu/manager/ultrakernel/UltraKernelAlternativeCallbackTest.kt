package dev.maplesu.manager.ultrakernel

import org.junit.Assert.assertThrows
import org.junit.Test

class UltraKernelAlternativeCallbackTest {
    private class Symbols(private val names: Map<String, Int>, override val imageSize: Int) : UltraKernelSymbols {
        override val summary = "alternative-callback-fixture"
        override fun offsetOf(name: String) = names[name]
        override fun isSymbolStart(value: Int) = value in names.values
        override fun nextOffset(offset: Int) = names.values.filter { it > offset }.minOrNull()
    }

    private data class Fixture(val kernel: ByteArray, val symbols: UltraKernelSymbols, val callback: Int)

    private fun fixture(callback: Int = 0x100, helper: Int = 0x200): Fixture {
        val kernel = ByteArray(0x600)
        // Actual 6.1 callback: signed LR/frame spills, callee-saved output/count,
        // outlined NOP generator, and a post-indexed four-byte output loop.
        val body = "3f2303d5fd7bbea9f44f01a9fd0300917f040071eb000054f30302aaf403032a" +
            "13530094940600f1604600b8a1ffff54f44f41a9fd7bc2a8bf2303d5c0035fd6"
        body.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(kernel, callback)
        word(kernel, callback + 32, UltraKernelA64.encodeBl((callback + 32).toLong(), helper.toLong()))
        for ((index, value) in listOf(0x528403e0, 0x72baa060, UltraKernelA64.RET).withIndex()) {
            word(kernel, helper + index * 4, value)
        }
        val symbols = Symbols(mapOf("alt_cb_patch_nops" to callback, "callback_end" to callback + 64,
            "aarch64_insn_gen_nop" to helper, "helper_end" to helper + 12), kernel.size)
        return Fixture(kernel, symbols, callback)
    }

    private fun word(kernel: ByteArray, at: Int, value: Int) =
        UltraKernelA64.writeLe32(kernel, at, value.toLong() and 0xffffffffL)

    private fun verify(fixture: Fixture, length: Int = 8) = UltraKernelStructOffsets.verifyAlternativeNops(
        fixture.symbols, fixture.kernel, fixture.callback, length)

    private fun rejects(change: (Fixture) -> Unit) {
        val fixture = fixture()
        change(fixture)
        assertThrows(UltraKernelGenerateException::class.java) { verify(fixture) }
    }

    @Test fun outlinedNopCallbackProvesEveryOutputWord() {
        for (length in listOf(4, 8, 16, 32)) verify(fixture(), length)
    }

    @Test fun functionLocationsComeFromSymbolsAndBranches() {
        verify(fixture(callback = 0x180, helper = 0x300), 16)
    }

    @Test fun callbackWithInlineNopConstantRetainsItsProof() {
        val fixture = fixture()
        // Generate the constant before the loop, then store/decrement the input count.
        word(fixture.kernel, fixture.callback + 24, 0x528403e0)
        word(fixture.kernel, fixture.callback + 28, 0x72baa060)
        word(fixture.kernel, fixture.callback + 32, 0xb8004440.toInt()) // str w0,[x2],#4
        word(fixture.kernel, fixture.callback + 36, 0x71000463) // subs w3,w3,#1
        word(fixture.kernel, fixture.callback + 40, 0x54ffffc1) // b.ne callback+32
        word(fixture.kernel, fixture.callback + 44, UltraKernelA64.NOP)
        verify(fixture, 16)
    }

    @Test fun aWrongHelperConstantIsRejected() = rejects {
        word(it.kernel, 0x200, 0x52800000)
    }

    @Test fun aHelperInputWriteIsRejected() = rejects {
        word(it.kernel, 0x200, 0xb9000000.toInt()) // str w0,[x0]
    }

    @Test fun aHelperCallIsRejected() = rejects {
        word(it.kernel, 0x200, UltraKernelA64.encodeBl(0x200, 0x400))
    }

    @Test fun aHelperTrapIsRejected() = rejects {
        word(it.kernel, 0x200, 0xd4200000.toInt())
    }

    @Test fun aDifferentOutputArgumentIsRejected() = rejects {
        word(it.kernel, it.callback + 24, 0xaa0103f3.toInt()) // mov x19,x1
    }

    @Test fun aGapBetweenOutputWordsIsRejected() = rejects {
        word(it.kernel, it.callback + 40, 0xb8008660.toInt()) // str w0,[x19],#8
    }

    @Test fun aMissingOutputWordIsRejected() = rejects {
        word(it.kernel, it.callback + 40, UltraKernelA64.NOP)
    }

    @Test fun aWideOutputStoreIsRejected() = rejects {
        word(it.kernel, it.callback + 40, 0xf8004660.toInt()) // str x0,[x19],#4
    }

    @Test fun anUnbalancedFrameIsRejected() = rejects {
        word(it.kernel, it.callback + 52, UltraKernelA64.NOP)
    }

    @Test fun aHelperWithoutTheMatchingSymbolIsRejected() = rejects {
        word(it.kernel, it.callback + 32, UltraKernelA64.encodeBl((it.callback + 32).toLong(), 0x204))
    }
}
