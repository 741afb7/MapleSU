package dev.maplesu.manager.ultrakernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field-location contract: observer calls preserve callee-saved identities, not caller-saved values. */
class UltraKernelCommLocationTest {
    private fun image(member: Int = 160, extent: Int = 16, clamp: Boolean = true,
        terminator: Boolean = true, sameIndex: Boolean = true, callback: Boolean = false,
        source: Int = 20, direct: Boolean = false, beforeCallback: Boolean = false, taskRegister: Int = 19,
        conflicting: Boolean = false, boundInW: Boolean = false, history: Int = 0,
        observerConflict: Boolean = false, splitConflict: Boolean = false): Pair<ByteArray, UltraKernelSymbols> {
        val raw = ByteArray(0x2000)
        val a = UltraKernelAsm(0x100)
        a.emit(UltraKernelA64.movReg(taskRegister, 0))
        a.emit(UltraKernelA64.movReg(20, 1))
        a.emit(UltraKernelA64.addImm(21, taskRegister, 64))
        a.emit(UltraKernelA64.movReg(0, 21))
        a.blAbs(0x800)
        if (beforeCallback) a.emit(0xd63f0100.toInt()) // BLR X8
        a.emit(UltraKernelA64.addImm(23, taskRegister, member))
        if (direct) {
            a.emit(UltraKernelA64.movReg(0, 23))
            a.emit(UltraKernelA64.movReg(1, source))
            a.emit(UltraKernelA64.movz(2, extent))
            a.blAbs(0x900)
        } else {
            a.emit(UltraKernelA64.movReg(0, 20))
            a.blAbs(0x880)
            a.emit(UltraKernelA64.addImm(31, 0, extent - 1, setFlags = true, sub = true))
            a.emit(UltraKernelA64.movz(8, extent - 1, sf = if (boundInW) 0 else 1))
            a.emit(if (clamp) UltraKernelA64.csel(24, 0, 8, 3) else UltraKernelA64.movReg(24, 0))
            a.emit(UltraKernelA64.movReg(0, 23))
            a.emit(UltraKernelA64.movReg(1, source))
            a.emit(UltraKernelA64.movReg(2, 24))
            a.blAbs(0x8c0)
            if (terminator) a.emit(0x38206800 or ((if (sameIndex) 24 else 25) shl 16) or (23 shl 5) or 31)
        }
        for (index in 0 until history) {
            a.emit(UltraKernelA64.ldImm("ldr", 8, taskRegister, 512 + index * 8, 8))
            a.cbz(8, "observed$index")
            a.blr(10)
            a.mark("observed$index")
        }
        if (observerConflict) {
            a.emit(UltraKernelA64.ldImm("ldr", 8, taskRegister, 1024, 8))
            a.cbz(8, "observedConflict")
            a.blr(10)
            a.emit(UltraKernelA64.addImm(0, taskRegister, member + 32))
            a.emit(UltraKernelA64.movReg(1, 20)); a.emit(UltraKernelA64.movz(2, extent)); a.blAbs(0x900)
            a.mark("observedConflict")
        }
        if (splitConflict) {
            a.emit(UltraKernelA64.ldImm("ldr", 8, taskRegister, 1032, 8))
            a.cbz(8, "splitConflict")
            a.emit(UltraKernelA64.addImm(0, taskRegister, member + 32))
            a.emit(UltraKernelA64.movReg(1, 20)); a.emit(UltraKernelA64.movz(2, extent)); a.blAbs(0x900)
            a.mark("splitConflict")
        }
        if (conflicting) {
            a.emit(UltraKernelA64.addImm(0, taskRegister, member + 32))
            a.emit(UltraKernelA64.movReg(1, 20))
            a.emit(UltraKernelA64.movz(2, extent))
            a.blAbs(0x900)
        }
        if (callback) a.blAbs(0x940)
        a.emit(UltraKernelA64.movReg(0, 21))
        a.blAbs(0x840)
        a.emit(UltraKernelA64.RET)
        val code = a.finish()
        code.copyInto(raw, 0x100)
        val entries = mapOf("__set_task_comm" to 0x100, "end_setter" to 0x100 + code.size,
            "_raw_spin_lock" to 0x800, "_raw_spin_unlock" to 0x840, "strlen" to 0x880,
            "memcpy" to 0x8c0, "strlcpy" to 0x900, "observer" to 0x940)
        return raw to object : UltraKernelSymbols {
            override val imageSize = raw.size
            override val summary = "strict-comm-setter"
            override fun offsetOf(name: String) = entries[name]
            override fun isSymbolStart(value: Int) = value in entries.values
            override fun nameAt(offset: Int) = entries.entries.firstOrNull { it.value == offset }?.key
            override fun nextOffset(offset: Int) = entries.values.filter { it > offset }.minOrNull()
        }
    }
    private fun field(pair: Pair<ByteArray, UltraKernelSymbols>) =
        UltraKernelStructOffsets.runtimeAccessorField(pair.second, pair.first, "task_struct.comm")
    private fun rejected(pair: Pair<ByteArray, UltraKernelSymbols>, reason: String) {
        val error = assertThrows(UltraKernelGenerateException::class.java) { field(pair) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(reason))
    }
    @Test fun `bounded memcpy and matching indexed terminator derive target extent`() {
        for ((member, extent) in listOf(160 to 16, 256 to 24)) {
            val result = field(image(member, extent))
            assertEquals(member, result.offsetBytes)
            assertEquals(extent, result.widthBytes)
        }
    }
    @Test fun `generation resolves the verified bounded setter location`() {
        for (strict in listOf(false, true)) {
            val (raw, symbols) = image(member = 256)
            val fields = UltraKernelStructOffsets.resolve(symbols, raw,
                setOf(UltraKernelStructOffsets.COMM), emptyMap(), null, strict)
            assertEquals(256, fields.getValue(UltraKernelStructOffsets.COMM))
        }
    }
    @Test fun `generation rejects a setter with a different array extent`() {
        val (raw, symbols) = image(extent = 24)
        val error = assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelStructOffsets.resolve(symbols, raw,
                setOf(UltraKernelStructOffsets.COMM), emptyMap(), null, true)
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("STRUCT_COPY_EXTENT_UNPROVEN"))
    }
    @Test fun `generation retains the bounded setter terminator requirement`() {
        val (raw, symbols) = image(terminator = false)
        val error = assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelStructOffsets.resolve(symbols, raw,
                setOf(UltraKernelStructOffsets.COMM), emptyMap(), null, true)
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("STRUCT_COPY_EXTENT_UNPROVEN"))
    }
    @Test fun `W bound is zero extended before X width strlen clamp`() {
        for ((member, extent) in listOf(160 to 16, 256 to 24)) {
            val result = field(image(member, extent, boundInW = true))
            assertEquals(member, result.offsetBytes)
            assertEquals(extent, result.widthBytes)
        }
    }
    @Test fun `dead observer load histories merge under original budget`() =
        assertEquals(160, field(image(direct = true, history = 20)).offsetBytes)
    @Test fun `W bound without clamp stays unproven`() =
        rejected(image(boundInW = true, clamp = false), "STRUCT_COPY_EXTENT_UNPROVEN")
    @Test fun `live observer branch with extra copy remains a conflict`() =
        rejected(image(direct = true, observerConflict = true), "STRUCT_COMM_COPY_AMBIGUOUS")
    @Test fun `live no call branch with extra copy remains a conflict`() =
        rejected(image(direct = true, splitConflict = true), "STRUCT_COMM_COPY_AMBIGUOUS")
    @Test fun `constant sized string copy derives its own target extent`() {
        for (extent in listOf(16, 24)) assertEquals(extent, field(image(extent = extent, direct = true)).widthBytes)
    }
    @Test fun `unbounded source length does not qualify an array extent`() =
        rejected(image(clamp = false), "STRUCT_COPY_EXTENT_UNPROVEN")
    @Test fun `bounded copy without terminator does not prove member extent`() =
        rejected(image(terminator = false), "STRUCT_COPY_EXTENT_UNPROVEN")
    @Test fun `terminator index must be the exact copied bounded length`() =
        rejected(image(sameIndex = false), "STRUCT_INDEXED_ADDRESS_UNPROVEN")
    @Test fun `source must remain the actual setter string argument`() =
        rejected(image(source = 19), "STRUCT_COMM_COPY_ARGUMENTS")
    @Test fun `later opaque observer is not a field location precondition`() =
        assertEquals(160, field(image(callback = true)).offsetBytes)
    @Test fun `earlier opaque observer preserves actual callee saved task identity`() =
        assertEquals(160, field(image(beforeCallback = true)).offsetBytes)
    @Test fun `earlier opaque observer clobbers caller saved task identity`() =
        rejected(image(beforeCallback = true, taskRegister = 9), "STRUCT_COMM_TERMINATOR_ADDRESS")
    @Test fun `two actual task copy locations remain a conflict`() =
        rejected(image(direct = true, conflicting = true), "STRUCT_COMM_COPY_AMBIGUOUS")
}
