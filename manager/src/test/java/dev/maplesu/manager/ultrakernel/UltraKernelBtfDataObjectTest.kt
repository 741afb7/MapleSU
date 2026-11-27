package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class UltraKernelBtfDataObjectTest {
    private class Symbols(override val imageSize: Int) : UltraKernelSymbols {
        override val summary = "data-object-type-fixture"
        override fun offsetOf(name: String) = if (name == "__start_BTF") 0 else null
        override fun isSymbolStart(value: Int) = false
        override fun nextOffset(offset: Int): Int? = null
    }

    @Test fun `omitted variable record retains the full target mutex shape and size`() {
        val raw = fixture()
        val symbols = Symbols(raw.size)
        val signature = requireNotNull(UltraKernelBtf.typeSignature(raw, symbols, "mutex"))
        assertEquals(16L, UltraKernelBtf.dataObjectSizes(raw, symbols,
            mapOf("text_mutex" to signature))!!.getValue("text_mutex"))
        assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelBtf.variableSignatures(raw, symbols, setOf("text_mutex"))
        }
    }

    @Test fun `declared variable type disagreement remains an error`() {
        val raw = fixture(variableType = 1)
        val symbols = Symbols(raw.size)
        val signature = requireNotNull(UltraKernelBtf.typeSignature(raw, symbols, "mutex"))
        assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelBtf.dataObjectSizes(raw, symbols, mapOf("text_mutex" to signature))
        }
    }

    @Test fun `omitted variable record does not accept a different target shape`() {
        val expected = fixture().let { UltraKernelBtf.typeSignature(it, Symbols(it.size), "mutex")!! }
        val target = fixture(memberOffset = 8)
        assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelBtf.dataObjectSizes(target, Symbols(target.size), mapOf("text_mutex" to expected))
        }
    }

    @Test fun `declared matching variable and graph fallback resolve the same size`() {
        val raw = fixture(variableType = 2)
        val symbols = Symbols(raw.size)
        val signature = requireNotNull(UltraKernelBtf.typeSignature(raw, symbols, "mutex"))
        assertEquals(16L, UltraKernelBtf.dataObjectSizes(raw, symbols,
            mapOf("text_mutex" to signature))!!.getValue("text_mutex"))
    }

    private fun fixture(variableType: Int? = null, memberOffset: Int = 0): ByteArray {
        val strings = ByteArrayOutputStream().also { it.write(0) }
        fun name(value: String): Int {
            val offset = strings.size()
            strings.write(value.toByteArray(Charsets.US_ASCII)); strings.write(0)
            return offset
        }
        val types = ByteArrayOutputStream()
        fun word(value: Int) { repeat(4) { types.write(value ushr (it * 8) and 255) } }
        word(name("unsigned long")); word(1 shl 24); word(8); word(64)
        word(name("mutex")); word((4 shl 24) or 1); word(16)
        word(name("owner")); word(1); word(memberOffset * 8)
        if (variableType != null) { word(name("text_mutex")); word(14 shl 24); word(variableType); word(1) }
        val body = types.toByteArray(); val text = strings.toByteArray()
        return ByteArray(24 + body.size + text.size).also { raw ->
            listOf(0x0001EB9F, 24, 0, body.size, body.size, text.size).forEachIndexed { index, value ->
                UltraKernelA64.writeLe32(raw, index * 4, value.toLong())
            }
            body.copyInto(raw, 24); text.copyInto(raw, 24 + body.size)
        }
    }
}
