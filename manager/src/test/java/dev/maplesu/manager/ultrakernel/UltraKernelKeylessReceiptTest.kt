package dev.maplesu.manager.ultrakernel

import dev.maplesu.manager.installer.BootPartitionSnapshot
import dev.maplesu.manager.installer.InstallerSnapshot
import dev.maplesu.manager.installer.StockOriginalResolver
import dev.maplesu.manager.workmode.service.fullSizeSwitchCandidate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real Builder/receipt/original recovery boundaries; no device or module executes. */
class UltraKernelKeylessReceiptTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assets = UltraKernelMemoryAssets(emptyMap())

    @Test fun currentKeylessReceiptRestoresTheExactStockPartition() {
        val fixture = fixture()
        assertEquals("", UltraKernelSpec.read(fixture.spec).key)
        val spec = UltraKernelOriginalRecovery(assets).installedSpec(fixture.current, fixture.workspace)
        assertNotNull("the current serializer publishes a readable keyless receipt", spec)
        assertEquals("", requireNotNull(spec).key)
        assertStockRestored(fixture)
    }

    @Test fun explicitHistoricalCredentialRetainsItsReceiptAndOriginalProof() {
        val fixture = fixture(legacy = true)
        val credential = requireNotNull(fixture.credential)
        retainSpec(fixture, fixture.plan.specText(credential))
        val read = requireNotNull(UltraKernelOriginalRecovery(assets).installedSpec(fixture.current, fixture.workspace))
        assertTrue(UltraKernelCredential.isValid(read.key))
        assertEquals(credential, UltraKernelCredential.of(read.key))
        assertStockRestored(fixture)
    }

    @Test fun aNonemptyMalformedLegacyKeyIsStillRejectedAfterDigestRebinding() {
        for (key in listOf("short", "A".repeat(47), "_".repeat(48))) {
            val fixture = fixture(legacy = true)
            retainSpec(fixture, fixture.plan.specText() + "#key=$key\n")
            assertEquals(key, UltraKernelSpec.read(fixture.spec).key)
            assertFalse(UltraKernelCredential.isValid(key))
            val before = fixture.current.readBytes()
            assertNull(key, UltraKernelOriginalRecovery(assets).installedSpec(fixture.current, fixture.workspace))
            assertArrayEquals(before, fixture.current.readBytes())
        }
    }

    @Test fun keylessReceiptsStillRequireEveryPublishedIdentityBinding() {
        val fixture = fixture()
        val receipt = fixture.receipt.readText()
        val original = Json.parseToJsonElement(receipt).jsonObject
        for ((field, value) in listOf(
            "readbackVerified" to JsonPrimitive(false),
            "expectedKernelPinned" to JsonPrimitive(true),
            "candidateBootSha256" to JsonPrimitive("0".repeat(64)),
            "candidateImageBytes" to JsonPrimitive(fixture.current.length() + 1),
            "specSha256" to JsonPrimitive("0".repeat(64)),
            "baseKernelSha256" to JsonPrimitive("0".repeat(64)),
            "generatedPatchedKernelSha256" to JsonPrimitive("0".repeat(64)),
            "sourceImageBytes" to JsonPrimitive(0),
            "source" to JsonPrimitive("sample"),
            "assetKernelRelease" to JsonPrimitive("6.6.2-other"),
            "patches" to JsonPrimitive(fixture.plan.records.size + 1),
        )) {
            fixture.receipt.writeText(JsonObject(original.toMutableMap().apply { put(field, value) }).toString())
            assertNull(field, UltraKernelOriginalRecovery(assets).installedSpec(fixture.current, fixture.workspace))
        }
        fixture.receipt.writeText(receipt)
        assertNotNull(UltraKernelOriginalRecovery(assets).installedSpec(fixture.current, fixture.workspace))
        val retained = fixture.spec.readBytes()
        fixture.spec.appendText("#tampered\n")
        assertNull("retained spec bytes stay digest bound",
            UltraKernelOriginalRecovery(assets).installedSpec(fixture.current, fixture.workspace))
        fixture.spec.writeBytes(retained)
        val before = fixture.current.readBytes()
        fixture.current.writeBytes(before.copyOf().apply { this[4096 + 1024] = 0 })
        assertNull("installed image stays digest bound",
            UltraKernelOriginalRecovery(assets).installedSpec(fixture.current, fixture.workspace))
        fixture.current.writeBytes(before)
        assertStockRestored(fixture)
    }

    private fun assertStockRestored(fixture: Fixture) {
        val before = fixture.current.readBytes()
        val snapshot = InstallerSnapshot("6.6.1-fixture", "fixture", "_a", "Enforcing", mapOf("boot" to
            BootPartitionSnapshot("boot", "", "", fixture.current.length(), UltraKernelSpec.sha256(fixture.current))))
        val resolved = StockOriginalResolver(assets, fixture.workspace, listOf(fixture.base))
            .resolve(snapshot, mapOf("boot" to fixture.current), temporary.newFolder())
        assertArrayEquals(fixture.base.readBytes(), resolved.originals.getValue("boot").readBytes())
        assertArrayEquals(before, fixture.current.readBytes())
    }

    private fun retainSpec(fixture: Fixture, text: String) {
        val sha = UltraKernelSpec.sha256(text.toByteArray())
        fixture.spec = File(fixture.workspace, "candidate-${fixture.asset.id}-$sha.spec").apply { writeText(text) }
        val receipt = Json.parseToJsonElement(fixture.receipt.readText()).jsonObject
        fixture.receipt.writeText(JsonObject(receipt.toMutableMap().apply { put("specSha256", JsonPrimitive(sha)) }).toString())
    }

    private fun fixture(legacy: Boolean = false): Fixture {
        val raw = ByteArray(8192).apply {
            "ARMd".toByteArray().copyInto(this, 56)
            "Linux version 6.6.1-fixture (receipt fixture)".toByteArray().copyInto(this, 128)
        }
        val base = File(temporary.newFolder(), "original.img")
        val image = ByteArray(131072)
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("ANDROID!".toByteArray()); putInt(8, raw.size); putInt(20, 1584); putInt(40, 4)
        }
        raw.copyInto(image, 4096)
        base.writeBytes(image)
        val credential = if (legacy) UltraKernelCredential.of("Aa1".repeat(16)).withMetadataDisplacement(-8192) else null
        val records = if (credential == null) listOf(
            UltraKernelPlan.PatchRecord("fixture", 1024, ByteArray(4), byteArrayOf(1, 2, 3, 4)),
            UltraKernelPlan.PatchRecord("manager_claim:deployment_namespace", 3072,
                ByteArray(UltraKernelDeploymentNamespace.RECORD_BYTES), UltraKernelDeploymentNamespace.create().record()))
        else listOf(
            UltraKernelPlan.PatchRecord("credential", 1024, ByteArray(48), credential.copySecret()),
            UltraKernelPlan.PatchRecord("path", 2048, ByteArray(UltraKernelCredential.INIT_PATH_FIELD),
                credential.initPathField(UltraKernelCredential.INIT_PATH_FIELD)))
        val plan = UltraKernelPlan.fromRecords("6.6.1-fixture", records, credential,
            baseKernelSha256 = UltraKernelSpec.sha256(raw)).let { it.withDerived(it.apply(raw).derived) }
        val text = plan.specText()
        val asset = UltraKernelGeneratedAsset.of(plan, base.length(), 0, text)
        val workspace = temporary.newFolder()
        val compact = UltraKernelBuilder(workspace).generate(base, asset, UltraKernelSpec.parse(text)).image
        val current = fullSizeSwitchCandidate(compact, File(temporary.newFolder(), "boot.img"), base.length())
        return Fixture(base, workspace, asset, plan, credential, current,
            File(workspace, "candidate-${asset.id}.json"),
            File(workspace, "candidate-${asset.id}-${asset.specSha256}.spec"))
    }

    private class Fixture(val base: File, val workspace: File, val asset: UltraKernelGeneratedAsset,
        val plan: UltraKernelPlan, val credential: UltraKernelCredential?, val current: File,
        val receipt: File, var spec: File)
}
