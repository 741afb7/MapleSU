package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class UltraKernelBtfCallerInstructionTest {
    private val prefix = 0x2af6cdbb
    private data class Fixture(val raw: ByteArray, val symbols: UltraKernelKallsymsTable, val member: String)

    private fun fixture(work: Boolean = false, operation: Int = 0x92407ea2.toInt(),
        validGuard: Boolean = true, conflictingCaller: Boolean = false): Fixture {
        val strings = ByteArrayOutputStream().apply { write(0) }
        fun name(value: String) = strings.size().also { strings.write(value.toByteArray()); strings.write(0) }
        val types = ByteArrayOutputStream()
        fun word(value: Int) { repeat(4) { types.write(value ushr (it * 8) and 255) } }
        fun record(label: String, kind: Int, value: Int, count: Int = 0, vararg extra: Int) {
            word(if (label.isEmpty()) 0 else name(label)); word(kind shl 24 or count); word(value); extra.forEach(::word)
        }
        val caller = if (work) "process_scheduled_works" else "__arm64_compat_sys_ioctl"
        val otherCaller = if (work) "process_one_work" else "compat_ptr_ioctl"
        val member = if (work) "work_struct.func" else "file_operations.compat_ioctl"
        if (work) {
            record("work_struct", 4, 32, 1, name("func"), 3, 24 * 8) // 1
            record("worker", 4, 32, 1, name("current_func"), 3, 24 * 8) // 2
            record("", 2, 4) // 3: callback pointer
            record("", 13, 0, 1, 0, 5) // 4: void callback(work_struct *)
            record("", 2, 1) // 5: work_struct pointer
            record("", 2, 2) // 6: worker pointer
            record("", 13, 0, 1, 0, 6) // 7: caller(worker *)
            record(caller, 12, 7) // 8
            if (conflictingCaller) record(otherCaller, 12, 7)
        } else {
            record("file", 4, 8, 1, name("f_op"), 2, 0) // 1
            record("", 2, 3) // 2: file_operations pointer
            record("file_operations", 4, 96, 1, name("compat_ioctl"), 4, 88 * 8) // 3
            record("", 2, 5) // 4: callback pointer
            record("", 13, 6, 3, 0, 7, 0, 8, 0, 9) // 5: long callback(file *, unsigned int, unsigned long)
            record("long", 1, 8, 0, 0x01000040) // 6
            record("", 2, 1) // 7: file pointer
            record("unsigned int", 1, 4, 0, 32) // 8
            record("unsigned long", 1, 8, 0, 64) // 9
            record(caller, 12, 5) // 10
            if (conflictingCaller) record(otherCaller, 12, 5)
        }
        val body = types.toByteArray(); val text = strings.toByteArray(); val btf = 1024
        val raw = ByteArray(btf + 24 + body.size + text.size)
        fun code(at: Int, value: Int) = UltraKernelA64.writeLe32(raw, at, value.toLong())
        fun callerCode(at: Int, typeid: Int) {
            if (work) {
                code(at, 0xaa0003f3.toInt()) // mov x19, x0
                code(at + 4, operation)
                code(at + 8, 0xf9400e68.toInt()) // ldr x8, [x19, #24]
                code(at + 12, 0xd503201f.toInt())
                code(at + 16, 0xd503201f.toInt())
                code(at + 20, 0xd503201f.toInt())
            } else {
                code(at, 0xf9400268.toInt()) // ldr x8, [x19]
                code(at + 4, 0xf9402d08.toInt()) // ldr x8, [x8, #88]
                code(at + 8, 0xb40001c8.toInt()) // cbz x8, beyond the call
                code(at + 12, 0xaa1303e0.toInt()) // mov x0, x19
                code(at + 16, operation)
                code(at + 20, 0x2a1603e1) // mov w1, w22
            }
            code(at + 24, if (validGuard) 0xb85fc110.toInt() else 0xd503201f.toInt())
            code(at + 28, 0x52800011 or ((typeid and 65535) shl 5))
            code(at + 32, 0x72a00011 or ((typeid ushr 16) shl 5))
            code(at + 36, 0x6b11021f)
            code(at + 40, 0x54000040)
            code(at + 44, 0xd4200000.toInt() or ((0x8000 or (17 shl 5) or 8) shl 5))
            code(at + 48, 0xd63f0100.toInt())
            code(at + 52, 0xd65f03c0.toInt())
        }
        callerCode(64, prefix)
        if (conflictingCaller) callerCode(192, prefix xor 1)
        listOf(0x0001EB9F, 24, 0, body.size, body.size, text.size).forEachIndexed { i, value -> code(btf + i * 4, value) }
        body.copyInto(raw, btf + 24); text.copyInto(raw, btf + 24 + body.size)
        val names = hashMapOf("__start_BTF" to btf, "_stext" to 4, "_etext" to 512,
            caller to 64, "caller_end" to 128)
        if (conflictingCaller) { names[otherCaller] = 192; names["other_end"] = 256 }
        val constructor = UltraKernelKallsymsTable::class.java.declaredConstructors.single { it.parameterCount == 15 }
        constructor.isAccessible = true
        val symbols = constructor.newInstance(raw.size, 0L, 0L, names.size, names, emptyMap<String, List<Int>>(),
            HashSet(names.values), names.values.distinct().sorted().toIntArray(), emptyMap<String, List<Int>>(),
            intArrayOf(), 0, 0, 0, 0, 0) as UltraKernelKallsymsTable
        return Fixture(raw, symbols, member)
    }
    private fun typeids(value: Fixture): Set<Int> {
        val signature = UltraKernelBtf.memberSignature(value.raw, value.symbols,
            value.member.substringBefore('.'), value.member.substringAfter('.'))!!.removePrefix("*")
        return UltraKernelBtf.sessionCallbackTypeids(value.raw, value.symbols,
            mapOf(value.member to signature)).getValue(value.member)
    }
    private fun rejected(value: Fixture): UltraKernelGenerateException =
        assertThrows(UltraKernelGenerateException::class.java) { typeids(value) }

    @Test fun `compat argument survives unrelated AND immediate`() =
        assertEquals(setOf(prefix), typeids(fixture()))
    @Test fun `AND immediate overwriting the file argument is rejected`() {
        rejected(fixture(operation = 0x92407ea0.toInt()))
    }
    @Test fun `worker pointer survives unrelated STLXR status write`() =
        assertEquals(setOf(prefix), typeids(fixture(work = true, operation = 0x880bfd28.toInt())))
    @Test fun `worker pointer survives unrelated STXR status write`() =
        assertEquals(setOf(prefix), typeids(fixture(work = true, operation = 0x880b7d28.toInt())))
    @Test fun `exclusive status W19 clobbers tracked X19`() {
        rejected(fixture(work = true, operation = 0x8813fd28.toInt()))
    }
    @Test fun `worker pointer survives unrelated exclusive load`() =
        assertEquals(setOf(prefix), typeids(fixture(work = true, operation = 0x885ffd28.toInt())))
    @Test fun `exclusive load W19 clobbers tracked X19`() {
        rejected(fixture(work = true, operation = 0x885ffd33.toInt()))
    }
    @Test fun `CAS is not classified as exclusive store`() {
        rejected(fixture(work = true, operation = 0x88abfd28.toInt()))
    }
    @Test fun `writeback to the worker base is rejected`() {
        rejected(fixture(work = true, operation = 0xb8004668.toInt()))
    }
    @Test fun `recognized instruction retains the native guard requirement`() {
        assertTrue(rejected(fixture(validGuard = false)).detail.contains("SESSION_CALLER_GUARD:"))
    }
    @Test fun `recognized instruction retains unique caller typeid requirement`() {
        assertTrue(rejected(fixture(conflictingCaller = true)).detail.contains("SESSION_CALLER_TYPEID_MISSING_OR_AMBIGUOUS:"))
    }
}
