package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UltraKernelResidentAsmAbiTest {
    private val name = "__arch_copy_from_user"
    private val signature = "fi8:64(*v;*v;i8:64;)"
    private val callbacks = setOf("handle_inode_event", "freeing_mark", "free_mark")
        .associate { "fsnotify_ops.$it" to "*fv()" }
    private class Symbols(override val imageSize: Int, val names: Map<String, List<Int>>) : UltraKernelSymbols {
        override val summary = "resident-asm-abi-fixture"
        override fun offsetOf(name: String) = names[name]?.firstOrNull()
        override fun offsetsOf(name: String) = names[name].orEmpty()
        override fun isSymbolStart(value: Int) = names.values.any { value in it }
        override fun nextOffset(offset: Int) = names.values.flatten().filter { it > offset }.minOrNull()
    }
    private data class Fixture(val kernel: ByteArray, val symbols: Symbols)
    private fun fixture(btfFunction: Boolean = false, wrongBtf: Boolean = false,
                        call: Boolean = true, callerBtf: Boolean = true, assembly: String = name): Fixture {
        val strings = ByteArrayOutputStream().apply { write(0) }
        val names = hashMapOf<String, Int>()
        fun text(value: String) = names.getOrPut(value) {
            strings.size().also { strings.write(value.toByteArray()); strings.write(0) }
        }
        val types = ByteArrayOutputStream()
        fun word(value: Int) { repeat(4) { types.write(value ushr (it * 8) and 255) } }
        fun type(name: String, kind: Int, value: Int, count: Int = 0) {
            word(if (name.isEmpty()) 0 else text(name)); word(kind shl 24 or count); word(value)
        }
        type("unsigned long", 1, 8); word(64) // 1
        type("", 2, 0) // 2 void pointer
        type("", 13, if (wrongBtf) 0 else if (assembly == "memchr") 2 else 1, 3) // 3
        word(0); word(2); word(0); word(if (assembly == "memchr") 7 else 2); word(0); word(1)
        type("", 13, 0) // 4 void function
        type("", 2, 4) // 5 function pointer
        type("fsnotify_ops", 4, 24, 3) // 6
        for ((index, member) in listOf("handle_inode_event", "freeing_mark", "free_mark").withIndex()) {
            word(text(member)); word(5); word(index * 64)
        }
        type("int", 1, 4); word(0x01000020) // 7
        if (btfFunction) type(assembly, 12, 3)
        if (callerBtf) type("fixture_caller", 12, 4)
        val body = types.toByteArray(); val text = strings.toByteArray()
        val raw = ByteArray(128 + 24 + body.size + text.size)
        listOf(0x0001EB9F, 24, 0, body.size, body.size, text.size).forEachIndexed { index, value ->
            UltraKernelA64.writeLe32(raw, 128 + index * 4, value.toLong())
        }
        body.copyInto(raw, 152); text.copyInto(raw, 152 + body.size)
        UltraKernelA64.writeLe32(raw, 16, (if (call) UltraKernelA64.encodeBl(16, 64) else UltraKernelA64.encodeB(16, 64)).toLong())
        val symbols = Symbols(raw.size, mapOf("_stext" to listOf(0), "_etext" to listOf(128),
            "fixture_caller" to listOf(16), assembly to listOf(64), "__start_BTF" to listOf(128),
            "__stop_BTF" to listOf(raw.size)))
        return Fixture(raw, symbols)
    }
    private fun verify(fixture: Fixture, proof: Map<String, String> = mapOf(name to signature),
                       functions: Map<String, String> = mapOf(name to signature)) =
        UltraKernelResidentAbi.verifyFunctions(fixture.kernel, fixture.symbols, functions, callbacks,
            assemblyFunctions = proof)

    @Test fun `missing assembly FUNC uses exact declaration and actual BTF caller`() = verify(fixture())
    @Test fun `missing assembly declaration retains original BTF failure`() {
        val error = assertThrows(UltraKernelGenerateException::class.java) { verify(fixture(), emptyMap()) }
        assertTrue(error.detail.contains("FUNCTION_MISSING:$name"))
    }
    @Test fun `existing BTF signature remains authoritative`() {
        val error = assertThrows(IllegalArgumentException::class.java) { verify(fixture(btfFunction = true, wrongBtf = true)) }
        assertTrue(error.message!!.contains("FUNCTION_MISMATCH:$name"))
    }
    @Test fun `arbitrary missing imports are never accepted as assembly`() {
        assertThrows(IllegalArgumentException::class.java) {
            verify(fixture(), mapOf("other_import" to signature), mapOf("other_import" to signature))
        }
    }
    @Test fun `wrong assembly declaration signature fails`() {
        assertThrows(IllegalArgumentException::class.java) { verify(fixture(), mapOf(name to "fv()")) }
    }
    @Test fun `branch without link is not a callsite`() {
        val error = assertThrows(IllegalArgumentException::class.java) { verify(fixture(call = false)) }
        assertTrue(error.message!!.contains("ASM_CALLSITE:$name"))
    }
    @Test fun `a text caller without BTF is insufficient`() {
        assertThrows(IllegalArgumentException::class.java) { verify(fixture(callerBtf = false)) }
    }
    @Test fun `multiple target addresses fail`() {
        val fixture = fixture()
        assertThrows(IllegalStateException::class.java) {
            verify(fixture.copy(symbols = Symbols(fixture.kernel.size, fixture.symbols.names + (name to listOf(64, 68)))))
        }
    }
    @Test fun `target outside actual text fails`() {
        val fixture = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            verify(fixture.copy(symbols = Symbols(fixture.kernel.size, fixture.symbols.names + (name to listOf(132)))))
        }
    }
    @Test fun `memchr assembly uses its declaration and actual target caller`() {
        val declaration = mapOf("memchr" to "f*v(*v;i4:16777248;i8:64;)")
        verify(fixture(assembly = "memchr"), declaration, declaration)
    }
    @Test fun `memchr without declaration retains BTF failure`() {
        val error = assertThrows(UltraKernelGenerateException::class.java) {
            verify(fixture(assembly = "memchr"), emptyMap(), mapOf("memchr" to "f*v(*v;i4:16777248;i8:64;)"))
        }
        assertTrue(error.detail.contains("FUNCTION_MISSING:memchr"))
    }
    @Test fun `memchr existing BTF remains authoritative`() {
        val declaration = mapOf("memchr" to "f*v(*v;i4:16777248;i8:64;)")
        val error = assertThrows(IllegalArgumentException::class.java) {
            verify(fixture(btfFunction = true, wrongBtf = true, assembly = "memchr"), declaration, declaration)
        }
        assertTrue(error.message!!.contains("FUNCTION_MISMATCH:memchr"))
    }
    @Test fun `ambiguous memchr target remains rejected`() {
        val declaration = mapOf("memchr" to "f*v(*v;i4:16777248;i8:64;)")
        val original = fixture(assembly = "memchr")
        assertThrows(IllegalStateException::class.java) {
            verify(original.copy(symbols = Symbols(original.kernel.size,
                original.symbols.names + ("memchr" to listOf(64, 68)))), declaration, declaration)
        }
    }
    private fun actualReference(family: String = "android15-6.6"): JsonObject {
        val root = File(System.getProperty("maplesu.asm.assets"))
        val manifest = Json.parseToJsonElement(File(root, "manifest.json").readText()).jsonObject
        val item = manifest.getValue("payloads").jsonObject.getValue(family).jsonObject
        val bytes = ByteArrayOutputStream()
        for (chunk in item.getValue("chunks").jsonArray) bytes.write(File(root, chunk.jsonObject.getValue("path").jsonPrimitive.content).readBytes())
        val stream = bytes.toByteArray().inputStream()
        val decoder = if (item.getValue("metadataEncoding").jsonPrimitive.content == "xz-chunks-v1")
            org.tukaani.xz.XZInputStream(stream, 32 * 1024) else GZIPInputStream(stream)
        val decoded = decoder.use { it.readBytes() }
        return Json.parseToJsonElement(decoded.decodeToString()).jsonObject.getValue("referenceAbi").jsonObject
    }
    @Test fun `real same-object evidence supplies four assembly contracts`() {
        assertEquals(mapOf("__arch_copy_from_user" to "fi8:64(*v;*v;i8:64;)",
            "__arch_copy_to_user" to "fi8:64(*v;*v;i8:64;)",
            "memmove" to "f*v(*v;*v;i8:64;)", "strchr" to "f*i1:8(*i1:8;i4:16777248;)"),
            UltraKernelManagerClaimPayload.assemblyDeclarations(actualReference()))
    }
    @Test fun `real 6_1 same-object evidence supplies memchr declaration`() {
        assertEquals("f*v(*v;i4:16777248;i8:64;)",
            UltraKernelManagerClaimPayload.assemblyDeclarations(actualReference("android14-6.1"))["memchr"])
    }
    @Test fun `missing actual relocation evidence fails`() {
        val reference = actualReference()
        val declarations = reference.getValue("declarationAbi").jsonObject
        val item = declarations.getValue(name).jsonObject
        val rows = item.getValue("declarations").jsonArray.map { row ->
            JsonObject(row.jsonObject + ("externalSymbolRelocations" to JsonArray(emptyList())))
        }
        val changed = JsonObject(reference + ("declarationAbi" to JsonObject(declarations +
            (name to JsonObject(item + ("declarations" to JsonArray(rows)))))))
        assertThrows(IllegalArgumentException::class.java) { UltraKernelManagerClaimPayload.assemblyDeclarations(changed) }
    }
}
