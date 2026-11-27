package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class UltraKernelBtfSessionCallbackTest {
    private val prefix = 0xe866e2f4.toInt()
    private fun fixture(memberOffset: Int = 16, parameterIsFile: Boolean = true,
        selectDestination: Int = 21, logicalDestination: Int = 10,
        validGuard: Boolean = true): Pair<ByteArray, UltraKernelKallsymsTable> {
        val strings = ByteArrayOutputStream().apply { write(0) }
        fun name(value: String): Int = strings.size().also { strings.write(value.toByteArray()); strings.write(0) }
        val intName = name("int")
        val fileName = name("file")
        val operationsName = name("file_operations")
        val fopName = name("f_op")
        val readName = name("read")
        val iterName = name("read_iter")
        val callerName = name("vfs_read")
        val body = ByteArrayOutputStream()
        fun word(value: Int) { repeat(4) { body.write(value ushr (it * 8) and 255) } }
        fun record(label: Int, kind: Int, value: Int, count: Int = 0, vararg extra: Int) {
            word(label); word(kind shl 24 or count); word(value); extra.forEach(::word)
        }
        record(intName, 1, 4, 0, 0x01000020) // 1: int
        record(fileName, 4, 8, 1, fopName, 3, 0) // 2: file
        record(0, 2, 4) // 3: file_operations pointer
        record(operationsName, 4, 32, 2, readName, 5, 16 * 8, iterName, 5, 24 * 8) // 4
        record(0, 2, 6) // 5: callback pointer
        record(0, 13, 1, 1, 0, if (parameterIsFile) 7 else 1) // 6: caller/callback prototype
        record(0, 2, 2) // 7: file pointer
        record(callerName, 12, 6) // 8: vfs_read
        val typeBytes = body.toByteArray()
        val stringBytes = strings.toByteArray()
        val btf = 512
        val raw = ByteArray(btf + 24 + typeBytes.size + stringBytes.size)
        fun code(at: Int, value: Int) = UltraKernelA64.writeLe32(raw, at, value.toLong())
        code(64, 0xaa0003f4.toInt()) // mov x20, x0
        code(68, 0xf9400289.toInt()) // ldr x9, [x20]
        code(72, 0x32144be0 or logicalDestination) // orr wN, wzr, immediate
        code(76, 0xf9400128.toInt() or (memberOffset / 8 shl 10)) // callback member
        code(80, 0x9a8a3300.toInt() or selectDestination) // csel xN, x24, x10, cc
        code(84, 0xaa1403e0.toInt()) // mov x0, x20
        code(88, if (validGuard) 0xb85fc110.toInt() else 0xd503201f.toInt())
        code(92, 0x52800011 or ((prefix and 65535) shl 5))
        code(96, 0x72a00011 or ((prefix ushr 16) shl 5))
        code(100, 0x6b11021f)
        code(104, 0x54000040)
        code(108, 0xd4200000.toInt() or ((0x8000 or (17 shl 5) or 8) shl 5))
        code(112, 0xd63f0100.toInt())
        code(116, 0xd65f03c0.toInt())
        listOf(0x0001EB9F, 24, 0, typeBytes.size, typeBytes.size, stringBytes.size).forEachIndexed { at, value -> code(btf + at * 4, value) }
        typeBytes.copyInto(raw, btf + 24); stringBytes.copyInto(raw, btf + 24 + typeBytes.size)
        val names = hashMapOf("__start_BTF" to btf, "_stext" to 4, "_etext" to 256,
            "vfs_read" to 64, "caller_end" to 128)
        val constructor = UltraKernelKallsymsTable::class.java.declaredConstructors.single { it.parameterCount == 15 }
        constructor.isAccessible = true
        val symbols = constructor.newInstance(raw.size, 0L, 0L, names.size, names, emptyMap<String, List<Int>>(),
            HashSet(names.values), names.values.distinct().sorted().toIntArray(), emptyMap<String, List<Int>>(),
            intArrayOf(), 0, 0, 0, 0, 0) as UltraKernelKallsymsTable
        return raw to symbols
    }
    private fun typeids(fixture: Pair<ByteArray, UltraKernelKallsymsTable>): Set<Int> {
        val (raw, symbols) = fixture
        val signature = UltraKernelBtf.memberSignature(raw, symbols, "file_operations", "read")!!.removePrefix("*")
        return UltraKernelBtf.sessionCallbackTypeids(raw, symbols, mapOf("file_operations.read" to signature)).getValue("file_operations.read")
    }
    @Test fun `read type comes from the actual member guard across unrelated select and logical writes`() {
        assertEquals(setOf(prefix), typeids(fixture()))
    }
    @Test fun `same canonical callback type at another member is not a read anchor`() {
        assertThrows(UltraKernelGenerateException::class.java) { typeids(fixture(memberOffset = 24)) }
    }
    @Test fun `untyped source pointer is rejected`() {
        assertThrows(UltraKernelGenerateException::class.java) { typeids(fixture(parameterIsFile = false)) }
    }
    @Test fun `select overwriting the callback pointer is rejected`() {
        assertThrows(UltraKernelGenerateException::class.java) { typeids(fixture(selectDestination = 8)) }
    }
    @Test fun `logical write overwriting the operations pointer is rejected`() {
        assertThrows(UltraKernelGenerateException::class.java) { typeids(fixture(logicalDestination = 9)) }
    }
    @Test fun `missing native type guard is rejected`() {
        assertThrows(UltraKernelGenerateException::class.java) { typeids(fixture(validGuard = false)) }
    }
}
