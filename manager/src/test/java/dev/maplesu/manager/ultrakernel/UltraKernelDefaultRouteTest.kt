package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.UltraInitBootProducer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real production dispatch and failure preservation; no partition or device operations. */
class UltraKernelDefaultRouteTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun defaultModernBootKeepsItsRawFailureWithoutSelectingAnAvailableComponent() {
        for (release in listOf("6.1.25-android14-11-fixture", "6.6.1-android15-1-fixture",
            "6.12.1-android16-1-fixture", "6.18.1-android17-1-fixture")) {
            for (hasRamdisk in listOf(false, true)) {
                val (source, artifact) = componentFixture(true)
                val bytes = source.readBytes()
                java.util.Arrays.fill(bytes, 4096 + 128, 4096 + 512, 0.toByte())
                "Linux version $release (fixture)\n".toByteArray().copyInto(bytes, 4096 + 128)
                if (!hasRamdisk) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 0)
                source.writeBytes(bytes)
                var components = 0
                val flow = UltraKernelFlow(temporary.newFolder(), UltraKernelMemoryAssets(emptyMap()),
                    moduleArtifact = { components++; artifact })
                for (prepare in listOf<() -> UltraKernelFlow.Preparation>({ flow.prepare(source) },
                    { flow.prepareTarget(source) })) {
                    val failure = runCatching(prepare).exceptionOrNull()
                    assertNotNull(failure)
                    assertTrue(failure.toString(), failure!!.message.orEmpty().startsWith("ULTRA_KERNEL_GENERATE_KALLSYMS"))
                    assertEquals(0, components)
                    assertArrayEquals(bytes, source.readBytes())
                }
            }
        }
    }

    @Test fun anUnboundTargetTemplateFailsBeforeAnyCandidateIsCreated() {
        val (source, _) = componentFixture(true)
        val constructor = UltraInitBootProducer.Artifact::class.java.declaredConstructors.single { it.parameterCount == 7 }
            .apply { isAccessible = true }
        val artifact = constructor.newInstance(null, "", "fixture", "", null, byteArrayOf(1), byteArrayOf(1)) as UltraInitBootProducer.Artifact
        val work = temporary.newFolder()
        val failure = runCatching { UltraKernelBuilder(work).generateInitBoot(source, artifact) }.exceptionOrNull()
        assertEquals("ULTRA_INIT_TARGET_BINDING_REQUIRED", failure?.message)
        assertTrue(work.listFiles().orEmpty().isEmpty())
    }
    @Suppress("UNCHECKED_CAST")
    private fun componentFixture(boot: Boolean): Pair<File, UltraInitBootProducer.Artifact> {
        val helpers = Class.forName("dev.maplesu.image.UltraInitBootProducerTest")
        val files = helpers.getDeclaredMethod("kmiBundle").apply { isAccessible = true }.invoke(null) as Map<String, ByteArray>
        val directory = temporary.newFolder()
        files.forEach { (path, bytes) -> File(directory, path).writeBytes(bytes) }
        val artifact = UltraInitBootProducer.readArtifact(File(directory, "manifest.json"))
        val kernel = helpers.getDeclaredMethod("kernel").apply { isAccessible = true }.invoke(null) as ByteArray
        val ramdisk = helpers.getDeclaredMethod("stock", java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            .apply { isAccessible = true }.invoke(null, false, false) as ByteArray
        val bytes = helpers.getDeclaredMethod("image", ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(null, kernel, ramdisk) as ByteArray
        return File(directory, "boot.img").also { it.writeBytes(bytes) } to artifact
    }
}
