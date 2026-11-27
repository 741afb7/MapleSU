package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.RamdiskInspector
import dev.maplesu.image.UltraInitBootProducer
import java.io.File
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual namespace-binding seam and Producer; target resolver/Android PRE are separate tests. */
class UltraInitBootRepatchNamespaceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val flow by lazy { UltraKernelFlow(temporary.newFolder(), UltraKernelMemoryAssets(emptyMap())) }

    @Test fun matchingNamespaceRepatchRetainsOriginalContainerWithoutCredentials() {
        val fixture = fixture()
        val bound = bind(fixture.installed, fixture.artifact, fixture.namespace)
        val next = File(temporary.newFolder(), "next.img")
        UltraInitBootProducer.produce(fixture.installed, next, bound)
        assertArrayEquals(fixture.namespace.record(), UltraInitBootProducer.readDeploymentNamespace(next))
        assertTrue(RamdiskInspector.inspect(next).entries.none { it.path == ".maplesu_ultra/credential" })
        val restored = File(temporary.newFolder(), "original.img")
        UltraInitBootProducer.restore(next, restored)
        assertArrayEquals(fixture.original.readBytes(), restored.readBytes())
    }

    @Test fun differentRecoveredNamespaceStopsBeforeSelectionMutation() {
        val fixture = fixture()
        val failure = runCatching { bind(fixture.installed, fixture.artifact,
            UltraKernelDeploymentNamespace.create()) }.exceptionOrNull()
        assertEquals("ULTRA_INIT_BOOT_DEPLOYMENT_NAMESPACE_CONFLICT", failure?.message)
        assertNull(UltraKernelFlow::class.java.getDeclaredField("selectedDeploymentNamespace")
            .apply { isAccessible = true }.get(flow))
        assertArrayEquals(fixture.namespace.record(), UltraInitBootProducer.readDeploymentNamespace(fixture.installed))
    }

    private fun bind(source: File, artifact: UltraInitBootProducer.Artifact,
                     recovered: UltraKernelDeploymentNamespace): UltraInitBootProducer.Artifact = try {
        UltraKernelFlow::class.java.getDeclaredMethod("withInitBootDeployment", File::class.java,
            UltraInitBootProducer.Artifact::class.java, UltraKernelDeploymentNamespace::class.java)
            .apply { isAccessible = true }.invoke(flow, source, artifact, recovered) as UltraInitBootProducer.Artifact
    } catch (wrapped: InvocationTargetException) { throw wrapped.targetException }

    private data class Fixture(val original: File, val installed: File, val artifact: UltraInitBootProducer.Artifact,
                               val namespace: UltraKernelDeploymentNamespace)

    private fun fixture(): Fixture {
        val directory = temporary.newFolder()
        val helpers = Class.forName("dev.maplesu.image.UltraInitBootProducerTest")
        fun bytes(name: String, vararg args: Any): ByteArray {
            val types = args.map { when (it) {
                is Boolean -> java.lang.Boolean.TYPE
                is Int -> java.lang.Integer.TYPE
                else -> it.javaClass
            } }.toTypedArray()
            return helpers.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(null, *args) as ByteArray
        }
        @Suppress("UNCHECKED_CAST")
        val files = helpers.getDeclaredMethod("kmiBundle").apply { isAccessible = true }
            .invoke(null) as Map<String, ByteArray>
        files.forEach { (name, content) -> File(directory, name).writeBytes(content) }
        val artifact = UltraInitBootProducer.readArtifact(File(directory, "manifest.json"))
        val namespace = UltraKernelDeploymentNamespace.create()
        val original = File(directory, "original.img").also {
            it.writeBytes(bytes("image", ByteArray(0), bytes("stock", false, false)))
        }
        val installed = File(directory, "installed.img")
        UltraInitBootProducer.produce(original, installed, artifact.withDeploymentNamespace(namespace.record()))
        return Fixture(original, installed, artifact, namespace)
    }
}
