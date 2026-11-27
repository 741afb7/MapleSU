package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.UltraInitBootProducer
import kotlinx.serialization.json.*
import java.io.File
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Runs against a source-bound compact bundle supplied by the packaging build. */
class UltraInitBootCompactFactsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(): File {
        val path = System.getProperty("maplesu.compact.fixture")
        assumeTrue("compact packaging fixture is supplied by the packaging build", path != null)
        return File(requireNotNull(path)).also { check(File(it, "manifest.json").isFile) }
    }
    private fun copyFixture(): File = temporary.newFolder().also { output ->
        fixture().listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(output, it.name)) }
    }
    private fun parse(directory: File): UltraInitBootProducer.Artifact {
        val artifact = UltraInitBootProducer.readArtifact(File(directory, "manifest.json"))
        val method = UltraInitBootTargetResolver::class.java.getDeclaredMethod("referenceFacts", UltraInitBootProducer.Artifact::class.java)
            .apply { isAccessible = true }
        try { assertNotNull(method.invoke(UltraInitBootTargetResolver, artifact)) }
        catch (error: InvocationTargetException) { throw requireNotNull(error.cause) }
        return artifact
    }
    private fun change(directory: File, edit: (MutableMap<String, JsonElement>) -> Unit) {
        val manifestFile = File(directory, "manifest.json")
        val manifest = Json.parseToJsonElement(manifestFile.readText()).jsonObject.toMutableMap()
        val facts = File(directory, manifest.getValue("referenceFacts").jsonPrimitive.content)
        val changed = Json.parseToJsonElement(facts.readText()).jsonObject.toMutableMap().also(edit)
        val bytes = JsonObject(changed).toString().toByteArray()
        facts.writeBytes(bytes)
        manifest["referenceFactsSha256"] = JsonPrimitive(UltraKernelSpec.sha256(bytes))
        manifestFile.writeText(JsonObject(manifest).toString())
    }
    private fun rejected(edit: (MutableMap<String, JsonElement>) -> Unit, expected: String) {
        val copy = copyFixture(); change(copy, edit)
        val error = runCatching { parse(copy) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains(expected))
    }
    @Test fun publishedFactsParseWithoutReferenceImage() {
        val artifact = parse(fixture())
        assertTrue(artifact.isTargetTemplate); assertNull(artifact.targetBoot)
        assertFalse(File(fixture(), "target-boot.img").exists()); assertFalse(File(fixture(), "target.btf").exists())
    }
    @Test fun importerExtractsFactsAndNeverCopiesReferenceImage() {
        val original = fixture(); val privateDir = temporary.newFolder()
        val artifact = UltraInitBootAssets.load(privateDir, { path ->
            val file = File(original, path.removePrefix("ultra-init-bridge/"))
            ByteArrayInputStream(file.readBytes())
        })!!
        assertTrue(artifact.isTargetTemplate); assertNull(artifact.targetBoot)
        assertTrue(File(artifact.bundleDirectory, "reference-facts.json").isFile)
        assertFalse(privateDir.walkTopDown().any { it.name == "target-boot.img" || it.name == "target.btf" })
    }
    @Test fun unknownFactIsRejectedAfterRebindingItsHash() = rejected({ it["targetOffset"] = JsonPrimitive(16) }, "REFERENCE_FIELDS")
    @Test fun differentSourceIdentityIsRejectedAfterRebindingItsHash() = rejected({ it["referenceBootSha256"] = JsonPrimitive("f".repeat(64)) }, "REFERENCE_IDENTITY")
    @Test fun omittedSourceProofIsRejectedAfterRebindingItsHash() = rejected({
        it["sourceProofs"] = buildJsonObject { put("sessionAbiSha256", JsonNull); put("taskProfileAbiSha256", JsonNull) }
    }, "REFERENCE_PROOFS")
    @Test fun missingPrivateObservationIsRejectedAfterRebindingItsHash() = rejected({
        val row = it.getValue("privateSymbolPresence").jsonObject
        it["privateSymbolPresence"] = JsonObject(row - row.keys.first())
    }, "REFERENCE_PRIVATE_SET")
}
