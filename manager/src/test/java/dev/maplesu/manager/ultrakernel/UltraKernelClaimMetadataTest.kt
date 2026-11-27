package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.MemoryLimitException
import org.tukaani.xz.XZOutputStream

class UltraKernelClaimMetadataTest {
    private val family = "android15-6.6"
    private val elf = byteArrayOf(1, 2, 3)
    private val payload = buildJsonObject {
        put("bytes", elf.size); put("sha256", UltraKernelSpec.sha256(elf))
        put("facts", buildJsonObject { put("name", "完整 ABI"); put("values", JsonArray((0..20).map(::JsonPrimitive))) })
    }

    private data class Fixture(val manifest: JsonObject, val files: Map<String, ByteArray>) {
        fun field(name: String, value: JsonElement): Fixture {
            val payloads = manifest.getValue("payloads").jsonObject
            val family = payloads.keys.single()
            val changed = JsonObject(payloads.getValue(family).jsonObject + (name to value))
            return copy(manifest = JsonObject(manifest + ("payloads" to JsonObject(mapOf(family to changed)))))
        }
    }

    private fun fixture(codec: String = "xz", raw: ByteArray = payload.toString().toByteArray(),
        encoded: ByteArray? = null, chunkSize: Int = 512 * 1024): Fixture {
        val compressed = encoded ?: ByteArrayOutputStream().also { output ->
            if (codec == "xz") XZOutputStream(output, LZMA2Options(6)).use { it.write(raw) }
            else GZIPOutputStream(output).use { it.write(raw) }
        }.toByteArray()
        val files = linkedMapOf<String, ByteArray>()
        val chunks = (compressed.indices step chunkSize).mapIndexed { index, start ->
            val bytes = compressed.copyOfRange(start, minOf(start + chunkSize, compressed.size))
            val path = "$family/metadata-$index.part"
            files[path] = bytes
            buildJsonObject { put("path", path); put("bytes", bytes.size); put("sha256", UltraKernelSpec.sha256(bytes)) }
        }
        val descriptor = buildJsonObject {
            put("metadataEncoding", "$codec-chunks-v1")
            put("decodedBytes", raw.size); put("decodedSha256", UltraKernelSpec.sha256(raw))
            put("${codec}Bytes", compressed.size); put("${codec}Sha256", UltraKernelSpec.sha256(compressed))
            put("bytes", elf.size); put("sha256", UltraKernelSpec.sha256(elf)); put("chunks", JsonArray(chunks))
        }
        return Fixture(buildJsonObject {
            put("schemaVersion", 2); put("payloads", JsonObject(mapOf(family to descriptor)))
        }, files)
    }

    private fun decode(fixture: Fixture): JsonObject {
        val companion = UltraKernelManagerClaimPayload.Companion
        val method = companion.javaClass.getDeclaredMethod("runtimeManifest", JsonObject::class.java,
            String::class.java, ByteArray::class.java, Function2::class.java).apply { isAccessible = true }
        val read: (String, Int) -> ByteArray = { name, limit -> fixture.files.getValue(name).also { require(it.size <= limit) } }
        try {
            return method.invoke(companion, fixture.manifest, family, elf, read) as JsonObject
        } catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun reject(code: String, fixture: Fixture): Throwable {
        val error = assertThrows(RuntimeException::class.java) { decode(fixture) }
        assertEquals("ULTRA_KERNEL_MANAGER_CLAIM_METADATA_$code", error.message)
        return error
    }

    @Test fun xzAndLegacyGzipPreserveCompleteFactsAndPayloadIdentity() {
        for (codec in listOf("xz", "gzip")) {
            val decoded = decode(fixture(codec)).getValue("payloads").jsonObject.getValue(family)
            assertEquals(payload, decoded)
        }
    }

    @Test fun chunkBoundariesDoNotChangeDecodedFacts() {
        assertEquals(payload, decode(fixture(chunkSize = 64)).getValue("payloads").jsonObject.getValue(family))
    }

    @Test fun pythonPublisherOutputPreservesAllFactsInTheRuntimeReader() {
        val property = System.getProperty("maplesu.claimMetadataAssets")
        assumeTrue("Set maplesu.claimMetadataAssets to publisher output", !property.isNullOrBlank())
        val root = File(requireNotNull(property))
        val manifest = Json.parseToJsonElement(File(root, "assets/manifest.json").readText()).jsonObject
        val descriptor = manifest.getValue("payloads").jsonObject.getValue(family).jsonObject
        val files = descriptor.getValue("chunks").jsonArray.associate { chunk ->
            val path = chunk.jsonObject.getValue("path").jsonPrimitive.content
            path to File(root, "assets/$path").readBytes()
        }
        val expected = Json.parseToJsonElement(File(root, "out/$family/runtime-payload-metadata.full.json").readText())
        assertEquals(expected, decode(Fixture(manifest, files)).getValue("payloads").jsonObject.getValue(family))
    }

    @Test fun legacyUnencodedManifestRemainsReadable() {
        val legacy = buildJsonObject { put("schemaVersion", 1); put("payloads", JsonObject(mapOf(family to payload))) }
        assertEquals(legacy, decode(Fixture(legacy, emptyMap())))
        reject("SCHEMA", fixture().copy(manifest = JsonObject(fixture().manifest + ("schemaVersion" to JsonPrimitive(1)))))
    }

    @Test fun unknownEncodingAndOversizedDeclarationsAreRejectedBeforeReading() {
        val source = fixture().copy(files = emptyMap())
        reject("ENCODING", source.field("metadataEncoding", JsonPrimitive("other-chunks-v1")))
        reject("SIZE", source.field("decodedBytes", JsonPrimitive(32 * 1048576 + 1)))
        reject("SIZE", source.field("xzBytes", JsonPrimitive(4 * 1048576 + 1)))
    }

    @Test fun chunkAndAggregateIdentitiesAreCheckedBeforeDecoding() {
        val source = fixture()
        val first = source.files.keys.first()
        reject("CHUNK_IDENTITY", source.copy(files = source.files + (first to source.files.getValue(first).copyOf().also { it[0] = 0 })))
        reject("XZ_IDENTITY", source.field("xzSha256", JsonPrimitive("0".repeat(64))))
        reject("XZ_SIZE", source.field("xzBytes", JsonPrimitive(source.files.values.sumOf { it.size } - 1)))
    }

    @Test fun decodedLengthAndDigestRemainBoundForBothFormats() {
        for (codec in listOf("xz", "gzip")) {
            val source = fixture(codec)
            reject("DECODED_SIZE", source.field("decodedBytes", JsonPrimitive(payload.toString().toByteArray().size - 1)))
            reject("DECODED_IDENTITY", source.field("decodedBytes", JsonPrimitive(payload.toString().toByteArray().size + 1)))
            reject("DECODED_IDENTITY", source.field("decodedSha256", JsonPrimitive("0".repeat(64))))
        }
    }

    @Test fun truncatedAndCorruptStreamsAreRejectedEvenWithCorrectCompressedHashes() {
        for (codec in listOf("xz", "gzip")) {
            val bytes = fixture(codec).files.values.single()
            reject(codec.uppercase(), fixture(codec, encoded = bytes.copyOf(bytes.size - 3)))
            val changed = bytes.copyOf().also { it[it.size - 6] = (it[it.size - 6].toInt() xor 1).toByte() }
            reject(codec.uppercase(), fixture(codec, encoded = changed))
        }
    }

    @Test fun xzDictionaryLimitIsEnforcedBeforeLargeAllocation() {
        val bytes = fixture().files.values.single().copyOf()
        val headerBytes = ((bytes[12].toInt() and 255) + 1) * 4
        check(bytes[13].toInt() == 0 && bytes[14].toInt() == 0x21 && bytes[15].toInt() == 1)
        bytes[16] = 28 // A valid LZMA2 property declaring a 64 MiB dictionary.
        val crc = CRC32().apply { update(bytes, 12, headerBytes - 4) }.value
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12 + headerBytes - 4, crc.toInt())
        assertTrue(reject("XZ", fixture(encoded = bytes)).cause is MemoryLimitException)
    }

    @Test fun decodedMetadataStillBindsToSelectedNativePayload() {
        val invalid = JsonObject(payload + ("bytes" to JsonPrimitive(elf.size + 1))).toString().toByteArray()
        val error = assertThrows(IllegalArgumentException::class.java) { decode(fixture(raw = invalid)) }
        assertEquals("ULTRA_KERNEL_RESIDENT_REFERENCE_IDENTITY", error.message)
    }

    @Test fun metadataPathsAndRepeatedChunksAreRejected() {
        val source = fixture()
        val descriptor = source.manifest.getValue("payloads").jsonObject.getValue(family).jsonObject
        val first = descriptor.getValue("chunks").jsonArray.single().jsonObject
        val invalid = JsonObject(first + ("path" to JsonPrimitive("$family/../metadata.part")))
        reject("PATH", source.field("chunks", JsonArray(listOf(invalid))))
        reject("PATH", source.field("xzBytes", JsonPrimitive(source.files.values.single().size * 2))
            .field("chunks", JsonArray(listOf(first, first))))
    }
}
