package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class UltraKernelBtfFunctionTypeidsTest {
    private class Symbols(override val imageSize: Int, private val btf: Int, private val entries: List<Int>,
        private val alias: Boolean = false) : UltraKernelSymbols {
        override val summary = "function-typeid-static-names"
        override fun offsetOf(name: String) = offsetsOf(name).firstOrNull()
        override fun offsetsOf(name: String) = when (name) {
            "__start_BTF" -> listOf(btf)
            "_stext" -> listOf(4)
            "_etext" -> listOf(btf)
            "delayed_free" -> entries
            else -> emptyList()
        }
        override fun nameAt(offset: Int) = if (offset in entries && !alias) "delayed_free" else null
        override fun isSymbolStart(value: Int) = value in entries
        override fun nextOffset(offset: Int): Int? = null
    }
    private fun fixture(ids: List<Int>, alias: Boolean = false): Pair<ByteArray, Symbols> {
        val btf = 32
        val strings = "\u0000delayed_free\u0000".toByteArray()
        val body = ByteArrayOutputStream()
        fun word(value: Int) { repeat(4) { body.write(value ushr (it * 8) and 255) } }
        word(0); word(13 shl 24); word(0)
        word(1); word(12 shl 24); word(1)
        val types = body.toByteArray()
        val raw = ByteArray(btf + 24 + types.size + strings.size)
        ids.forEachIndexed { index, id -> UltraKernelA64.writeLe32(raw, 4 + index * 8, id.toLong()) }
        listOf(0x0001EB9F, 24, 0, types.size, types.size, strings.size).forEachIndexed { index, value ->
            UltraKernelA64.writeLe32(raw, btf + index * 4, value.toLong())
        }
        types.copyInto(raw, btf + 24); strings.copyInto(raw, btf + 24 + types.size)
        return raw to Symbols(raw.size, btf, ids.indices.map { 8 + it * 8 }, alias)
    }
    @Test fun `single typed address retains target prefix`() {
        val (raw, symbols) = fixture(listOf(123))
        assertEquals(setOf(123), UltraKernelBtf.functionTypeids(raw, symbols, setOf("fv()"))["fv()"])
    }
    @Test fun `same name and signature can have multiple actual definitions`() {
        val (raw, symbols) = fixture(listOf(123, 123))
        assertEquals(setOf(123), UltraKernelBtf.functionTypeids(raw, symbols, setOf("fv()"))["fv()"])
    }
    @Test fun `all actual address prefixes remain visible for caller disagreement checks`() {
        val (raw, symbols) = fixture(listOf(123, 456))
        assertEquals(setOf(123, 456), UltraKernelBtf.functionTypeids(raw, symbols, setOf("fv()"))["fv()"])
    }
    @Test fun `synthesized alias is not a type anchor`() {
        val (raw, symbols) = fixture(listOf(123), alias = true)
        assertThrows(UltraKernelGenerateException::class.java) { UltraKernelBtf.functionTypeids(raw, symbols, setOf("fv()")) }
    }
    @Test fun `missing typed target stays an error`() {
        val (raw, symbols) = fixture(emptyList())
        assertThrows(UltraKernelGenerateException::class.java) { UltraKernelBtf.functionTypeids(raw, symbols, setOf("fv()")) }
    }
    private fun managementSymbols(raw: ByteArray, end: Int): UltraKernelKallsymsTable {
        val names = hashMapOf("__start_BTF" to 32, "_stext" to 4, "_etext" to 24, "_data" to 24)
        val constructor = UltraKernelKallsymsTable::class.java.declaredConstructors.single { it.parameterCount == 15 }
        constructor.isAccessible = true
        return constructor.newInstance(raw.size, 0L, 0L, names.size, names, emptyMap<String, List<Int>>(),
            HashSet(names.values), names.values.distinct().sorted().toIntArray(), mapOf("_edata" to listOf(end)),
            intArrayOf(end), 0, 0, 0, 0, 0) as UltraKernelKallsymsTable
    }
    @Test fun `initialized data end is an exclusive memory boundary`() {
        val (raw, _) = fixture(listOf(123))
        val symbols = managementSymbols(raw, raw.size)
        assertTrue(symbols.offsetsOf("_edata").isEmpty())
        assertEquals(emptyMap<String, Set<Int>>(), UltraKernelBtf.managementCallbackTypeids(raw, symbols, emptyMap(), emptyMap()))
    }
    @Test fun `data end beyond loaded bytes retains bounds failure`() {
        val (raw, _) = fixture(listOf(123))
        val error = assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelBtf.managementCallbackTypeids(raw, managementSymbols(raw, raw.size + 1), emptyMap(), emptyMap())
        }
        assertTrue(error.detail, error.detail.contains("MANAGEMENT_SECTION_BOUNDS"))
    }
}
