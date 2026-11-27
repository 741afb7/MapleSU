package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.RamdiskInspector
import dev.maplesu.image.UltraInitBootProducer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real file producer and shared retain policy; synthetic ELF bytes are never executed. */
class UltraInitBootCredentialTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = UltraKernelCredential.of("Aa1".repeat(16)).withMetadataDisplacement(-8192)
    @Test fun publicDeploymentReachesPreparationWithoutPublishingACredential() {
        val fixture = keylessFixture()
        val sourceBefore = fixture.source.readBytes()
        val manifestBefore = fixture.manifest.readBytes()
        val bridgeBefore = fixture.artifact.bridgeBytes()
        val flow = UltraKernelFlow(temporary.newFolder(), UltraKernelMemoryAssets(emptyMap()))
        val candidate = requireNotNull(flow.prepareTarget(fixture.source, "generated", fixture.artifact).candidate)
        val entries = RamdiskInspector.inspect(candidate.image).entries
        assertTrue(entries.none { it.path == ".maplesu_ultra/credential" })
        val record = requireNotNull(UltraInitBootProducer.readDeploymentNamespace(candidate.image))
        val namespace = UltraKernelDeploymentNamespace.decode(record)
        assertEquals(UltraKernelDeploymentNamespace.RECORD_BYTES, record.size)
        assertEquals(fixture.artifact.bridgeSha256, entries.single { it.path == ".maplesu_ultra/bridge.ko" }.sha256)
        assertNull(fixture.artifact.credentialSha256)
        val repeated = requireNotNull(flow.prepareTarget(candidate.image, "generated", fixture.artifact).candidate)
        assertArrayEquals(namespace.record(), UltraInitBootProducer.readDeploymentNamespace(repeated.image))
        assertArrayEquals(candidate.image.readBytes(), repeated.image.readBytes())
        record.fill(0)
        assertArrayEquals(namespace.record(), UltraInitBootProducer.readDeploymentNamespace(candidate.image))
        assertArrayEquals(sourceBefore, fixture.source.readBytes())
        assertArrayEquals(manifestBefore, fixture.manifest.readBytes())
        assertArrayEquals(bridgeBefore, fixture.artifact.bridgeBytes())
    }

    @Test fun upgradeComparesActualPayloadAndRequiresKeylessMode() {
        val fixture = keylessFixture()
        val candidate = requireNotNull(UltraKernelFlow(temporary.newFolder(), UltraKernelMemoryAssets(emptyMap()))
            .prepareTarget(fixture.source, "generated", fixture.artifact).candidate)
        assertFalse(UltraKernelFlow.initBootUpdateRequired(candidate.image, fixture.artifact))
        assertTrue(UltraKernelFlow.initBootUpdateRequired(candidate.image, keylessFixture(variant = 1).artifact))
        assertTrue(UltraKernelFlow.initBootUpdateRequired(candidate.image, fixture(embedded = true).artifact))
        assertTrue(UltraKernelFlow.initBootUpdateRequired(candidate.image, fixture().artifact))
    }

    @Test fun legacyEmbeddedIsDecodedButNeverReinstalledByCurrentProduction() {
        val fixture = fixture(embedded = true)
        assertEquals("embedded", fixture.artifact.credentialMode)
        val bytes = original.copySecret()
        try { assertEquals(UltraKernelSpec.sha256(bytes), fixture.artifact.credentialSha256) }
        finally { bytes.fill(0) }
        val sourceBefore = fixture.source.readBytes()
        val work = temporary.newFolder()
        val error = runCatching { UltraKernelBuilder(work).generateInitBoot(fixture.source, fixture.artifact) }.exceptionOrNull()
        assertEquals("ULTRA_KERNEL_KEYLESS_COMPONENT_REQUIRED", error?.message)
        assertTrue(work.listFiles().orEmpty().isEmpty())
        assertArrayEquals(sourceBefore, fixture.source.readBytes())
    }

    @Test fun directBuilderRequiresThePublicDeploymentRecordBeforeProducingAnything() {
        val fixture = keylessFixture()
        val workspace = temporary.newFolder()
        val before = fixture.source.readBytes()
        val error = runCatching { UltraKernelBuilder(workspace).generateInitBoot(fixture.source, fixture.artifact) }.exceptionOrNull()
        assertEquals("ULTRA_INIT_BOOT_DEPLOYMENT_NAMESPACE_REQUIRED", error?.message)
        assertTrue(workspace.listFiles().orEmpty().isEmpty())
        assertArrayEquals(before, fixture.source.readBytes())
    }

    @Test fun realPinnedLegacyCredentialMismatchStillFailsAtItsDecoderBoundary() {
        val fixture = fixture(pinnedHash = "0".repeat(64))
        val before = fixture.source.readBytes()
        val bytes = original.copySecret()
        val error = try { runCatching { fixture.artifact.withCredential(bytes) }.exceptionOrNull() }
        finally { bytes.fill(0) }
        assertEquals("ULTRA_INIT_BOOT_CREDENTIAL_HASH", error?.message)
        val output = File(temporary.newFolder(), "candidate.img")
        val productionError = runCatching { UltraInitBootProducer.produce(fixture.source, output, fixture.artifact) }.exceptionOrNull()
        assertEquals("ULTRA_INIT_BOOT_KEYLESS_COMPONENT_REQUIRED", productionError?.message)
        assertFalse(output.exists())
        assertArrayEquals(before, fixture.source.readBytes())
    }

    private data class Fixture(val source: File, val artifact: UltraInitBootProducer.Artifact, val manifest: File)

    @Suppress("UNCHECKED_CAST")
    private fun keylessFixture(variant: Int = 0): Fixture {
        val helpers = Class.forName("dev.maplesu.image.UltraInitBootProducerTest")
        val files = (helpers.getDeclaredMethod("kmiBundle").apply { isAccessible = true }
            .invoke(null) as Map<String, ByteArray>).toMutableMap()
        if (variant != 0) {
            val old = requireNotNull(files["bridge.ko"])
            val changed = old + variant.toByte()
            files["bridge.ko"] = changed
            files["manifest.json"] = requireNotNull(files["manifest.json"]).toString(Charsets.UTF_8)
                .replace(UltraKernelSpec.sha256(old), UltraKernelSpec.sha256(changed)).toByteArray()
        }
        val directory = temporary.newFolder()
        files.forEach { (path, bytes) -> File(directory, path).writeBytes(bytes) }
        val ramdisk = helpers.getDeclaredMethod("stock", java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            .apply { isAccessible = true }.invoke(null, false, false) as ByteArray
        val image = helpers.getDeclaredMethod("image", ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(null, ByteArray(0), ramdisk) as ByteArray
        val source = File(directory, "init_boot.img").also { it.writeBytes(image) }
        val manifest = File(directory, "manifest.json")
        return Fixture(source, UltraInitBootProducer.readArtifact(manifest), manifest)
    }

    private fun fixture(embedded: Boolean = false, displacement: Int = -4096,
                        variant: Int = 0, pinnedHash: String? = null): Fixture {
        val directory = temporary.newFolder()
        val helpers = Class.forName("dev.maplesu.image.UltraInitBootProducerTest")
        fun bytes(name: String, vararg args: Any): ByteArray {
            val types = args.map { when (it) {
                is Boolean -> java.lang.Boolean.TYPE
                is Int -> java.lang.Integer.TYPE
                else -> it.javaClass
            } }.toTypedArray()
            val method = helpers.getDeclaredMethod(name, *types).apply { isAccessible = true }
            return method.invoke(null, *args) as ByteArray
        }
        val kernel = if (embedded) bytes("kernel") else {
            val layout = UltraKernelKallsymsLayoutTest::class.java.getDeclaredMethod("fixture",
                java.lang.Boolean.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE,
                java.lang.Integer.TYPE, Map::class.java).apply { isAccessible = true }
            (layout.invoke(UltraKernelKallsymsLayoutTest(), false, 1025, 0x5000, 0,
                emptyMap<Int, Pair<String, Int>>()) as ByteArray).apply {
                ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(24, 2)
                "Linux version 6.6.1-fixture (byte fixture)\n".toByteArray(Charsets.US_ASCII).copyInto(this, 0x9000)
            }
        }
        val module = if (embedded) bytes("module").also {
            val elf = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
            val symbols = elf.getLong(40).toInt() + 3 * elf.getShort(58).toInt()
            elf.putInt(symbols + 40, 1).putLong(symbols + 56, 24)
        } else bytes("parameterModule", 4096 - displacement).also {
            val elf = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
            val section = elf.getLong(40).toInt() + 5 * elf.getShort(58).toInt()
            val at = elf.getLong(section + 24).toInt()
            val info = "vermagic=6.6.1-fixture SMP\u0000parmtype=deployment_namespace:string\u0000"
                .toByteArray(Charsets.US_ASCII)
            info.copyInto(it, at)
            elf.putLong(section + 32, info.size.toLong())
        }
        val files = mapOf("target-boot.img" to bytes("image", kernel, ByteArray(0)),
            "ultra-init" to bytes("handoff"), "bridge.ko" to (module + variant.toByte()))
        files.forEach { (path, content) -> File(directory, path).writeBytes(content) }
        val manifest = File(directory, "manifest.json")
        manifest.writeText(buildJsonObject {
            put("schemaVersion", 1)
            put("kernelRelease", "6.6.1-fixture")
            put("kernelSha256", UltraKernelSpec.sha256(kernel))
            if (embedded) {
                val secret = original.copySecret()
                try { put("credentialSha256", UltraKernelSpec.sha256(secret)) } finally { secret.fill(0) }
            } else {
                put("credentialMode", "module-parameter")
                put("metadataDisplacement", displacement)
                pinnedHash?.let { put("credentialSha256", it) }
            }
            for ((field, path) in mapOf("targetBoot" to "target-boot.img", "handoff" to "ultra-init", "bridge" to "bridge.ko")) {
                put(field, path)
                put(field + "Sha256", UltraKernelSpec.sha256(files.getValue(path)))
            }
        }.toString())
        val source = File(directory, "init_boot.img").also {
            it.writeBytes(bytes("image", ByteArray(0), bytes("stock", false, false)))
        }
        return Fixture(source, UltraInitBootProducer.readArtifact(manifest), manifest)
    }
}
