package dev.maplesu.manager.workmode.service

import dev.maplesu.image.UltraInitBootProducer
import dev.maplesu.manager.installer.BootPartitionSnapshot
import dev.maplesu.manager.installer.InstallerSnapshot
import dev.maplesu.manager.installer.StockOriginalResolver
import dev.maplesu.manager.ksu.KsuRootCommandRunner
import dev.maplesu.manager.ultrakernel.*
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.switch.CandidateInput
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real inverse/CPIO/Flow boundaries, file-only; no root process, module or signing qualification. */
class RecoveredDeploymentLineageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val assets = UltraKernelMemoryAssets(emptyMap())
    private val credential = UltraKernelCredential.of("Aa1".repeat(16))

    @Test fun bothOuterBootRecoveriesKeepCleanBaselinesWhileVerifiedPreCarriesLineage() {
        val f = fixture()
        val record = namespace('A')
        val receipts = temporary.newFolder()
        val raw = kernel()
        val plan = UltraKernelPlan.fromRecords("6.6.1-fixture", listOf(
            UltraKernelPlan.PatchRecord("fixture", 1024, ByteArray(4), byteArrayOf(1, 2, 3, 4)),
            UltraKernelPlan.PatchRecord("manager_claim:deployment_namespace", 3072, ByteArray(24), record.record())),
            baseKernelSha256 = UltraKernelSpec.sha256(raw)).let { it.withDerived(it.apply(raw).derived) }
        val text = plan.specText()
        val asset = UltraKernelGeneratedAsset.of(plan, f.targetBoot.length(), 0, text)
        val compact = UltraKernelBuilder(receipts).generate(f.targetBoot, asset, UltraKernelSpec.parse(text)).image
        // Recovery consumes a fixed partition PRE, not the compact producer export.
        val installed = fullSizeSwitchCandidate(compact, File(temporary.newFolder(), "boot.img"),
            f.targetBoot.length())
        val before = installed.readBytes()
        val snapshot = InstallerSnapshot("6.6.1-fixture", "fixture", "_a", "Enforcing", mapOf("boot" to
            BootPartitionSnapshot("boot", "", "", installed.length(), UltraKernelSpec.sha256(installed))))
        val resolved = StockOriginalResolver(assets, receipts, listOf(f.targetBoot))
            .resolve(snapshot, mapOf("boot" to installed), temporary.newFolder())
        assertArrayEquals(raw, UltraKernelPlan.readKernel(resolved.originals.getValue("boot")))
        assertArrayEquals(f.targetBoot.readBytes(), resolved.originals.getValue("boot").readBytes())
        val recovered = ModeSwitchSourceRecovery(assets, receipts, listOf(f.targetBoot), "unused",
            KsuRootCommandRunner { _, _ -> error("unexpected ksud") }).recover(
                WorkMode.LITE, "boot", installed, temporary.newFolder(), true)
        assertTrue(recovered.detail, recovered.ok)
        assertArrayEquals(raw, UltraKernelPlan.readKernel(requireNotNull(recovered.image)))
        assertArrayEquals(f.targetBoot.readBytes(), requireNotNull(recovered.image).readBytes())
        val lineage = runBlocking {
            val input = CandidateInput("transaction", WorkMode.LITE, "init_boot", f.source,
                UltraKernelSpec.sha256(f.source), temporary.newFolder(), "", "6.6.1-fixture")
            ModeSwitchCandidateInput(assets, receipts).prepare(input,
                status(listOf(row("boot", installed), row("init_boot", f.source))),
                Captured(mapOf("boot" to installed, "init_boot" to f.source))).deploymentNamespace
        }
        assertArrayEquals(record.record(), requireNotNull(lineage).record())
        val candidate = build(f.source, f.artifact, lineage)
        assertArrayEquals(record.record(), UltraInitBootProducer.readDeploymentNamespace(candidate))
        assertArrayEquals(before, installed.readBytes())
    }

    @Test fun transactionPreExportsRestoreInitBootLineageIntoTheActualCandidateInput() = runBlocking {
        val f = fixture()
        val record = namespace('B')
        val installed = fullSizeSwitchCandidate(build(f.source, f.artifact, record),
            File(temporary.newFolder(), "init_boot.img"), f.source.length())
        val before = installed.readBytes()
        val recovery = ModeSwitchSourceRecovery(assets, temporary.newFolder(), emptyList(), "unused",
            KsuRootCommandRunner { _, _ -> error("unexpected ksud") }).recover(
                WorkMode.LITE, "init_boot", installed, temporary.newFolder(), true)
        assertTrue(recovery.detail, recovery.ok)
        val baseline = requireNotNull(recovery.image)
        assertArrayEquals(f.source.readBytes(), baseline.readBytes())
        assertNull(UltraInitBootProducer.readDeploymentNamespace(baseline))
        val work = temporary.newFolder()
        val input = CandidateInput("transaction", WorkMode.LITE, "init_boot", baseline,
            recovery.sha256, work, "", "6.6.1-fixture")
        val row = row("init_boot", installed)
        val status = status(listOf(row))
        val client = Captured(mapOf("init_boot" to installed))
        val prepared = ModeSwitchCandidateInput(assets, temporary.newFolder()).prepare(input, status, client)
        assertArrayEquals(record.record(), requireNotNull(prepared.deploymentNamespace).record())
        val candidate = build(prepared.baselineImage, f.artifact, prepared.deploymentNamespace)
        assertArrayEquals(record.record(), UltraInitBootProducer.readDeploymentNamespace(candidate))
        assertEquals(listOf("PRE:init_boot"), client.exports)
        assertTrue(work.listFiles().orEmpty().isEmpty())
        assertNull(input.deploymentNamespace)
        assertArrayEquals(before, installed.readBytes())
    }

    @Test fun conflictingOrChangedCapturedSourcesFailBeforeAnyCandidateAndKeepNoTemporaryExport() = runBlocking {
        val f = fixture()
        val first = build(f.source, f.artifact, namespace('C'))
        val second = build(f.source, f.artifact, namespace('D'))
        val rows = listOf(row("boot", first), row("init_boot", second))
        val work = temporary.newFolder()
        val reader = ModeSwitchCandidateInput(assets, temporary.newFolder())
        val conflict = runCatching { reader.readCaptured(rows, work) { name, destination ->
            (if (name == "boot") first else second).copyTo(destination, overwrite = true); true
        } }.exceptionOrNull()
        assertEquals("SOURCE_RECOVERY_DEPLOYMENT_CONFLICT", conflict?.message)
        assertTrue(work.listFiles().orEmpty().isEmpty())
        val changed = runCatching { reader.readCaptured(rows.take(1), work) { _, destination ->
            destination.writeBytes(first.readBytes().apply { this[0] = 0 }); true
        } }.exceptionOrNull()
        assertEquals("SOURCE_RECOVERY_PRE_IMAGE_CHANGED", changed?.message)
        assertTrue(work.listFiles().orEmpty().isEmpty())
        val firstError = java.io.IOException("EXPORT_FIRST_ERROR")
        val failedCleanup = runCatching { reader.readCaptured(rows.take(1), work) { _, destination ->
            java.nio.file.Files.delete(destination.toPath())
            check(destination.mkdir())
            File(destination, "held").writeText("fixture")
            throw firstError
        } }.exceptionOrNull()
        assertSame(firstError, failedCleanup)
        assertEquals(1, firstError.suppressed.size)
    }

    @Test fun knownLineageHasAnExplicitUnsupportedErrorAndAnOwnedSourceRejectsAConflictingHint() {
        val f = fixture()
        val record = namespace('E')
        val installed = build(f.source, f.artifact, record)
        assertArrayEquals(record.record(), UltraInitBootProducer.readDeploymentNamespace(
            build(installed, f.artifact, record)))
        val conflict = runCatching { build(installed, f.artifact, namespace('F')) }.exceptionOrNull()
        assertEquals("ULTRA_INIT_BOOT_DEPLOYMENT_NAMESPACE_CONFLICT", conflict?.message)
        val legacy = fixture(support = false)
        val work = temporary.newFolder()
        val failure = runCatching { UltraKernelFlow(work, assets).prepareTarget(legacy.source,
            null, legacy.artifact, record) }.exceptionOrNull()
        assertEquals("ULTRA_KERNEL_DEPLOYMENT_SUPPORT_MISSING", failure?.message)
        assertTrue(work.listFiles().orEmpty().isEmpty())
    }

    private fun build(source: File, artifact: UltraInitBootProducer.Artifact,
                      record: UltraKernelDeploymentNamespace?): File =
        requireNotNull(UltraKernelFlow(temporary.newFolder(), assets)
            .prepareTarget(source, null, artifact, record).candidate).image

    private fun namespace(name: Char) = UltraKernelDeploymentNamespace.decode(
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(0x4e44534d)
            .putShort(1).putShort(24).put(ByteArray(16) { name.code.toByte() }).array())

    private fun row(name: String, image: File) = ClientPartition(name, "RESTORE_SOURCE", image.length(),
        UltraKernelSpec.sha256(image), "", "", "SNAPSHOT")

    private fun status(rows: List<ClientPartition>) = ClientStatus("transaction", "SNAPSHOT", "", "",
        "KSU", "LITE", "", "", "", false, false, false, false, 0, rows)

    private class Captured(private val images: Map<String, File>) : ModeSwitchClient {
        val exports = mutableListOf<String>()
        override suspend fun exportImage(switchId: String, kind: String, partition: String, destination: File): Boolean {
            check(switchId == "transaction" && kind == "PRE")
            exports += "$kind:$partition"
            images.getValue(partition).copyTo(destination, overwrite = true)
            return true
        }
        override suspend fun preflight(switchId: String, planFields: Map<String, String>): ClientResult = error("unexpected")
        override suspend fun begin(switchId: String, planFields: Map<String, String>, superuserJson: File, settingsMirrorJson: File): ClientResult = error("unexpected")
        override suspend fun snapshot(switchId: String): ClientResult = error("unexpected")
        override suspend fun submitCandidate(switchId: String, partition: String, candidate: File, candidateSha256: String): ClientResult = error("unexpected")
        override suspend fun commit(switchId: String): ClientResult = error("unexpected")
        override suspend fun rollback(switchId: String): ClientResult = error("unexpected")
        override suspend fun abort(switchId: String): ClientResult = error("unexpected")
        override fun status(switchId: String): ClientStatus? = error("unexpected")
        override suspend fun verify(switchId: String): ClientResult = error("unexpected")
        override suspend fun markRebootRequested(switchId: String): Boolean = error("unexpected")
        override suspend fun exportLog(switchId: String, destination: File): File? = error("unexpected")
    }

    private data class Fixture(val source: File, val targetBoot: File, val artifact: UltraInitBootProducer.Artifact)

    private fun fixture(support: Boolean = true): Fixture {
        val directory = temporary.newFolder()
        val kernel = kernel()
        val files = mapOf("target.img" to image(kernel, ByteArray(0)),
            "init" to handoff(), "bridge.ko" to module(support))
        files.forEach { (name, content) -> File(directory, name).writeBytes(content) }
        val manifest = File(directory, "manifest.json")
        manifest.writeText(buildJsonObject {
            put("schemaVersion", if (support) 2 else 1); put("kernelRelease", "6.6.1-fixture")
            if (support) {
                put("kind", "kmi-component"); put("kmi", "android15-6.6"); put("architecture", "arm64")
                put("pageSize", 4096); put("cfiMode", "none"); put("credentialMode", "keyless")
                put("metadataDisplacement", 4064); put("runtimeFactsAbi", "maplesu-runtime-facts/1")
                put("sourceGenerationSha256", UltraKernelSpec.sha256(kernel))
            } else {
                put("kernelSha256", UltraKernelSpec.sha256(kernel)); put("credentialMode", "embedded")
                put("credentialSha256", UltraKernelSpec.sha256(credential.copySecret()))
                put("targetBoot", "target.img"); put("targetBootSha256", UltraKernelSpec.sha256(files.getValue("target.img")))
            }
            for ((field, path) in mapOf("handoff" to "init", "bridge" to "bridge.ko")) {
                put(field, path); put(field + "Sha256", UltraKernelSpec.sha256(files.getValue(path)))
            }
        }.toString())
        return Fixture(File(directory, "source.img").apply {
            writeBytes(image(ByteArray(0), stock()))
        }, File(directory, "target.img"), UltraInitBootProducer.readArtifact(manifest))
    }

    // Local byte fixtures keep this Manager suite independent of image-core's test classpath.
    private fun stock(): ByteArray = ByteArrayOutputStream().apply {
        fun entry(name: String, mode: Int, data: ByteArray = ByteArray(0)) {
            val path = name.toByteArray(Charsets.US_ASCII)
            write("070701".toByteArray(Charsets.US_ASCII))
            for (value in intArrayOf(19, mode, 0, 0, 1, 1699999999, data.size,
                    0, 0, 0, 0, path.size + 1, 0)) {
                write(String.format(Locale.ROOT, "%08x", value).toByteArray(Charsets.US_ASCII))
            }
            write(path); write(0)
            while (size() and 3 != 0) write(0x71)
            write(data)
            while (size() and 3 != 0) write(0x53)
        }
        entry("dev", 0x41ed)
        entry("init", 0x81e8, "original-init".toByteArray(Charsets.US_ASCII))
        entry("vendor-file", 0x81a1, byteArrayOf(1, 0, -1, 7))
        entry("unrelated-marker", 0x81a4, "inert-third-party-marker".toByteArray(Charsets.US_ASCII))
        entry("dev", 0x41c0)
        entry("TRAILER!!!", 0)
        write(ByteArray(17))
    }.toByteArray()

    private fun image(kernel: ByteArray, ramdisk: ByteArray): ByteArray {
        fun align(value: Int) = (value + 4095) and -4096
        val ramdiskAt = align(4096 + kernel.size)
        return ByteArray(maxOf(align(ramdiskAt + ramdisk.size) + 8192, 131072)).apply {
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("ANDROID!".toByteArray(Charsets.US_ASCII))
                putInt(8, kernel.size); putInt(12, ramdisk.size); putInt(20, 1584); putInt(40, 4)
            }
            kernel.copyInto(this, 4096)
            ramdisk.copyInto(this, ramdiskAt)
        }
    }

    private fun kernel(): ByteArray = ByteArray(4096).apply {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(24, 2)
        "ARMd".toByteArray(Charsets.US_ASCII).copyInto(this, 56)
        "Linux version 6.6.1-fixture (byte fixture)\n".toByteArray(Charsets.US_ASCII).copyInto(this, 128)
    }

    private fun handoff(): ByteArray = ByteArray(256).apply {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(127, 69, 76, 70, 2, 1, 1))
            putShort(16, 2); putShort(18, 183); putInt(20, 1); putLong(24, 0x10080)
            putLong(32, 64); putShort(52, 64); putShort(54, 56); putShort(56, 1)
            putInt(64, 1); putInt(68, 5); putLong(72, 0); putLong(80, 0x10000)
            putLong(96, 256); putLong(104, 256); putLong(112, 65536)
        }
    }

    private fun module(support: Boolean): ByteArray {
        val names = "\u0000.shstrtab\u0000.text\u0000.symtab\u0000.gnu.linkonce.this_module\u0000.modinfo\u0000.strtab\u0000"
        val symbols = "\u0000msu_sdk_call_prefix\u0000msu_sdk_call_blr\u0000msu_sdk_call_template\u0000"
        val info = ("vermagic=6.6.1-fixture SMP\u0000maplesu_runtime_facts=1\u0000" +
            if (support) "parmtype=deployment_namespace:string\u0000" else "").toByteArray(Charsets.US_ASCII)
        return ByteArray(1060).apply {
            val h = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
            h.put(byteArrayOf(127, 69, 76, 70, 2, 1, 1))
            h.putShort(16, 1); h.putShort(18, 183); h.putInt(20, 1); h.putLong(40, 64)
            h.putShort(52, 64); h.putShort(58, 64); h.putShort(60, 7); h.putShort(62, 1)
            val sections = arrayOf("", ".shstrtab", ".text", ".symtab", ".gnu.linkonce.this_module", ".modinfo", ".strtab")
            val offsets = intArrayOf(0, 512, 1024, 704, 800, 832, 640)
            val sizes = intArrayOf(0, names.length, 36, 96, 24, info.size, symbols.length)
            for (index in 1 until sections.size) {
                val at = 64 + index * 64
                h.putInt(at, names.indexOf(sections[index]))
                h.putInt(at + 4, if (index == 1 || index == 6) 3 else if (index == 3) 2 else 1)
                h.putLong(at + 8, if (index == 2) 6 else 0)
                h.putLong(at + 24, offsets[index].toLong()); h.putLong(at + 32, sizes[index].toLong())
            }
            h.putInt(64 + 3 * 64 + 40, 6); h.putLong(64 + 3 * 64 + 56, 24)
            for ((index, name) in listOf("msu_sdk_call_prefix", "msu_sdk_call_blr", "msu_sdk_call_template").withIndex()) {
                val at = 704 + (index + 1) * 24
                h.putInt(at, symbols.indexOf(name)); h.putShort(at + 6, 2)
                h.putLong(at + 8, when (index) { 0 -> 0; 1 -> 32; else -> 4 })
            }
            names.toByteArray(Charsets.US_ASCII).copyInto(this, 512)
            symbols.toByteArray(Charsets.US_ASCII).copyInto(this, 640)
            info.copyInto(this, 832)
            h.putInt(1056, 0xd63f0140.toInt()) // Synthetic BLR x10; these bytes are never executed.
        }
    }
}
