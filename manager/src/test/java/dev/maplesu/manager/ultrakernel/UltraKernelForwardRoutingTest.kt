package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.UltraInitBootProducer
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic kernels test dispatch and failure fidelity; they do not prove a full claim generation. */
class UltraKernelForwardRoutingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun modernKernelBootsNeverOpenTheDefaultComponentOrExactCatalog() {
        for (release in listOf("6.1.25-android14-11-fixture", "6.6.1-android15-1-fixture",
            "6.12.1-android16-1-fixture", "6.18.1-android17-1-fixture", "6.6.127-custom")) {
            val failures = mutableListOf<String>()
            for (ramdisk in listOf(false, true)) {
                val (source, _) = fixture(release, ramdisk)
                val before = source.readBytes()
                var componentCalls = 0
                val flow = UltraKernelFlow(temporary.newFolder(), UltraKernelMemoryAssets(emptyMap()),
                    moduleArtifact = { componentCalls++; error("EXACT_CATALOG_MUST_NOT_OPEN") })
                val failure = runCatching { flow.prepareTarget(source) }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(failure!!.message.orEmpty(), failure.message.orEmpty().startsWith("ULTRA_KERNEL_GENERATE_KALLSYMS"))
                failures += failure.message.orEmpty()
                assertEquals(0, componentCalls)
                assertArrayEquals(before, source.readBytes())
            }
            assertEquals(failures[0], failures[1])
        }
    }

    @Test fun anAvailableKmiComponentDoesNotTurnTargetGenerationFailureIntoSuccess() {
        val (source, artifact) = fixture("6.6.1-android15-1-fixture", true)
        val before = source.readBytes()
        var componentCalls = 0
        val work = temporary.newFolder()
        val flow = UltraKernelFlow(work, UltraKernelMemoryAssets(emptyMap()),
            moduleArtifact = { componentCalls++; artifact })
        val failure = runCatching { flow.prepare(source) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().startsWith("ULTRA_KERNEL_GENERATE_KALLSYMS"))
        assertEquals(0, componentCalls)
        assertArrayEquals(before, source.readBytes())
        assertTrue(work.listFiles().orEmpty().none { it.extension == "img" })
    }

    @Test fun pureInitBootKeepsItsActualKmiComponentAndExactRestore() {
        val (_, artifact) = fixture("6.6.1-android15-1-fixture", true)
        val source = image(ByteArray(0), true)
        val before = source.readBytes()
        val prepared = UltraKernelFlow(temporary.newFolder(), UltraKernelMemoryAssets(emptyMap()),
            moduleArtifact = { assertEquals(source, it); artifact }).prepare(source)
        val candidate = requireNotNull(prepared.candidate)
        assertEquals("INIT_BOOT_KMI_COMPONENT", prepared.planOrigin)
        assertEquals("init_boot", candidate.partition)
        assertEquals("android15-6.6", candidate.componentKmi)
        val restored = File(temporary.newFolder(), "restored.img")
        UltraInitBootProducer.restore(candidate.image, restored)
        assertArrayEquals(before, restored.readBytes())
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun explicitBootComponentRetainsTheExistingCompatibilityApi() {
        val (source, artifact) = fixture("6.6.1-android15-1-fixture", true)
        val prepared = UltraKernelFlow(temporary.newFolder(), UltraKernelMemoryAssets(emptyMap()),
            moduleArtifact = { error("EXPLICIT_COMPONENT_MUST_NOT_SELECT_DEFAULT") })
            .prepareTarget(source, "generated", artifact)
        assertEquals("KMI_BOOT_MODULE", prepared.planOrigin)
        assertEquals("boot", requireNotNull(prepared.candidate).partition)
    }

    private val helpers get() = Class.forName("dev.maplesu.image.UltraInitBootProducerTest")
    private fun bytes(name: String, vararg arguments: Any): ByteArray = helpers.getDeclaredMethod(name,
        *arguments.map { if (it is Boolean) java.lang.Boolean.TYPE else it.javaClass }.toTypedArray())
        .apply { isAccessible = true }.invoke(null, *arguments) as ByteArray

    @Suppress("UNCHECKED_CAST")
    private fun fixture(release: String, ramdisk: Boolean): Pair<File, UltraInitBootProducer.Artifact> {
        val directory = temporary.newFolder()
        val files = helpers.getDeclaredMethod("kmiBundle").apply { isAccessible = true }
            .invoke(null) as Map<String, ByteArray>
        files.forEach { (name, content) -> File(directory, name).writeBytes(content) }
        val artifact = UltraInitBootProducer.readArtifact(File(directory, "manifest.json"))
        val kernel = bytes("kernel").also {
            java.util.Arrays.fill(it, 128, 512, 0.toByte())
            "Linux version $release (fixture)\n".toByteArray().copyInto(it, 128)
        }
        return image(kernel, ramdisk) to artifact
    }

    private fun image(kernel: ByteArray, ramdisk: Boolean): File =
        File(temporary.newFolder(), if (kernel.isEmpty()) "init_boot.img" else "boot.img").also {
            it.writeBytes(bytes("image", kernel, if (ramdisk) bytes("stock", false, false) else ByteArray(0)))
        }
}
