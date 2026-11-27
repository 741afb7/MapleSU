package dev.maplesu.manager.ultrakernel

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.maplesu.image.AvbFooter
import org.junit.Assume.assumeTrue

class UltraKernelArchivedRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun archivedInverseRestoresExactKernelWithoutLocalReceiptsOrWrites() {
        val fixture = fixture()
        val before = UltraKernelSpec.sha256(fixture.boot)
        assertArrayEquals(fixture.original, UltraKernelOriginalRecovery(fixture.assets())
            .generatedKernel(fixture.boot, temporary.newFolder("receipts")))
        assertEquals(before, UltraKernelSpec.sha256(fixture.boot))
    }

    @Test fun kernelRecoveryKeepsItsSourceAndDoesNotClaimTheStockContainer() {
        val fixture = fixture()
        val recovery = UltraKernelOriginalRecovery(fixture.assets())
        val receipts = temporary.newFolder("receipts")
        assertNull(recovery.recover(fixture.boot, receipts, emptyList(), temporary.newFolder("stock")))
        val restored = recovery.recover(fixture.boot, receipts, emptyList(),
            temporary.newFolder("kernel"), generatedOnly = true)
        assertNotNull(restored)
        assertEquals("ARCHIVED_KERNEL_REVERSE", restored!!.source.name)
        assertArrayEquals(fixture.original, UltraKernelPlan.readKernel(restored.image))
    }

    @Test fun anyKernelChangePreventsTheArchiveFromMatching() {
        for (at in listOf(1024, 3000)) {
            val fixture = fixture()
            val bytes = fixture.boot.readBytes()
            bytes[4096 + at] = (bytes[4096 + at].toInt() xor 1).toByte()
            fixture.boot.writeBytes(bytes)
            assertNull(UltraKernelOriginalRecovery(fixture.assets())
                .generatedKernel(fixture.boot, temporary.newFolder()))
        }
    }

    @Test fun exactArchivedKernelProofSupersedesAStaleEmbeddedInverse() {
        val fixture = fixture()
        val raw = temporary.newFile("patched-kernel").apply { writeBytes(UltraKernelPlan.readKernel(fixture.boot)) }
        // This valid record describes another final write, as in the retained executor probe.
        val stale = fixture.inverse.clone()
        stale[64] = (stale[64].toInt() xor 1).toByte()
        java.security.MessageDigest.getInstance("SHA-256").digest(stale.copyOf(stale.size - 32))
            .copyInto(stale, stale.size - 32)
        val boot = File(temporary.root, "stale-inverse.img")
        dev.maplesu.image.BootImageRebuilder.rebuild(fixture.boot, raw, boot, stale)
        val recovery = UltraKernelOriginalRecovery(fixture.assets())
        val before = UltraKernelSpec.sha256(boot)
        assertTrue(recovery.archivedKernelUpdateRequired(boot))
        assertArrayEquals(fixture.original, recovery.generatedKernel(boot, temporary.newFolder()))
        val restored = recovery.recover(boot, temporary.newFolder(), emptyList(),
            temporary.newFolder(), generatedOnly = true)
        assertEquals("ARCHIVED_KERNEL_REVERSE", restored!!.source.name)
        assertArrayEquals(fixture.original, UltraKernelPlan.readKernel(restored.image))
        assertEquals(before, UltraKernelSpec.sha256(boot))
        assertThrows(IllegalArgumentException::class.java) {
            UltraKernelOriginalRecovery(UltraKernelMemoryAssets(emptyMap()))
                .generatedKernel(boot, temporary.newFolder())
        }
        val changed = boot.readBytes().apply { this[4096 + 3000] = (this[4096 + 3000].toInt() xor 1).toByte() }
        boot.writeBytes(changed)
        assertFalse(recovery.archivedKernelUpdateRequired(boot))
        assertThrows(IllegalArgumentException::class.java) {
            recovery.generatedKernel(boot, temporary.newFolder())
        }
    }

    @Test fun aMatchedArchiveWithInvalidMetadataFailsBeforeAnyRecoveryOutput() {
        for ((field, value) in listOf(
            "schemaVersion" to JsonPrimitive(2), "kernelBytes" to JsonPrimitive(4095),
            "kernelRelease" to JsonPrimitive("6.6.89-other"),
            "patchedKernelSha256" to JsonPrimitive("0".repeat(64)),
            "originalKernelSha256" to JsonPrimitive("0".repeat(64)),
            "recordSha256" to JsonPrimitive("0".repeat(64)),
        )) {
            val fixture = fixture()
            val metadata = JsonObject(fixture.metadata.toMutableMap().apply { put(field, value) })
            val output = File(temporary.root, "unused-$field")
            assertThrows(Exception::class.java) {
                UltraKernelOriginalRecovery(fixture.assets(metadata)).recover(fixture.boot,
                    temporary.newFolder(), emptyList(), output, generatedOnly = true)
            }
            assertFalse(output.exists())
        }
    }

    @Test fun aCorruptOrMissingInverseIsNotTreatedAsAnUnknownImage() {
        val fixture = fixture()
        for (data in listOf<ByteArray?>(null, fixture.inverse.copyOf().apply { this[lastIndex]++ })) {
            assertThrows(Exception::class.java) {
                UltraKernelOriginalRecovery(fixture.assets(inverse = data))
                    .generatedKernel(fixture.boot, temporary.newFolder())
            }
        }
    }

    @Test fun aStaleFooterWithProvenZeroTailDoesNotPreventArchivedKernelRecovery() {
        val fixture = fixture()
        staleFooter(fixture.boot, unknownTail = false)
        val before = UltraKernelSpec.sha256(fixture.boot)
        val recovered = UltraKernelOriginalRecovery(fixture.assets()).recover(fixture.boot,
            temporary.newFolder(), emptyList(), temporary.newFolder(), generatedOnly = true)
        assertNotNull(recovered)
        assertArrayEquals(fixture.original, UltraKernelPlan.readKernel(recovered!!.image))
        assertFalse(AvbFooter.parse(recovered.image).present)
        assertEquals(before, UltraKernelSpec.sha256(fixture.boot))
    }

    @Test fun anUnknownNonzeroTailStillPreventsArchivedRecovery() {
        val fixture = fixture()
        staleFooter(fixture.boot, unknownTail = true)
        val before = UltraKernelSpec.sha256(fixture.boot)
        val output = File(temporary.root, "no-output")
        assertThrows(Exception::class.java) {
            UltraKernelOriginalRecovery(fixture.assets()).recover(fixture.boot, temporary.newFolder(),
                emptyList(), output, generatedOnly = true)
        }
        assertTrue("no recovery image is published", output.walkTopDown().none { it.isFile })
        assertEquals(before, UltraKernelSpec.sha256(fixture.boot))
    }

    @Test fun aRamdiskOnlyContainerHasNoArchivedKernel() {
        val fixture = fixture()
        val bytes = ByteArray(4096)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("ANDROID!".toByteArray()); putInt(20, 1584); putInt(40, 4)
        }
        fixture.boot.writeBytes(bytes)
        assertNull(UltraKernelOriginalRecovery(fixture.assets()).generatedKernel(fixture.boot, temporary.newFolder()))
    }

    @Test fun allFourHistoricalImagesRestoreTheCompleteCorrespondingOriginalKernel() {
        val root = File(System.getProperty("maplesu.repo", ".."))
        assumeTrue("historical image corpus absent", historicalFiles(root).all { it.isFile })
        val assets = UltraKernelFileAssets(File(root, "manager/src/main/assets"))
        val receipts = temporary.newFolder("receipts")
        for ((directory, original, patched) in listOf(
            Triple("._boot", "k70uboot.img", "maplesu-ultra-k70u-patched.img"),
            Triple("Jeannette", "boot.img", "maplesu-ultra-patched-fixed.img"),
            Triple("Jeannette", "boot.img", "maplesu-ultra-patched.img"),
            Triple("\u7c0c", "boot.img", "XiaoMi_15_Ultra_Patch2.img"),
        )) {
            val folder = File(root, "Boot_Research/$directory")
            val image = File(folder, patched)
            val before = UltraKernelSpec.sha256(image)
            val restored = UltraKernelOriginalRecovery(assets).generatedKernel(image, receipts)
            assertNotNull(patched, restored)
            assertArrayEquals(patched, UltraKernelPlan.readKernel(File(folder, original)), restored)
            assertEquals(before, UltraKernelSpec.sha256(image))
        }
    }

    @Test fun historicalCredentialsRetainValidLocatorsAndBindLegacyRandomKeysExplicitly() {
        val root = File(System.getProperty("maplesu.repo", ".."))
        assumeTrue("historical image corpus absent", historicalFiles(root).all { it.isFile })
        val assets = UltraKernelFileAssets(File(root, "manager/src/main/assets"))
        val receipts = temporary.newFolder()
        for (path in listOf("._boot/maplesu-ultra-k70u-patched.img",
            "Jeannette/maplesu-ultra-patched-fixed.img", "Jeannette/maplesu-ultra-patched.img",
            "\u7c0c/XiaoMi_15_Ultra_Patch2.img")) {
            val image = File(root, "Boot_Research/$path")
            val patched = UltraKernelPlan.readKernel(image)
            val symbols = UltraKernelKallsymsTable.decode(patched)
            val at = requireNotNull(symbols.offsetOf("panic"))
            val bytes = patched.copyOfRange(at, at + UltraKernelCredential.LENGTH)
            try {
                val credential = UltraKernelCredential.of(bytes)
                val original = requireNotNull(UltraKernelOriginalRecovery(assets).generatedKernel(image, receipts))
                // Legacy identity binding is an explicit retained recipe boundary;
                // current SpecGenerator never accepts a caller credential.
                val preserved = credential.metadataDisplacement.takeIf { credential.sdkMetadataValid }
                val rendered = UltraKernelRecipe.k22().renderDetailed(
                    UltraKernelExecveEntry.resolve(UltraKernelKallsymsTable.decode(original), original),
                    original, credential, preservedMetadataDisplacement = preserved)
                val metadata = rendered.records.single { it.role == "free_initmem+0x0" }.site.toLong() + 4
                val executor = rendered.records.single { it.role == "__drm_printfn_coredump+0x0" }.site.toLong()
                val displacement = preserved ?: Math.toIntExact(metadata - executor - UltraKernelBodies.EXECUTOR_CALL_OFFSET)
                val legacy = UltraKernelPlan.fromRecords(UltraKernelSpec.releaseOf(original),
                    rendered.records.map { UltraKernelPlan.PatchRecord(it.role, it.site.toLong(), it.original, it.replacement) },
                    credential, sdkMetadataDisplacement = displacement, layout = rendered.layout)
                val plan = legacy.rebind(credential)
                val keyless = UltraKernelSpecGenerator().generate(original)
                assertNull(keyless.credential)
                assertTrue(keyless.records.none { it.role == "panic+0x0" })
                val bound = requireNotNull(plan.credential)
                assertTrue("published identity must address the generated metadata", bound.sdkMetadataValid)
                if (credential.sdkMetadataValid) {
                    assertTrue("keep an existing valid root identity", credential == bound)
                    assertEquals(credential.initPath, bound.initPath)
                    assertEquals(credential.metadataDisplacement, bound.metadataDisplacement)
                } else assertFalse("a random legacy key needs explicit SDK binding", credential == bound)
                val patchedResult = plan.apply(original)
                assertEquals(0, patchedResult.derived.outsideSites)
                val slot = plan.records.single { it.role == "panic+0x0" }
                assertArrayEquals(bound.copySecret(), slot.replacement)
                println("LEGACY_IDENTITY image=$path locatorPreviouslyValid=${credential.sdkMetadataValid}" +
                    " identityRetained=${credential == bound} publishedLocatorValid=${bound.sdkMetadataValid}")
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun historicalFiles(root: File): List<File> = listOf(
        "._boot/k70uboot.img", "._boot/maplesu-ultra-k70u-patched.img", "Jeannette/boot.img",
        "Jeannette/maplesu-ultra-patched-fixed.img", "Jeannette/maplesu-ultra-patched.img",
        "\u7c0c/boot.img", "\u7c0c/XiaoMi_15_Ultra_Patch2.img",
    ).map { File(root, "Boot_Research/$it") }

    private fun fixture(): Fixture {
        val raw = ByteArray(4096)
        "ARMd".toByteArray().copyInto(raw, 56)
        "Linux version 6.6.89-archived-fixture\u0000".toByteArray().copyInto(raw, 512)
        val credential = UltraKernelCredential.of("Aa1".repeat(16))
        val plan = UltraKernelPlan.fromRecords("6.6.89-archived-fixture", listOf(
            UltraKernelPlan.PatchRecord("credential", 1024, ByteArray(48), credential.copySecret()),
            UltraKernelPlan.PatchRecord("path", 2048, ByteArray(UltraKernelCredential.INIT_PATH_FIELD),
                credential.initPathField(UltraKernelCredential.INIT_PATH_FIELD))), credential)
        val patched = plan.apply(raw).kernel
        val inverse = UltraKernelRecoveryRecord.fromPlan(plan, raw.size, 0, 0).encode()
        val digest = UltraKernelSpec.sha256(patched)
        val metadata = buildJsonObject {
            put("schemaVersion", 1); put("kernelBytes", raw.size)
            put("kernelRelease", plan.kernelRelease); put("patchedKernelSha256", digest)
            put("originalKernelSha256", UltraKernelSpec.sha256(raw))
            put("recordSha256", UltraKernelSpec.sha256(inverse))
        }
        val bytes = ByteArray(8192)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("ANDROID!".toByteArray()); putInt(8, raw.size); putInt(20, 1584); putInt(40, 4)
        }
        patched.copyInto(bytes, 4096)
        val boot = temporary.newFile("boot-${temporary.root.listFiles()!!.size}.img").apply { writeBytes(bytes) }
        return Fixture(raw, boot, inverse, metadata, digest)
    }

    private fun staleFooter(image: File, unknownTail: Boolean) {
        val bytes = image.readBytes().copyOf(16384)
        if (unknownTail) bytes[8208] = 1
        val at = bytes.size - 64
        "AVBf".toByteArray().copyInto(bytes, at)
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(at + 4, 1); putLong(at + 12, 4096)
            putLong(at + 20, 8192); putLong(at + 28, 1664)
        }
        image.writeBytes(bytes)
    }

    private class Fixture(val original: ByteArray, val boot: File, val inverse: ByteArray,
                          val metadata: JsonObject, val digest: String) {
        fun assets(metadata: JsonObject = this.metadata, inverse: ByteArray? = this.inverse): UltraKernelAssetSource {
            val prefix = "ultra-kernels/recovery/kernel-inverses/$digest"
            return UltraKernelMemoryAssets(buildMap {
                put("$prefix.json", metadata.toString().toByteArray())
                if (inverse != null) put("$prefix.bin", inverse)
            })
        }
    }
}
