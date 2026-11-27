package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class UltraKernelBtfIntegerVariableTest {
    private class Symbols(override val imageSize: Int, private val end: Int,
                          private val addresses: List<Int>) : UltraKernelSymbols {
        override val summary = "integer-variable-target-fixture"
        override fun offsetOf(name: String): Int? = offsetsOf(name).firstOrNull()
        override fun offsetsOf(name: String): List<Int> = when (name) {
            "__start_BTF" -> listOf(0)
            "__stop_BTF" -> listOf(end)
            "cap_last_cap" -> addresses
            else -> emptyList()
        }
        override fun isSymbolStart(value: Int) = value in addresses
        override fun nextOffset(offset: Int): Int? = addresses.filter { it > offset }.minOrNull()
    }

    private data class Fixture(val raw: ByteArray, val end: Int, val address: Int) {
        fun read(addresses: List<Int> = listOf(address)) =
            UltraKernelBtf.integerVariable(raw, Symbols(raw.size, end, addresses), "cap_last_cap")
    }

    @Test fun `BTF variable reads its target scalar`() {
        assertEquals(40L, fixture(variable = true).read())
    }

    @Test fun `target symbol supplies integer when BTF omits the variable`() {
        assertEquals(40L, fixture().read())
    }

    @Test fun `missing variable and missing target symbol remain absent`() {
        assertNull(fixture().read(emptyList()))
    }

    @Test fun `multiple target addresses remain an explicit address error`() {
        val value = fixture()
        invalid("VARIABLE_ADDRESS:cap_last_cap") { value.read(listOf(value.address, value.address + 4)) }
    }

    @Test fun `symbol reads stay inside the target image`() {
        val value = fixture()
        for (address in listOf(-1, value.raw.size - 3, Int.MAX_VALUE))
            invalid("VARIABLE_BOUNDS:cap_last_cap") { value.read(listOf(address)) }
    }

    @Test fun `a declared incompatible BTF type is not replaced by raw bytes`() {
        invalid("VARIABLE_SCALAR:cap_last_cap") { fixture(variable = true, scalarBytes = 8).read() }
    }

    @Test fun `malformed BTF stays an error when a target symbol exists`() {
        val value = fixture()
        value.raw[0] = 0
        invalid("HEADER") { value.read() }
    }

    @Test fun `source returns actual value for caller capability comparison`() {
        assertEquals(63L, fixture(number = 63).read())
        assertEquals(64L, fixture(number = 64).read())
        assertEquals(0xffffffffL, fixture(number = -1).read())
    }

    private fun invalid(detail: String, read: () -> Long?) {
        val error = assertThrows(UltraKernelGenerateException::class.java) { read() }
        assertEquals(UltraKernelGenerateCodes.STRUCT_OFFSET, error.code)
        assertTrue(error.detail, error.detail.contains(detail))
    }

    private fun fixture(variable: Boolean = false, scalarBytes: Int = 4, number: Int = 40): Fixture {
        val strings = "\u0000unsigned int\u0000cap_last_cap\u0000".toByteArray(Charsets.US_ASCII)
        val types = ByteArrayOutputStream()
        fun word(value: Int) { repeat(4) { types.write(value ushr (it * 8) and 255) } }
        word(1); word(1 shl 24); word(scalarBytes); word(scalarBytes * 8)
        if (variable) { word(14); word(14 shl 24); word(1); word(1) }
        val body = types.toByteArray()
        val end = 24 + body.size + strings.size
        val address = (end + 15) and -4
        val raw = ByteArray(address + 8)
        listOf(0x0001EB9F, 24, 0, body.size, body.size, strings.size).forEachIndexed { index, value ->
            UltraKernelA64.writeLe32(raw, index * 4, value.toLong())
        }
        body.copyInto(raw, 24)
        strings.copyInto(raw, 24 + body.size)
        UltraKernelA64.writeLe32(raw, address, number.toLong() and 0xffffffffL)
        return Fixture(raw, end, address)
    }
}
