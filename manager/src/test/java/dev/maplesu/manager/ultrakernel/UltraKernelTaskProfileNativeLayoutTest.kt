package dev.maplesu.manager.ultrakernel

import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real module DWARF and raw Images, supplied explicitly by the host acceptance run. */
class UltraKernelTaskProfileNativeLayoutTest {
    @Test fun nativeLayoutKeepsItsModuleOriginAndActualMissingMember() = fixtures().forEach { fixture ->
        val native = fixture.implementation.nativeLayout!!
        assertEquals("module-dwarf-derived-btf", native.getValue("origin").jsonPrimitive.content)
        assertFalse(native.getValue("targetImageBtf").jsonPrimitive.boolean)
        val fields = native.getValue("fields").jsonObject
        assertFalse("thread_info.syscall_work" in fields)
        assertEquals(40, fields.getValue("tracepoint.iterator").jsonObject.getValue("offsetBytes").jsonPrimitive.int)
        assertEquals(24, fields.getValue("tracepoint_func.__size").jsonObject.getValue("widthBytes").jsonPrimitive.int)
    }

    @Test fun moduleLayoutRejectsTargetBtfRelabelingAndInvalidBounds() = fixtures().forEach { fixture ->
        val native = fixture.implementation.nativeLayout!!
        assertEquals("ULTRA_KERNEL_TASK_PROFILE_MODULE_NATIVE_LAYOUT_SOURCE", rejected {
            parse(fixture, JsonObject(native + ("targetImageBtf" to JsonPrimitive(true))))
        }.message)
        val fields = native.getValue("fields").jsonObject
        val flags = fields.getValue("thread_info.flags").jsonObject
        assertEquals("ULTRA_KERNEL_TASK_PROFILE_MODULE_NATIVE_LAYOUT_FIELD:thread_info.flags", rejected {
            parse(fixture, JsonObject(native + ("fields" to JsonObject(fields + ("thread_info.flags" to
                JsonObject(flags + ("offsetBytes" to JsonPrimitive(flags.getValue("ownerSizeBytes").jsonPrimitive.int))))))))
        }.message)
    }

    @Test fun noImageBtfNeedsTheVerifiedNormalLoaderAndPublishesFiveProofs() {
        val fixture = fixtures().first()
        assertTrue(fixture.symbols.offsetsOf("__start_BTF").isEmpty())
        assertEquals("ULTRA_KERNEL_TASK_PROFILE_TARGET_BTF", rejected {
            UltraKernelTaskProfileTarget.derive(fixture.kernel, fixture.symbols, fixture.implementation, "legacy-lto-cfi")
        }.message)
        val proof = UltraKernelTaskProfileTarget.derive(fixture.kernel, fixture.symbols, fixture.implementation, "legacy-lto-cfi", true)
        assertEquals(emptyMap<String, String>(), proof.missing)
        assertEquals(UltraKernelTaskProfileTarget.proofBits.keys, proof.evidence.getValue("proofs").jsonObject.keys)
        assertEquals("module-dwarf-derived-btf", proof.evidence.getValue("layoutOrigin").jsonPrimitive.content)
        assertFalse(proof.evidence.getValue("targetImageBtf").jsonPrimitive.boolean)
        assertEquals(136, UltraKernelTaskProfileTarget.targetBytes(proof).size)
    }

    @Test fun imageBtfUsesTheCurrentImageDespiteADifferentModuleLayout() {
        val fixture = fixtures().last()
        assertTrue(fixture.symbols.offsetsOf("__start_BTF").isNotEmpty())
        val native = fixture.implementation.nativeLayout!!
        val fields = native.getValue("fields").jsonObject
        val funcs = fields.getValue("tracepoint.funcs").jsonObject
        val changedFields = JsonObject(fields + ("tracepoint.funcs" to JsonObject(funcs + ("offsetBytes" to JsonPrimitive(0)))))
        val changed = JsonObject(native + ("fields" to changedFields))
        val expected = UltraKernelTaskProfileTarget.derive(fixture.kernel, fixture.symbols, fixture.implementation, "legacy-lto-cfi", true)
        val actual = UltraKernelTaskProfileTarget.derive(fixture.kernel, fixture.symbols,
            fixture.implementation.copy(nativeLayout = changed), "legacy-lto-cfi", true)
        assertEquals(emptyMap<String, String>(), actual.missing)
        assertEquals(expected.proofSha256, actual.proofSha256)
        assertEquals("target-image-btf", actual.evidence.getValue("layoutOrigin").jsonPrimitive.content)
    }

    @Test fun actualNativeIteratorsRejectChangedCallsAndRecordFields() = fixtures().forEach { fixture ->
        val layout = nativeLayout(fixture)
        val proof = iterator(fixture, fixture.kernel, layout)
        val changed = fixture.kernel.clone()
        UltraKernelA64.writeLe32(changed, proof.getValue("callOffset").jsonPrimitive.int, UltraKernelA64.NOP.toLong())
        assertEquals("ULTRA_KERNEL_TASK_PROFILE_TARGET_UNCHECKED_TRACE:LOOP_EDGES", rejected {
            iterator(fixture, changed, layout)
        }.message)
        val data = layout.fields.getValue("tracepoint_func.data")
        assertEquals("ULTRA_KERNEL_TASK_PROFILE_TARGET_UNCHECKED_TRACE:" +
            if (fixture == fixtures().first()) "PAIR_ARGUMENTS" else "CALL_ARGUMENTS", rejected {
            iterator(fixture, fixture.kernel, UltraKernelLayout(layout.fields +
                ("tracepoint_func.data" to data.copy(offsetBytes = data.offsetBytes + 8))))
        }.message)
    }

    private data class Fixture(val report: JsonObject, val kernel: ByteArray, val symbols: UltraKernelSymbols,
        val implementation: UltraKernelTaskProfileTarget.Implementation)

    private fun fixtures(): List<Fixture> {
        val property = System.getProperty("maplesu.legacyNativeLayoutFixtures")
        assumeTrue("Set maplesu.legacyNativeLayoutFixtures to real module-layout fixture receipts", !property.isNullOrBlank())
        return cache ?: Json.parseToJsonElement(File(requireNotNull(property)).readText()).jsonArray.map { element ->
            val row = element.jsonObject
            val report = Json.parseToJsonElement(File(row.getValue("metadata").jsonPrimitive.content).readText()).jsonObject
            val kernel = File(row.getValue("kernel").jsonPrimitive.content).readBytes()
            assertEquals(row.getValue("kernelSha256").jsonPrimitive.content, UltraKernelSpec.sha256(kernel))
            Fixture(report, kernel, UltraKernelKallsymsTable.decode(kernel), UltraKernelTaskProfileTarget.parseLegacyModule(report))
        }.also { cache = it }
    }

    private fun parse(fixture: Fixture, native: JsonObject) = UltraKernelTaskProfileTarget.parseLegacyModule(
        JsonObject(fixture.report + ("nativeLayout" to native)))

    private fun nativeLayout(fixture: Fixture) = UltraKernelLayout(fixture.implementation.nativeLayout!!.getValue("fields").jsonObject
        .mapValues { (_, value) -> value.jsonObject.let { UltraKernelLayoutField(it.getValue("offsetBytes").jsonPrimitive.int,
            it.getValue("widthBytes").jsonPrimitive.int, it.getValue("ownerSizeBytes").jsonPrimitive.int, "module-dwarf-derived-btf") } })

    private fun iterator(fixture: Fixture, kernel: ByteArray, layout: UltraKernelLayout): JsonObject {
        val method = UltraKernelTaskProfileTarget::class.java.getDeclaredMethod("verifyUncheckedTrace",
            ByteArray::class.java, UltraKernelSymbols::class.java, UltraKernelLayout::class.java).apply { isAccessible = true }
        try { return method.invoke(UltraKernelTaskProfileTarget, kernel, fixture.symbols, layout) as JsonObject }
        catch (error: InvocationTargetException) { throw requireNotNull(error.cause) }
    }

    private fun rejected(action: () -> Unit): RuntimeException {
        try { action(); fail("Expected rejection") } catch (error: RuntimeException) { return error }
        error("Expected rejection")
    }

    companion object { private var cache: List<Fixture>? = null }
}
