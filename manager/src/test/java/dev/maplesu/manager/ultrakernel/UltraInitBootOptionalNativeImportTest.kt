package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.UltraInitBootProducer
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual loader-template rebinding; the source image is read without copying it. */
class UltraInitBootOptionalNativeImportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): Pair<File, File> {
        val bundle = System.getProperty("maplesu.legacy.loader.fixture")
        val input = System.getProperty("maplesu.legacy.loader.input")
        assumeTrue("legacy loader bundle and actual target are supplied", bundle != null && input != null)
        return File(requireNotNull(bundle)) to File(requireNotNull(input))
    }

    private fun resolve(bundle: File, input: File): UltraInitBootProducer.Artifact =
        UltraInitBootTargetResolver.resolve(UltraInitBootProducer.readArtifact(File(bundle, "manifest.json")), input)

    private fun changedFacts(edit: (MutableMap<String, JsonElement>) -> Unit): Pair<File, File> {
        val (bundle, input) = fixture()
        val copy = temporary.newFolder()
        bundle.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(copy, it.name)) }
        val manifestFile = File(copy, "manifest.json")
        val manifest = Json.parseToJsonElement(manifestFile.readText()).jsonObject.toMutableMap()
        val factsFile = File(copy, manifest.getValue("referenceFacts").jsonPrimitive.content)
        val facts = Json.parseToJsonElement(factsFile.readText()).jsonObject.toMutableMap().also(edit)
        val bytes = JsonObject(facts).toString().toByteArray()
        factsFile.writeBytes(bytes)
        manifest["referenceFactsSha256"] = JsonPrimitive(UltraKernelSpec.sha256(bytes))
        manifestFile.writeText(JsonObject(manifest).toString())
        return copy to input
    }

    @Test fun presentOptionalWithoutCanonicalEntryDoesNotBlockModuleRebinding() {
        val (bundle, input) = fixture()
        val kernel = UltraKernelPlan.readKernel(input)
        val symbols = UltraKernelKallsymsTable.decode(kernel)
        assertEquals(1, symbols.offsetsOf("__d_path").size)
        assertTrue(symbols.offsetsOf("__d_path.cfi_jt").isEmpty())
        assertEquals(UltraKernelSpec.sha256(input), resolve(bundle, input).targetBootSha256)
    }

    @Test fun optionalSourceAbsenceDoesNotRequireTargetCanonicalEntry() {
        val (bundle, input) = changedFacts {
            val presence = it.getValue("privateSymbolPresence").jsonObject.toMutableMap()
            presence["__d_path"] = JsonPrimitive(false)
            it["privateSymbolPresence"] = JsonObject(presence)
        }
        assertEquals(UltraKernelSpec.sha256(input), resolve(bundle, input).targetBootSha256)
    }

    @Test fun missingRequiredSourceCanonicalEntryStillRejectsRebinding() {
        val (bundle, input) = changedFacts {
            val canonical = it.getValue("privateCanonicalSymbols").jsonArray
            assertTrue(canonical.any { name -> name.jsonPrimitive.content == "proc_pid_attr_write" })
            it["privateCanonicalSymbols"] = JsonArray(canonical.filter { name -> name.jsonPrimitive.content != "proc_pid_attr_write" })
        }
        val error = runCatching { resolve(bundle, input) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals("ULTRA_INIT_TEMPLATE_PRIVATE_CFI:proc_pid_attr_write", error?.message)
    }

    @Test fun corruptedRequiredTargetCanonicalEntryStillRejectsIdentity() {
        val (_, input) = fixture()
        val kernel = UltraKernelPlan.readKernel(input)
        val symbols = UltraKernelKallsymsTable.decode(kernel)
        val entry = UltraKernelLegacyCfi.canonicalEntry(kernel, symbols, "proc_pid_attr_write")
        val branch = entry.canonicalOffset
        kernel[branch] = 0x1f; kernel[branch + 1] = 0x20
        kernel[branch + 2] = 0x03; kernel[branch + 3] = 0xd5.toByte()
        val error = runCatching { UltraKernelLegacyCfi.canonicalEntry(kernel, symbols, "proc_pid_attr_write") }.exceptionOrNull()
        assertNotNull(error)
        assertEquals("LEGACY_CFI_CANONICAL_BRANCH:proc_pid_attr_write", error?.message)
    }
}
