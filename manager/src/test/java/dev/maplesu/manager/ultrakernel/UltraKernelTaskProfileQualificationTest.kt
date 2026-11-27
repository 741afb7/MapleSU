package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Qualification compatibility through the real task-profile metadata parser. */
class UltraKernelTaskProfileQualificationTest {
    @Test fun actualSchema3MetadataKeepsFivePendingImageProofs() {
        val facts = fixture().parse()
        assertFalse(facts.verified)
        assertNotNull(facts.implementation)
        assertEquals(UltraKernelTaskProfileTarget.proofBits.keys, facts.missing)
    }

    @Test fun schema3RejectsMissingProofOmissionAndTheRetiredSixthRequirement() {
        val fixture = fixture()
        for (missing in listOf(emptySet(), legacyProofs)) {
            rejected("ULTRA_KERNEL_TASK_PROFILE_QUALIFICATION_MISSING") {
                fixture.parse(JsonObject(fixture.report + ("missing" to strings(missing))))
            }
        }
    }

    @Test fun schema3RejectsAnExtraQualificationRecord() {
        val fixture = fixture()
        rejected("ULTRA_KERNEL_TASK_PROFILE_PROOFS") {
            fixture.parse(JsonObject(fixture.report + ("qualification" to buildJsonObject {
                put("cloneExec", buildJsonObject { put("verified", false) })
            })))
        }
    }

    @Test fun historicalSchema2StillRequiresSixBeforeItsExistingSchemaGate() {
        val fixture = fixture()
        val implementation = fixture.report.getValue("implementation").jsonObject
        val schema2 = JsonObject(fixture.report + ("implementation" to JsonObject(implementation +
            ("targetProofSchema" to JsonPrimitive("maplesu-task-profile-target-proof/2")))))
        rejected("ULTRA_KERNEL_TASK_PROFILE_QUALIFICATION_MISSING") { fixture.parse(schema2) }
        // The unchanged implementation parser rejects this historical schema later.
        rejected("ULTRA_KERNEL_TASK_PROFILE_IMPLEMENTATION_SCHEMA") {
            fixture.parse(JsonObject(schema2 + ("missing" to strings(legacyProofs))))
        }
    }

    @Test fun historicalUnqualifiedMetadataKeepsItsSixProofContract() {
        val fixture = fixture()
        val (report, payload) = fixture.legacy(false)
        val facts = fixture.parse(report, payload)
        assertFalse(facts.verified)
        assertNull(facts.implementation)
        assertEquals(legacyProofs, facts.missing)
        rejected("ULTRA_KERNEL_TASK_PROFILE_QUALIFICATION_MISSING") {
            fixture.parse(JsonObject(report + ("missing" to strings(UltraKernelTaskProfileTarget.proofBits.keys))), payload)
        }
    }

    @Test fun historicalVerifiedFlagDoesNotReplaceMissingProofs() {
        val fixture = fixture()
        val (report, payload) = fixture.legacy(true)
        rejected("ULTRA_KERNEL_TASK_PROFILE_QUALIFICATION_MISSING") { fixture.parse(report, payload) }
    }

    private data class Fixture(val manifest: JsonObject, val payload: JsonObject, val elf: ByteArray) {
        val reference = payload.getValue("referenceAbi").jsonObject
        val report = reference.getValue("taskProfileAbi").jsonObject
        private val cfi = payload.getValue("callbackCfi").jsonObject
        private val inputs = reference.getValue("coreLifecycleAbi").jsonObject.getValue("sourceSelection").jsonObject
            .getValue("files").jsonArray.map { it.jsonObject }.associateBy { it.getValue("logicalPath").jsonPrimitive.content }

        fun parse(report: JsonObject = this.report, payload: JsonObject = this.payload) = UltraKernelManagerClaimPayload.parseTaskProfile(
            report, manifest, payload, reference, inputs, elf, cfi.getValue("mode").jsonPrimitive.content,
            cfi.getValue("normalizeIntegers").jsonPrimitive.boolean)

        fun legacy(verified: Boolean): Pair<JsonObject, JsonObject> {
            val words = report.getValue("layoutValues").jsonArray.toMutableList()
            words[2] = JsonPrimitive(if (verified) 1 else 0)
            words[3] = JsonPrimitive(if (verified) 1 else 0)
            val bytes = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                .apply { words.forEach { putInt(it.jsonPrimitive.int) } }.array()
            val name = "msu_task_profile_compiled_layout"
            val layouts = payload.getValue("compiledLayouts").jsonObject
            val table = layouts.getValue(name).jsonObject
            return JsonObject(report - "implementation" + mapOf("layoutValues" to JsonArray(words),
                "compiledLayoutSha256" to JsonPrimitive(UltraKernelSpec.sha256(bytes)), "missing" to strings(legacyProofs))) to
                JsonObject(payload + ("compiledLayouts" to JsonObject(layouts +
                    (name to JsonObject(table + ("values" to JsonArray(words)))))))
        }
    }

    private fun fixture(): Fixture {
        val property = System.getProperty("maplesu.taskProfileQualificationAssets")
        assumeTrue("Set maplesu.taskProfileQualificationAssets to the actual claim assets", !property.isNullOrBlank())
        return cached ?: run {
            val root = File(requireNotNull(property), "ultra-manager-claim")
            val manifest = Json.parseToJsonElement(File(root, "manifest.json").readText()).jsonObject
            val encoded = manifest.getValue("payloads").jsonObject.getValue("android15-6.6").jsonObject
            val compressed = encoded.getValue("chunks").jsonArray.flatMap { chunk ->
                val item = chunk.jsonObject
                val bytes = File(root, item.getValue("path").jsonPrimitive.content).readBytes()
                assertEquals(item.getValue("sha256").jsonPrimitive.content, UltraKernelSpec.sha256(bytes))
                bytes.asIterable()
            }.toByteArray()
            val stream = ByteArrayInputStream(compressed)
            val decoder = if (encoded.getValue("metadataEncoding").jsonPrimitive.content == "xz-chunks-v1")
                org.tukaani.xz.XZInputStream(stream, 32 * 1024) else GZIPInputStream(stream)
            val decoded = decoder.use { it.readBytes() }
            assertEquals(encoded.getValue("decodedSha256").jsonPrimitive.content, UltraKernelSpec.sha256(decoded))
            val payload = Json.parseToJsonElement(decoded.toString(Charsets.UTF_8)).jsonObject
            val elf = File(root, "android15-6.6/claim.so").readBytes()
            assertEquals(encoded.getValue("sha256").jsonPrimitive.content, UltraKernelSpec.sha256(elf))
            Fixture(manifest, payload, elf).also { cached = it }
        }
    }

    private fun rejected(code: String, action: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { action() }
        assertEquals(code, error.message)
    }

    companion object {
        private var cached: Fixture? = null
        private val legacyProofs = linkedSetOf("slot63", "rcu", "bitops", "cfi", "syscallTracepoint", "cloneExec")
        private fun strings(values: Set<String>) = JsonArray(values.map(::JsonPrimitive))
    }
}
