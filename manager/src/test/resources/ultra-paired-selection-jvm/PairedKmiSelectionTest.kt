package dev.maplesu.manager.ultrakernel

import android.content.Context
import android.content.res.AssetManager
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairedKmiSelectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pairedContextUsesTheActualKernelKmiComponent() {
        val fixture = Class.forName("dev.maplesu.image.UltraInitBootProducerTest")
        @Suppress("UNCHECKED_CAST")
        val bundle = fixture.getDeclaredMethod("kmiBundle").apply { isAccessible = true }
            .invoke(null) as Map<String, ByteArray>
        val kernel = (fixture.getDeclaredMethod("kernel").apply { isAccessible = true }
            .invoke(null) as ByteArray).apply {
            java.util.Arrays.fill(this, 128, 512, 0.toByte())
            "Linux version 6.6.66-android15-8-fixture (fixture)\n".toByteArray().copyInto(this, 128)
        }
        val boot = fixture.getDeclaredMethod("image", ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(null, kernel, ByteArray(0)) as ByteArray
        val source = temporary.newFile("boot.img").apply { writeBytes(boot) }
        val manifest = bundle.getValue("manifest.json")
        val fields = Json.parseToJsonElement(manifest.decodeToString()).jsonObject
        val digest = UltraKernelSpec.sha256(manifest)
        val prefix = "components/$digest/"
        val files = bundle.mapKeys { "ultra-init-bridge/$prefix${it.key}" }.toMutableMap()
        files["ultra-init-bridge/catalog-kmi.json"] = buildJsonObject {
            put("schema", "maplesu-init-bridge-catalog/2")
            put("sourceGenerationSha256", "a".repeat(64))
            put("components", buildJsonArray { add(buildJsonObject {
                for (name in listOf("kmi", "architecture", "pageSize", "cfiMode")) put(name, fields.getValue(name))
                put("manifestSha256", digest); put("prefix", prefix)
            }) })
        }.toString().toByteArray()
        val assets = AssetManager(files)
        val context = Context(temporary.newFolder(), assets)
        val selected = requireNotNull(UltraKernelFlow.initBootArtifact(context, source)) {
            "PAIRED_CONTEXT_COMPONENT_SELECTION_MISSING"
        }
        assertTrue(selected.isKmiComponent)
        assertEquals("android15-6.6", selected.componentKmi)
        assertFalse(assets.reads.contains("ultra-init-bridge/catalog.json"))
        assertArrayEquals(boot, source.readBytes())
    }
}
