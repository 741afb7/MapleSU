package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class UltraKernelProcattrSupplementalTest {
    private class Symbols(override val imageSize: Int, private val end: Int) : UltraKernelSymbols {
        override val summary = "procattr-supplemental-target"
        override fun offsetOf(name: String) = when (name) {
            "__start_BTF" -> 0
            "__stop_BTF" -> end
            else -> null
        }
        override fun isSymbolStart(value: Int) = false
        override fun nextOffset(offset: Int): Int? = null
    }
    private data class Fixture(val raw: ByteArray, val end: Int, val words: IntArray,
        val facts: UltraKernelResidentAbi.SupplementalFacts) {
        fun verify(facts: UltraKernelResidentAbi.SupplementalFacts = this.facts,
            bound: Map<String, UltraKernelResidentAbi.BoundField> = emptyMap()) =
            UltraKernelResidentAbi.verifyProcattr(raw, Symbols(raw.size, end), words, facts, true, bound)
    }

    @Test fun `native layout prefixes resolve both C type names`() {
        fixture().verify()
    }

    @Test fun `existing runtime binding retains target size and offset adaptation`() {
        fixture(operationsBytes = 40, writeOffset = 8).verify(bound = mapOf("file_operations.write" to
            UltraKernelResidentAbi.BoundField(0, 8, 32, UltraKernelLayoutField(8, 8, 40, "btf"))))
    }

    @Test fun `actual owner size disagreement stays a target type error`() {
        invalid("ULTRA_KERNEL_SUPPLEMENTAL_TARGET_TYPE:file_operations") { fixture(operationsBytes = 40).verify() }
    }

    @Test fun `native prefix alignment disagreement stays a target type error`() {
        val value = fixture()
        invalid("ULTRA_KERNEL_SUPPLEMENTAL_TARGET_TYPE:file_operations") {
            value.verify(value.facts.copy(typeAlignments = value.facts.typeAlignments + ("fileOperations" to 16)))
        }
    }

    @Test fun `missing native alignment stays a target type error`() {
        val value = fixture()
        invalid("ULTRA_KERNEL_SUPPLEMENTAL_TARGET_TYPE:file_operations") {
            value.verify(value.facts.copy(typeAlignments = value.facts.typeAlignments - "fileOperations"))
        }
    }

    @Test fun `actual write offset disagreement stays a target member error`() {
        invalid("ULTRA_KERNEL_SUPPLEMENTAL_TARGET_MEMBER:file_operations.write") { fixture(writeOffset = 8).verify() }
    }

    private fun invalid(message: String, verify: () -> Unit) {
        assertEquals(message, assertThrows(IllegalArgumentException::class.java, verify).message)
    }

    private fun fixture(operationsBytes: Int = 32, writeOffset: Int = 0): Fixture {
        val members = linkedMapOf("file" to linkedMapOf("f_op" to 5, "f_path" to 7, "f_inode" to 9),
            "path" to linkedMapOf("dentry" to 13), "file_operations" to linkedMapOf("write" to 17),
            "inode" to linkedMapOf("i_sb" to 21), "super_block" to linkedMapOf("s_magic" to 25),
            "dentry" to linkedMapOf("d_parent" to 29, "d_name" to 31), "qstr" to linkedMapOf("len" to 35, "name" to 37))
        val sizeWords = listOf(3, 11, 15, 19, 23, 27, 33)
        val words = IntArray(40)
        words[0] = 1; words[2] = 1; words[39] = 0x9fa0
        val strings = ByteArrayOutputStream().also { it.write(0) }
        fun name(value: String): Int = strings.size().also { strings.write(value.toByteArray()); strings.write(0) }
        val types = ByteArrayOutputStream()
        fun word(value: Int) { repeat(4) { types.write(value ushr (it * 8) and 255) } }
        word(name("unsigned long")); word(1 shl 24); word(8); word(64)
        word(name("unsigned int")); word(1 shl 24); word(4); word(32)
        for ((index, entry) in members.entries.withIndex()) {
            val (type, fields) = entry
            words[sizeWords[index]] = 32; words[sizeWords[index] + 1] = 8
            word(name(type)); word((4 shl 24) or fields.size); word(if (type == "file_operations") operationsBytes else 32)
            fields.entries.forEachIndexed { fieldIndex, (member, at) ->
                val bytes = if (member == "len") 4 else 8
                words[at] = fieldIndex * 8; words[at + 1] = bytes
                word(name(member)); word(if (bytes == 4) 2 else 1)
                word((if (type == "file_operations") writeOffset else fieldIndex * 8) * 8)
            }
        }
        val body = types.toByteArray(); val text = strings.toByteArray()
        val btf = ByteArray(24 + body.size + text.size)
        listOf(0x0001EB9F, 24, 0, body.size, body.size, text.size).forEachIndexed { index, value ->
            UltraKernelA64.writeLe32(btf, index * 4, value.toLong())
        }
        body.copyInto(btf, 24); text.copyInto(btf, 24 + body.size)
        val config = ByteArrayOutputStream().also { stream ->
            GZIPOutputStream(stream).use { it.write("CONFIG_PROC_FS=y\nCONFIG_SECURITY=y\n".toByteArray()) }
        }.toByteArray()
        val raw = btf + "IKCFG_ST".toByteArray() + config + "IKCFG_ED".toByteArray()
        val encoded = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { words.forEach { putInt(it) } }.array()
        val alignments = listOf("file", "path", "fileOperations", "inode", "superBlock", "dentry", "qstr").associateWith { 8 }
        val facts = UltraKernelResidentAbi.SupplementalFacts(1, 40, UltraKernelSpec.sha256(encoded),
            config = mapOf("CONFIG_PROC_FS" to "y", "CONFIG_SECURITY" to "y"), typeAlignments = alignments, targetQualified = true)
        return Fixture(raw, btf.size, words, facts)
    }
}
