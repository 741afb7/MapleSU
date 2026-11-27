package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.BootImageInspector
import dev.maplesu.image.RamdiskInspector
import dev.maplesu.image.UltraInitBootProducer
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real producer and typed namespace bytes only; no module, gateway or installation authority. */
class UltraInitBootDeploymentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oneFlowReusesItsPublicNamespaceAcrossPreparations() {
        val input = fixture()
        val flow = flow()
        val first = build(flow, input.source, input.artifact)
        val original = namespace(first)
        val second = flow.prepareTarget(input.source, null, input.artifact)
        assertArrayEquals(original, namespace(requireNotNull(second.candidate).image))
        assertArrayEquals(original, namespace(first))
        val returnedCopy = namespace(first).apply { this[8] = 'Z'.code.toByte() }
        assertArrayEquals(original, namespace(first))
        assertEquals(24, returnedCopy.size)
        val entry = RamdiskInspector.inspect(first).entries.single { it.path == ".maplesu_ultra/deployment" }
        assertEquals(24L, entry.size)
        assertEquals(0x180, entry.permissions())
        assertEquals(UltraKernelSpec.sha256(original), entry.sha256)
    }

    @Test fun installedSourceWinsOverEarlierFreshPreviewAndSurvivesExactRestore() {
        val input = fixture()
        val installed = build(flow(), input.source, input.artifact)
        val original = namespace(installed)
        val retryFlow = flow()
        build(retryFlow, input.source, input.artifact)
        val upgrade = build(retryFlow, installed, input.artifact)
        assertArrayEquals(original, namespace(upgrade))
        assertArrayEquals(original, namespace(build(retryFlow, input.source, input.artifact)))
        assertFalse(UltraKernelFlow.initBootUpdateRequired(upgrade, input.artifact))
        val restored = File(temporary.root, "exact-restored.img")
        UltraInitBootProducer.restore(upgrade, restored)
        assertArrayEquals(input.source.readBytes(), restored.readBytes())
    }

    @Test fun typedBootLineageHandoffWinsWhenInitBootHasNoRecord() {
        val input = fixture()
        val flow = flow()
        val record = UltraKernelDeploymentNamespace.create().record()
        val typed = UltraKernelDeploymentNamespace.decode(record)
        // The real HOST recovery consumers call this exact private boundary with their typed result.
        // This fixture covers that handoff, not boot inverse recovery or a separate Flow transfer.
        UltraKernelFlow::class.java.getDeclaredMethod("specGenerator", UltraKernelDeploymentNamespace::class.java)
            .apply { isAccessible = true }.invoke(flow, typed)
        assertArrayEquals(record, namespace(build(flow, input.source, input.artifact)))
    }

    @Test fun keylessArtifactsRejectCredentialBinding() {
        val input = fixture()
        assertEquals("keyless", input.artifact.credentialMode)
        assertNull(input.artifact.credentialSha256)
        assertThrows(java.io.IOException::class.java) {
            input.artifact.withCredential("A".repeat(48).toByteArray())
        }
        assertTrue(RamdiskInspector.inspect(build(flow(), input.source, input.artifact)).entries
            .none { it.path == ".maplesu_ultra/credential" })
    }

    @Test fun failedBindingDoesNotRotateTheFlowsAlreadySelectedNamespace() {
        val input = fixture()
        val flow = flow()
        val first = build(flow, input.source, input.artifact)
        val original = namespace(first)
        val failure = runCatching { flow.prepareTarget(first, null, input.artifact,
            UltraKernelDeploymentNamespace.create()) }.exceptionOrNull()
        assertEquals("ULTRA_INIT_BOOT_DEPLOYMENT_NAMESPACE_CONFLICT", failure?.message)
        assertArrayEquals(original, namespace(build(flow, input.source, input.artifact)))
    }

    @Test fun identicalRepatchReportsAlreadyInstalledAfterFullProducerReadback() {
        val input = fixture()
        val flow = flow()
        val first = flow.prepareTarget(input.source, null, input.artifact)
        assertFalse(first.alreadyInstalled)
        val installed = requireNotNull(first.candidate)
        val before = installed.image.readBytes()
        val repeated = flow.prepareTarget(installed.image, null, input.artifact)
        assertTrue(repeated.alreadyInstalled)
        assertEquals(installed.bootSha256, requireNotNull(repeated.candidate).bootSha256)
        assertArrayEquals(before, installed.image.readBytes())
    }

    @Test fun corruptOwnedInputFailsBeforeAnyNamespaceReplacementOrCandidate() {
        val input = fixture()
        val installed = build(flow(), input.source, input.artifact)
        val bad = File(temporary.root, "damaged.img")
        val layout = BootImageInspector.inspect(installed)
        bad.writeBytes(installed.readBytes().apply {
            val at = layout.ramdiskOffset.toInt() + 128
            this[at] = (this[at].toInt() xor 1).toByte()
        })
        val before = bad.readBytes()
        val workspace = temporary.newFolder()
        val failure = runCatching { build(UltraKernelFlow(workspace, UltraKernelMemoryAssets(emptyMap())), bad, input.artifact) }
            .exceptionOrNull()
        assertEquals("ULTRA_INIT_BOOT_RECOVERY_CHECKSUM", failure?.message)
        assertTrue(workspace.listFiles().orEmpty().isEmpty())
        assertArrayEquals(before, bad.readBytes())
    }

    private fun flow() = UltraKernelFlow(temporary.newFolder(), UltraKernelMemoryAssets(emptyMap()))

    private fun build(flow: UltraKernelFlow, source: File, artifact: UltraInitBootProducer.Artifact): File =
        requireNotNull(flow.prepareTarget(source, null, artifact).candidate).image

    private fun namespace(image: File): ByteArray =
        requireNotNull(UltraInitBootProducer.readDeploymentNamespace(image))

    private data class Fixture(val source: File, val artifact: UltraInitBootProducer.Artifact)

    private fun fixture(): Fixture {
        val directory = temporary.newFolder()
        val helpers = Class.forName("dev.maplesu.image.UltraInitBootProducerTest")
        fun bytes(name: String, vararg args: Any): ByteArray {
            val types = args.map { when (it) {
                is Boolean -> java.lang.Boolean.TYPE
                is Int -> java.lang.Integer.TYPE
                else -> it.javaClass
            } }.toTypedArray()
            return helpers.getDeclaredMethod(name, *types).apply { isAccessible = true }
                .invoke(null, *args) as ByteArray
        }
        @Suppress("UNCHECKED_CAST")
        val files = helpers.getDeclaredMethod("kmiBundle").apply { isAccessible = true }
            .invoke(null) as Map<String, ByteArray>
        files.forEach { (name, content) -> File(directory, name).writeBytes(content) }
        val manifest = File(directory, "manifest.json")
        val source = File(directory, "init_boot.img").also {
            it.writeBytes(bytes("image", ByteArray(0), bytes("stock", false, false)))
        }
        return Fixture(source, UltraInitBootProducer.readArtifact(manifest))
    }
}
