package dev.maplesu.manager.ultrakernel

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit legacy rendering preserves old identities; production upgrades generate without a Key. */
class UltraKernelLegacyMetadataFlowTest {
    @Test
    fun historicalK22IdentityUsesACompatibilityCarrierInsteadOfOverwritingLiveCode() {
        val boot = File("../MapleSU_Reference/Boots/OnePlusAce6_6.6/boot.img")
        assumeTrue("reference boot sample not available", boot.isFile)
        val credential = UltraKernelPlanFixtures.credential()
        assertEquals(-9139764, credential.metadataDisplacement)
        val archived = UltraKernelSpec.read(File("src/main/assets/ultra-kernels/recovery/k22-ultra-6.6.89.spec"))
        val key = credential.copySecret()
        try {
            assertTrue("the complete identity was published in the recovery archive",
                archived.patches.any { it.replacement.contentEquals(key) })
        } finally { key.fill(0) }
        val before = UltraKernelSpec.sha256(boot)
        val kernel = UltraKernelPlan.readKernel(boot)
        val plan = legacyPlan(kernel, credential)
        assertEquals(credential, plan.credential)
        assertEquals(credential.key16, plan.credential?.key16)
        val executor = plan.records.single { it.role == "__drm_printfn_coredump+0x0" }
        val helper = plan.records.single { it.role == "metadata-rebase" }
        assertEquals(executor.offset + 188, helper.offset)
        assertEquals(188, executor.replacement.size)
        assertEquals(UltraKernelA64.encodeBl(executor.offset + 4, helper.offset),
            UltraKernelA64.le32Bits(executor.replacement, 4))
        assertEquals(0, plan.apply(kernel).derived.outsideSites)
        assertEquals(before, UltraKernelSpec.sha256(boot))
    }

    @Test
    fun historicalArchiveRecoveryFeedsKeylessProductionGeneration() {
        val sample = File("../MapleSU_Reference/Boots/OnePlusAce6_6.6/boot.img")
        assumeTrue("reference boot sample not available", sample.isFile)
        val before = UltraKernelSpec.sha256(sample)
        val raw = UltraKernelPlan.readKernel(sample)
        val fileAssets = UltraKernelFileAssets(File("src/main/assets"))
        val specPath = "ultra-kernels/recovery/k22-ultra-6.6.89.spec"
        val specBytes = fileAssets.read(specPath)
        val archived = UltraKernelSpec.parse(specBytes.toString(Charsets.UTF_8))
        val oldRaw = archived.apply(raw).kernel
        val bytes = ByteArray((4096 + raw.size + 4095) / 4096 * 4096)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("ANDROID!".toByteArray())
            putInt(8, raw.size)
            putInt(20, 1584)
            putInt(40, 4)
        }
        raw.copyInto(bytes, 4096)
        val pristineSha = UltraKernelSpec.sha256(bytes)
        oldRaw.copyInto(bytes, 4096)
        val oldBootSha = UltraKernelSpec.sha256(bytes)
        val release = UltraKernelSpec.releaseOf(raw)
        val metaPath = "ultra-kernels/recovery/releases/$release/meta.json"
        val originalMeta = Json.parseToJsonElement(fileAssets.read(metaPath).toString(Charsets.UTF_8)).jsonObject
        val originalAsset = originalMeta.getValue("assets").jsonArray.first().jsonObject
        val updatedAsset = buildJsonObject {
            originalAsset.forEach { (name, value) -> put(name, value) }
            put("specSha256", UltraKernelSpec.sha256(specBytes))
            put("patchCount", archived.patches.size)
            put("baseKernelSha256", UltraKernelSpec.sha256(raw))
            put("expectedKernelSha256", UltraKernelSpec.sha256(oldRaw))
            put("expectedImageSize", bytes.size)
            put("knownBaseImageSha256", pristineSha)
            put("expectedBootSha256", oldBootSha)
        }
        val metadata = buildJsonObject {
            originalMeta.forEach { (name, value) -> put(name, value) }
            put("assets", JsonArray(listOf(updatedAsset)))
        }.toString().toByteArray()
        val assets = object : UltraKernelAssetSource by fileAssets {
            override fun read(path: String): ByteArray = if (path == metaPath) metadata else fileAssets.read(path)
        }
        val workspace = Files.createTempDirectory("ultrakernel-historical-upgrade-").toFile()
        try {
            val installed = File(workspace, "historical.img").apply { writeBytes(bytes) }
            val recovered = UltraKernelOriginalRecovery(assets).recoverPinned(installed, File(workspace, "recovered"))
            assertNotNull("the published historical spec must recover its complete preimage", recovered)
            assertEquals(pristineSha, UltraKernelSpec.sha256(requireNotNull(recovered).image))
            val flow = UltraKernelFlow(File(workspace, "flow"), assets)
            val plan = flow.generatePlan(installed)
            assertEquals(UltraKernelSpec.sha256(raw), plan.baseKernelSha256)
            assertEquals(null, plan.credential)
            assertEquals(null, plan.slots)
            assertFalse("preview must not write its workspace", File(workspace, "flow").exists())
            val prepared = flow.prepare(installed)
            assertFalse(prepared.alreadyInstalled)
            assertTrue(prepared.generated)
            val generated = prepared.variant as UltraKernelGeneratedAsset
            assertEquals(null, generated.plan.credential)
            assertEquals(null, generated.plan.slots)
            val candidate = requireNotNull(prepared.candidate)
            assertEquals(pristineSha, candidate.baseBootSha256)
            assertEquals(oldBootSha, UltraKernelSpec.sha256(installed))
            assertFalse(plan.records.any { it.role == "metadata-rebase" })
            assertEquals(plan.derived?.patchedKernelSha256, candidate.kernelSha256)
            assertEquals(before, UltraKernelSpec.sha256(sample))
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun legacyPlan(kernel: ByteArray, credential: UltraKernelCredential): UltraKernelPlan {
        val symbols = UltraKernelExecveEntry.resolve(UltraKernelKallsymsTable.decode(kernel), kernel)
        val rendered = UltraKernelRecipe.k22().renderDetailed(symbols, kernel, credential,
            preservedMetadataDisplacement = credential.metadataDisplacement)
        val envelope = rendered.records.single { it.role == "free_initmem+0x0" }
        val executor = rendered.records.single { it.role == "__drm_printfn_coredump+0x0" }
        val displacement = credential.metadataDisplacement ?: Math.toIntExact(
            envelope.site.toLong() + 4 - executor.site - UltraKernelBodies.EXECUTOR_CALL_OFFSET)
        val records = rendered.records.map {
            UltraKernelPlan.PatchRecord(it.role, it.site.toLong(), it.original, it.replacement)
        }
        val plan = UltraKernelPlan.fromRecords(UltraKernelSpec.releaseOf(kernel), records,
            credential = credential, baseKernelSha256 = UltraKernelSpec.sha256(kernel),
            sdkMetadataDisplacement = displacement, layout = rendered.layout).rebind(credential)
        return plan.withDerived(plan.apply(kernel).derived)
    }
}
