package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Synthetic ELF bytes exercise the actual parser; no module is loaded. */
class UltraInitBootCredentialDirectImportTest {
    private val targets = listOf("prepare_creds", "prepare_kernel_cred")
    private val aliases = targets.map { "msu_direct_target_$it" }

    @Test fun kernelCredentialConstructorUsesTheExistingExactAliasFlow() {
        val fixture = fixture()
        val original = fixture.bytes.clone()
        val calls = parse(fixture)
        assertEquals(targets.associateBy { "msu_direct_target_$it" }, property(calls, "getTargetsByImport"))
        assertEquals(UltraKernelSpec.sha256(fixture.marker), property(calls, "getMarkerSha256"))
        assertEquals(2, property(calls, "getVersion"))
        assertArrayEquals(original, fixture.bytes)
    }

    @Test fun kernelCredentialConstructorRequiresItsExactAlias() {
        rejected(fixture(aliases.last().replace("msu_direct_target_", "msu_direct_wrong__")),
            "ULTRA_INIT_LEGACY_DIRECT_MARKER")
    }

    @Test fun kernelCredentialConstructorRequiresTheDeclaredGlobalSymbol() {
        val fixture = fixture()
        ByteBuffer.wrap(fixture.bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(fixture.symbols + 48, 0)
        rejected(fixture, "ULTRA_INIT_LEGACY_DIRECT_SYMBOL_REQUIRED")
    }

    @Test fun kernelCredentialConstructorKeepsCallRelocationValidation() {
        val fixture = fixture()
        ByteBuffer.wrap(fixture.bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(fixture.relocations + 24 + 8, (2L shl 32) or 257)
        rejected(fixture, "ULTRA_INIT_LEGACY_DIRECT_RELOCATION:${aliases.last()}")
    }

    @Test fun kernelCredentialConstructorKeepsLegacyModeRequirement() {
        try {
            parse(fixture(), legacy = false)
            fail("Expected legacy mode rejection")
        } catch (error: IllegalStateException) {
            assertEquals("ULTRA_INIT_LEGACY_DIRECT_MARKER", error.message)
        }
    }

    private data class Fixture(val bytes: ByteArray, val symbols: Int, val relocations: Int, val marker: ByteArray)

    private fun fixture(kernelAlias: String = aliases.last()): Fixture {
        val labels = listOf("", ".shstrtab", ".strtab", ".symtab", ".text", ".rela.text", ".msu_direct_calls")
        val sectionNames = "\u0000" + labels.drop(1).joinToString("\u0000") + "\u0000"
        val symbolNames = "\u0000" + aliases.joinToString("\u0000") + "\u0000"
        val marker = ("MAPLESU_DIRECT_V2\u0000" + aliases.first() + "\u0000" + targets.first() + "\u0000" +
            kernelAlias + "\u0000" + targets.last() + "\u0000\u0000").toByteArray(Charsets.US_ASCII)
        fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        val symbols = buffer(24 * 3).apply {
            position(24)
            for (alias in aliases) {
                putInt(symbolNames.indexOf(alias + '\u0000')); put(0x10); put(0); putShort(0); putLong(0); putLong(0)
            }
        }.array()
        val relocations = buffer(24 * 2).apply {
            repeat(2) { index -> putLong(index * 4L); putLong(((index + 1L) shl 32) or 283); putLong(0) }
        }.array()
        val chunks = listOf(byteArrayOf(), sectionNames.toByteArray(), symbolNames.toByteArray(), symbols,
            buffer(8).apply { repeat(2) { putInt(0x94000000.toInt()) } }.array(), relocations, marker)
        val output = ByteArrayOutputStream().apply { write(ByteArray(64)) }
        val offsets = chunks.map { chunk ->
            while (output.size() % 8 != 0) output.write(0)
            output.size().also { output.write(chunk) }
        }
        while (output.size() % 8 != 0) output.write(0)
        val table = output.size()
        output.write(buffer(64 * chunks.size).apply {
            chunks.forEachIndexed { index, chunk ->
                putInt(if (index == 0) 0 else sectionNames.indexOf(labels[index] + '\u0000'))
                putInt(listOf(0, 3, 3, 2, 1, 4, 1)[index]); putLong(if (index == 4) 6 else 0); putLong(0)
                putLong(offsets[index].toLong()); putLong(chunk.size.toLong())
                putInt(if (index == 3) 2 else if (index == 5) 3 else 0)
                putInt(if (index == 3) 1 else if (index == 5) 4 else 0)
                putLong(8); putLong(if (index == 3 || index == 5) 24 else 0)
            }
        }.array())
        val bytes = output.toByteArray()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(127, 69, 76, 70, 2, 1, 1)); putShort(16, 1); putShort(18, 183)
            putInt(20, 1); putLong(40, table.toLong()); putShort(52, 64); putShort(58, 64)
            putShort(60, chunks.size.toShort()); putShort(62, 1)
        }
        return Fixture(bytes, offsets[3], offsets[5], marker)
    }

    private fun parse(fixture: Fixture, legacy: Boolean = true): Any {
        val type = Class.forName("dev.maplesu.manager.ultrakernel.UltraInitBootTargetResolver\$Module")
        val module = type.getDeclaredConstructor(ByteArray::class.java).apply { isAccessible = true }
            .newInstance(fixture.bytes)
        try {
            return type.getDeclaredMethod("legacyDirectCalls", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(module, legacy)
        } catch (error: InvocationTargetException) {
            throw requireNotNull(error.cause)
        }
    }

    private fun property(calls: Any, name: String): Any = calls.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(calls)

    private fun rejected(fixture: Fixture, expected: String) {
        val original = fixture.bytes.clone()
        try {
            parse(fixture)
            fail("Expected $expected")
        } catch (error: IllegalStateException) {
            assertEquals(expected, error.message)
        }
        assertArrayEquals(original, fixture.bytes)
    }
}
