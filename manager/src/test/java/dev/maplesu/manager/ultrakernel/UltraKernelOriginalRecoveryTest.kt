package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.BootImageInspector
import dev.maplesu.image.BootImageRebuilder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UltraKernelOriginalRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val release = "6.6.89-android15-stock-recovery-fixture"

    @Test fun installedSpecInspectionDoesNotCreateARecoveredBootOrChangeInputs() {
        val fixture = fixture()
        val directory = requireNotNull(fixture.candidate.image.parentFile)
        fun files() = directory.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(directory).path to UltraKernelSpec.sha256(it)
        }
        val before = files()
        val spec = UltraKernelOriginalRecovery(pinnedAssetsWithRejectedSibling(fixture))
            .installedSpec(fixture.candidate.image, directory)
        assertNotNull(spec)
        assertTrue(spec!!.isApplied(UltraKernelPlan.readKernel(fixture.candidate.image)))
        assertEquals(before, files())
        fixture.candidate.image.appendBytes(byteArrayOf(1))
        assertNull(UltraKernelOriginalRecovery(pinnedAssetsWithRejectedSibling(fixture))
            .installedSpec(fixture.candidate.image, directory))
    }

    @Test fun retainedOriginalIsPreferredWithPinnedKernelDigests() {
        val fixture = fixture()
        val recovered = recover(fixture, listOf(fixture.base))
        assertNotNull(recovered)
        assertEquals(UltraKernelOriginalRecovery.Source.LOCAL_PREIMAGE, recovered!!.source)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered.image))
        assertTrue(fixture.candidate.expectedPinned)
    }

    @Test fun retainedOriginalIsPreferredOverCompletePinnedRecovery() {
        val fixture = fixture()
        val before = UltraKernelSpec.sha256(fixture.candidate.image)
        val recovered = recover(fixture, listOf(fixture.base), assets = pinnedAssetsWithRejectedSibling(fixture))
        assertNotNull(recovered)
        assertEquals(UltraKernelOriginalRecovery.Source.LOCAL_PREIMAGE, recovered!!.source)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered.image))
        assertEquals(before, UltraKernelSpec.sha256(fixture.candidate.image))
    }

    @Test fun retainedOriginalNeedsNoLocalReceiptWhenTheCompleteCandidateIsPinned() {
        val fixture = fixture()
        val assets = pinnedAssetsWithRejectedSibling(fixture)
        assertTrue(fixture.receipt.delete())
        val recovered = recover(fixture, listOf(fixture.base), assets = assets)
        assertNotNull(recovered)
        assertEquals(UltraKernelOriginalRecovery.Source.LOCAL_PREIMAGE, recovered!!.source)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered.image))
    }

    @Test fun missingOrInvalidOriginalStillUsesCompletePinnedRecoveryWithoutALocalReceipt() {
        for (invalid in listOf(false, true)) {
            val fixture = fixture()
            val assets = pinnedAssetsWithRejectedSibling(fixture)
            val original = File(fixture.base.parentFile, "unavailable-original.img")
            if (invalid) original.writeBytes(fixture.base.readBytes().apply { this[64] = 1 })
            assertTrue(fixture.receipt.delete())
            val before = UltraKernelSpec.sha256(fixture.candidate.image)
            val recovered = recover(fixture, listOf(original), assets = assets)
            assertNotNull(recovered)
            assertEquals(UltraKernelOriginalRecovery.Source.PINNED_SPEC_REVERSE, recovered!!.source)
            assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered.image))
            assertEquals(before, UltraKernelSpec.sha256(fixture.candidate.image))
        }
    }

    @Test fun invalidOriginalStillUsesTheMatchingReceiptRecovery() {
        val fixture = fixture()
        val original = File(fixture.base.parentFile, "invalid-original.img").apply {
            writeBytes(fixture.base.readBytes().apply { this[64] = 1 })
        }
        val recovered = recover(fixture, listOf(original))
        assertNotNull(recovered)
        assertEquals(UltraKernelOriginalRecovery.Source.EXACT_SPEC_REVERSE, recovered!!.source)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered.image))
    }

    @Test fun generatedOnlyDoesNotUseAPackagedPinOrOriginal() {
        val fixture = fixture()
        val output = temporary.newFolder("unused-generated-output")
        assertNull(UltraKernelOriginalRecovery(pinnedAssetsWithRejectedSibling(fixture)).recover(
            fixture.candidate.image, fixture.receipt.parentFile, listOf(fixture.base), output,
            generatedOnly = true))
        assertTrue(output.listFiles().isNullOrEmpty())
    }

    @Test fun exactAppliedSpecCanRestoreWithoutAnyOriginalImage() {
        val fixture = fixture()
        val recovered = recover(fixture)
        assertNotNull(recovered)
        assertEquals(UltraKernelOriginalRecovery.Source.EXACT_SPEC_REVERSE, recovered!!.source)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered.image))
        assertEquals(UltraKernelSpec.sha256(fixture.candidate.image), fixture.candidate.bootSha256)
    }

    @Test fun pinnedRecoveryIgnoresAnUnrelatedRejectedSibling() {
        val fixture = fixture()
        val assets = pinnedAssetsWithRejectedSibling(fixture)
        val selection = requireNotNull(UltraKernelFlow(temporary.newFolder("selection"), assets)
            .selectForRelease(release))
        assertEquals(listOf("fixture"), selection.usable.map { it.id })
        assertEquals(1, selection.rejected.size)

        val recovered = UltraKernelOriginalRecovery(assets).recoverPinned(
            fixture.candidate.image, temporary.newFolder("pinned-recovery"),
        )
        assertNotNull(recovered)
        assertEquals(UltraKernelOriginalRecovery.Source.PINNED_SPEC_REVERSE, recovered!!.source)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered.image))
        assertArrayEquals(kernel(), UltraKernelPlan.readKernel(recovered.image))
    }

    @Test fun pinnedRecoveryStillChecksTheSelectedAssetDigestsWithARejectedSibling() {
        val fixture = fixture()
        for (field in listOf("specSha256", "baseKernelSha256", "expectedKernelSha256",
            "knownBaseImageSha256", "expectedBootSha256")) {
            val assets = pinnedAssetsWithRejectedSibling(fixture, field to "0".repeat(64))
            assertNull(field, UltraKernelOriginalRecovery(assets).recoverPinned(
                fixture.candidate.image, temporary.newFolder("invalid-$field"),
            ))
        }
    }

    @Test fun everyAospHeaderCanReverseWithoutAnOriginalImage() {
        for (version in 0..4) {
            for (page in if (version < 3) listOf(2048, 4096, 8192, 16384) else listOf(4096)) {
                val fixture = fixture(headerVersion = version, page = page)
                val recovered = recover(fixture)
                assertNotNull("v$version/page$page", recovered)
                assertEquals(UltraKernelOriginalRecovery.Source.EXACT_SPEC_REVERSE, recovered!!.source)
                assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered.image))
                val report = BootImageInspector.inspect(recovered.image)
                assertEquals(version, report.headerVersion)
                assertEquals(page.toLong(), report.pageSize)
                assertArrayEquals(kernel(), UltraKernelPlan.readKernel(recovered.image))
            }
        }
    }

    @Test fun zeroPaddedPartitionStillBindsTheCompleteCandidateAndOriginal() {
        val fixture = fixture(sourceSize = 65_536)
        assertTrue(fixture.candidate.image.length() < fixture.base.length())
        val installed = File(fixture.base.parentFile, "live-partition.img")
            .apply { writeBytes(fixture.candidate.image.readBytes().copyOf(65_536)) }
        assertNotNull(recover(fixture, listOf(fixture.base), installed))
        val reversed = recover(fixture, currentBoot = installed)
        assertNotNull(reversed)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(reversed!!.image))
        installed.writeBytes(installed.readBytes().apply { this[lastIndex] = 1 })
        assertNull(recover(fixture, listOf(fixture.base), installed))
    }

    @Test fun consistentOverlappingRecordsAreReversedAgainstTheirPristineBytes() {
        val fixture = fixture("1024|0000|0102\n1025|00|03")
        val recovered = recover(fixture)
        assertNotNull(recovered)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered!!.image))
    }

    @Test fun incompleteOriginalCoverageIsRejectedEvenWhenUnknownBytesHappenToBeZero() {
        assertNull(recover(fixture("1024|00|010000")))
    }

    @Test fun currentBootMustMatchTheCompleteRecordedCandidate() {
        val fixture = fixture()
        fixture.candidate.image.writeBytes(fixture.candidate.image.readBytes().apply { this[64] = 88 })
        assertNull(recover(fixture, listOf(fixture.base)))
    }

    @Test fun specDigestMismatchOrMissingReceiptDoesNotProduceStock() {
        val fixture = fixture()
        rewriteReceipt(fixture) { put("specSha256", "0".repeat(64)) }
        assertNull(recover(fixture, listOf(fixture.base)))
        fixture.receipt.delete()
        assertNull(recover(fixture, listOf(fixture.base)))
    }

    @Test fun reverseOutputMustMatchBothRawKernelAndCompleteBaseBootDigests() {
        val fixture = fixture()
        rewriteReceipt(fixture) { put("baseKernelSha256", "0".repeat(64)) }
        assertNull(recover(fixture))
        rewriteReceipt(fixture) {
            put("baseKernelSha256", UltraKernelSpec.sha256(kernel()))
            put("baseBootSha256", "0".repeat(64))
        }
        assertNull(recover(fixture))
    }

    @Test fun changesOutsideKernelPatchSitesCannotBePromotedToAStockBaseline() {
        val fixture = fixture()
        fixture.candidate.image.writeBytes(fixture.candidate.image.readBytes().apply { this[64] = 88 })
        rewriteReceipt(fixture) { put("candidateBootSha256", UltraKernelSpec.sha256(fixture.candidate.image)) }
        assertNull(recover(fixture))
        assertNull(recover(fixture, listOf(fixture.base)))
    }

    @Test fun lostOriginalSignatureRequiresTheActualOriginalInsteadOfInventingBytes() {
        val fixture = fixture(signature = ByteArray(256).apply { "AVB0".toByteArray().copyInto(this) })
        assertNull(recover(fixture))
        assertNotNull(recover(fixture, listOf(fixture.base)))
    }

    @Test fun generatedOrIncompleteBuilderReceiptsAreNotTrustedAsPackagedSpecs() {
        val fixture = fixture()
        rewriteReceipt(fixture) { put("planOrigin", "GENERATED") }
        assertNull(recover(fixture, listOf(fixture.base)))
    }

    @Test fun archivedSpecRestoresAnInstalledCandidateAfterThePackagedSpecChanges() {
        val fixture = fixture()
        assertNull(recover(fixture, assets = upgradedAssets(fixture, archive = false)))
        val assets = upgradedAssets(fixture)
        for (originals in listOf(emptyList(), listOf(fixture.base))) {
            val recovered = recover(fixture, originals, assets = assets)
            assertNotNull(recovered)
            assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered!!.image))
        }
        val candidate = buildHistorical(fixture.base, assets, temporary.newFolder("current-builder"))
        assertNotEquals(fixture.candidate.kernelSha256, candidate.kernelSha256)
        val flowWorkspace = temporary.newFolder("current-flow")
        val error = assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelFlow(flowWorkspace, assets).prepare(fixture.base)
        }
        assertEquals(UltraKernelGenerateCodes.KALLSYMS, error.code)
        assertTrue(flowWorkspace.listFiles().orEmpty().none { it.name.startsWith("candidate-") })
    }

    @Test fun corruptedArchivedSpecDoesNotRestoreAnOldCandidate() {
        val fixture = fixture()
        assertNull(recover(fixture, listOf(fixture.base), assets = upgradedAssets(fixture, corrupt = true)))
        assertNull(recover(fixture, assets = upgradedAssets(fixture, corrupt = true)))
    }

    @Test fun explicitHistoricalUpgradeRestoresTheReceiptBoundOldPatchBeforeBuilding() {
        val fixture = fixture()
        val before = UltraKernelSpec.sha256(fixture.candidate.image)
        val upgraded = upgradedAssets(fixture)
        val restored = requireNotNull(recover(fixture, assets = upgraded))
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(restored.image))
        val candidate = buildHistorical(restored.image, upgraded, temporary.newFolder("historical-upgrade"))
        assertEquals(UltraKernelSpec.sha256(fixture.base), candidate.baseBootSha256)
        assertNotEquals(before, candidate.bootSha256)
        assertEquals(before, UltraKernelSpec.sha256(fixture.candidate.image))
    }

    @Test fun paddedOldBootUpgradeKeepsTheOldPartitionSnapshotIntact() {
        val fixture = fixture(sourceSize = 65_536)
        val installed = File(fixture.base.parentFile, "partition.img").apply {
            writeBytes(fixture.candidate.image.readBytes().copyOf(65_536))
        }
        val before = UltraKernelSpec.sha256(installed)
        val assets = upgradedAssets(fixture)
        val restored = requireNotNull(recover(fixture, currentBoot = installed, assets = assets))
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(restored.image))
        val candidate = buildHistorical(restored.image, assets, temporary.newFolder("padded-historical-upgrade"))
        assertEquals(UltraKernelSpec.sha256(fixture.base), candidate.baseBootSha256)
        assertEquals(before, UltraKernelSpec.sha256(installed))
    }

    @Test fun previousRecoveryArchiveAlsoSupportsReceiptBoundUpgrades() {
        val fixture = fixture()
        val archived = upgradedAssets(fixture)
        val files = mutableMapOf<String, ByteArray>()
        val releaseDirectory = "releases/$release"
        for (name in listOf("meta.json", "fixture.spec")) {
            files["ultra-kernels/$releaseDirectory/$name"] = archived.read("ultra-kernels/$releaseDirectory/$name")
            files["ultra-kernels/recovery/previous/$releaseDirectory/$name"] =
                archived.read("ultra-kernels/recovery/$releaseDirectory/$name")
        }
        val recovered = recover(fixture, assets = UltraKernelMemoryAssets(files))
        assertNotNull(recovered)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered!!.image))
    }

    @Test fun legacyPacArchiveAlsoSupportsReceiptBoundUpgrades() {
        val fixture = fixture()
        val archived = upgradedAssets(fixture)
        val files = mutableMapOf<String, ByteArray>()
        val releaseDirectory = "releases/$release"
        for (name in listOf("meta.json", "fixture.spec")) {
            files["ultra-kernels/$releaseDirectory/$name"] = archived.read("ultra-kernels/$releaseDirectory/$name")
            files["ultra-kernels/recovery/pac-entry-legacy/$releaseDirectory/$name"] =
                archived.read("ultra-kernels/recovery/$releaseDirectory/$name")
        }
        val recovered = recover(fixture, assets = UltraKernelMemoryAssets(files))
        assertNotNull(recovered)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered!!.image))
    }

    @Test fun localCacheArchiveAlsoSupportsReceiptBoundUpgrades() {
        val fixture = fixture()
        val archived = upgradedAssets(fixture)
        val files = mutableMapOf<String, ByteArray>()
        val releaseDirectory = "releases/$release"
        for (name in listOf("meta.json", "fixture.spec")) {
            files["ultra-kernels/$releaseDirectory/$name"] = archived.read("ultra-kernels/$releaseDirectory/$name")
            files["ultra-kernels/recovery/ic-iallu/$releaseDirectory/$name"] =
                archived.read("ultra-kernels/recovery/$releaseDirectory/$name")
        }
        val recovered = recover(fixture, assets = UltraKernelMemoryAssets(files))
        assertNotNull(recovered)
        assertEquals(UltraKernelOriginalRecovery.Source.EXACT_SPEC_REVERSE, recovered!!.source)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered.image))
    }

    @Test fun transportBoundaryArchiveAlsoSupportsReceiptBoundUpgrades() {
        val fixture = fixture()
        val archived = upgradedAssets(fixture)
        val files = mutableMapOf<String, ByteArray>()
        val releaseDirectory = "releases/$release"
        for (name in listOf("meta.json", "fixture.spec")) {
            files["ultra-kernels/$releaseDirectory/$name"] = archived.read("ultra-kernels/$releaseDirectory/$name")
            files["ultra-kernels/recovery/transport-boundary-legacy/$releaseDirectory/$name"] =
                archived.read("ultra-kernels/recovery/$releaseDirectory/$name")
        }
        val recovered = recover(fixture, assets = UltraKernelMemoryAssets(files))
        assertNotNull(recovered)
        assertEquals(UltraKernelOriginalRecovery.Source.EXACT_SPEC_REVERSE, recovered!!.source)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(recovered.image))
    }

    @Test fun preRangeTlbiArchiveSupportsBothPinnedAndReceiptBoundRecovery() =
        archivedExecutorSupportsBothPinnedAndReceiptBoundRecovery("pre-range-tlbi")

    @Test fun preTailStubArchiveSupportsBothPinnedAndReceiptBoundRecovery() =
        archivedExecutorSupportsBothPinnedAndReceiptBoundRecovery("pre-tail-stub-180")

    private fun archivedExecutorSupportsBothPinnedAndReceiptBoundRecovery(archive: String) {
        val fixture = fixture()
        val current = upgradedAssets(fixture, archive = false)
        val pinned = pinnedAssetsWithRejectedSibling(fixture)
        val directory = "releases/$release"
        val files = mutableMapOf<String, ByteArray>()
        for (name in listOf("meta.json", "fixture.spec")) {
            files["ultra-kernels/$directory/$name"] = current.read("ultra-kernels/$directory/$name")
            files["ultra-kernels/recovery/$archive/$directory/$name"] =
                pinned.read("ultra-kernels/$directory/$name")
        }
        files["ultra-kernels/recovery/$archive/$directory/broken.spec"] =
            pinned.read("ultra-kernels/$directory/broken.spec")
        val archived = UltraKernelMemoryAssets(files)
        val receiptBound = recover(fixture, assets = archived)
        assertNotNull(receiptBound)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(receiptBound!!.image))
        assertTrue(fixture.receipt.delete())
        val pinnedRecovery = UltraKernelOriginalRecovery(archived).recoverPinned(fixture.candidate.image,
            temporary.newFolder("pre-range-pinned"))
        assertNotNull(pinnedRecovery)
        assertEquals(UltraKernelOriginalRecovery.Source.PINNED_SPEC_REVERSE, pinnedRecovery!!.source)
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(pinnedRecovery.image))
        fixture.candidate.image.writeBytes(fixture.candidate.image.readBytes().apply { this[4096 + 2048] = 1 })
        assertNull(UltraKernelOriginalRecovery(archived).recoverPinned(fixture.candidate.image,
            temporary.newFolder("pre-range-tampered")))
    }

    @Test fun old140ByteExecutorRestoresExactlyBeforeExplicitHistoricalRebuild() =
        oldExecutorRestoresExactlyBeforeInstallingTailStub("pre-range-tlbi", 140)

    @Test fun intermediate180ByteExecutorRestoresExactlyBeforeExplicitHistoricalRebuild() =
        oldExecutorRestoresExactlyBeforeInstallingTailStub("pre-tail-stub-180", 180)

    private fun oldExecutorRestoresExactlyBeforeInstallingTailStub(archive: String, oldLength: Int) {
        val oldBody = UltraKernelSpec.read(File("src/main/assets/ultra-kernels/recovery/$archive/k22-ultra-6.6.89.spec"))
            .patches.single { it.replacement.size == oldLength }.replacement
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
        val fixture = fixture("1024|${"00".repeat(oldBody.size)}|${oldBody.hex()}")
        val next = UltraKernelBodies.generate("printfn-bracket", 1024,
            UltraKernelSymbolResolver(UltraKernelPlanFixtures.symbols()),
            UltraKernelFields(comm = 0x830, cred = 0x820, credSuid = 16)).bytes
        assertEquals(188, next.size)
        val assets = upgradedAssets(fixture, nextRecords = "1024|${"00".repeat(next.size)}|${next.hex()}")
        val files = mutableMapOf<String, ByteArray>()
        for (name in listOf("meta.json", "fixture.spec")) {
            val directory = "releases/$release/$name"
            files["ultra-kernels/$directory"] = assets.read("ultra-kernels/$directory")
            files["ultra-kernels/recovery/$archive/$directory"] = assets.read("ultra-kernels/recovery/$directory")
        }
        val before = UltraKernelSpec.sha256(fixture.candidate.image)
        val archived = UltraKernelMemoryAssets(files)
        val restored = requireNotNull(recover(fixture, assets = archived))
        assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(restored.image))
        val candidate = buildHistorical(restored.image, archived, temporary.newFolder("executor-historical-upgrade"))
        assertEquals(UltraKernelSpec.sha256(fixture.base), candidate.baseBootSha256)
        assertArrayEquals(next, UltraKernelPlan.readKernel(candidate.image).copyOfRange(1024, 1024 + next.size))
        assertEquals(before, UltraKernelSpec.sha256(fixture.candidate.image))
    }

    @Test fun tamperedOldPatchNeverBecomesAnUpgradeBase() {
        val fixture = fixture()
        fixture.candidate.image.writeBytes(fixture.candidate.image.readBytes().apply { this[4096 + 2048] = 1 })
        val before = UltraKernelSpec.sha256(fixture.candidate.image)
        assertNull(recover(fixture, assets = upgradedAssets(fixture)))
        assertThrows(IllegalStateException::class.java) {
            UltraKernelFlow(fixture.receipt.parentFile, upgradedAssets(fixture))
                .prepare(fixture.candidate.image)
        }
        assertEquals(before, UltraKernelSpec.sha256(fixture.candidate.image))
    }

    @Test fun repeatedLocalRecoveryCanReuseItsPrivateOutputDirectory() {
        val fixture = fixture()
        val output = temporary.newFolder("repeated-recovery")
        val recovery = UltraKernelOriginalRecovery(fixture.assets)
        repeat(2) {
            val restored = recovery.recover(fixture.candidate.image, fixture.receipt.parentFile,
                listOf(fixture.base), output)
            assertNotNull(restored)
            assertEquals(UltraKernelSpec.sha256(fixture.base), UltraKernelSpec.sha256(restored!!.image))
        }
    }


    @Test fun matchingReceiptPreservesTheOriginalOutputIoException() {
        val fixture = fixture()
        val failure = java.io.IOException("RECOVERY_ENOSPC_FIXTURE")
        val output = FailingOutputFile(File(fixture.base.parentFile, "io-output"), failure)
        val thrown = assertThrows(java.io.IOException::class.java) {
            UltraKernelOriginalRecovery(fixture.assets).recover(fixture.candidate.image,
                fixture.receipt.parentFile, emptyList(), output)
        }
        assertSame(failure, thrown)
    }

    @Test fun pinnedRecoveryPreservesTheOriginalOutputIoException() {
        val fixture = fixture()
        val failure = java.io.IOException("PINNED_ENOSPC_FIXTURE")
        val output = FailingOutputFile(File(fixture.base.parentFile, "pinned-output"), failure)
        val thrown = assertThrows(java.io.IOException::class.java) {
            UltraKernelOriginalRecovery(pinnedAssetsWithRejectedSibling(fixture))
                .recoverPinned(fixture.candidate.image, output)
        }
        assertSame(failure, thrown)
    }

    @Test fun invalidReceiptStillFallsBackBeforeAttemptingAnyOutput() {
        val fixture = fixture()
        fixture.receipt.writeText("{")
        val output = FailingOutputFile(File(fixture.base.parentFile, "unused-output"),
            java.io.IOException("MUST_NOT_WRITE"))
        assertNull(UltraKernelOriginalRecovery(fixture.assets).recover(fixture.candidate.image,
            fixture.receipt.parentFile, emptyList(), output))
    }

    @Test fun localReplayWriteFailureDoesNotSwitchToReverseRecovery() {
        val fixture = fixture()
        val output = temporary.newFolder("local-io")
        val blocked = File(output, "local-proof-0/kernel-fixture.img")
        assertTrue(blocked.mkdirs())
        File(blocked, "keep").writeText("block an actual output stream")
        val before = UltraKernelSpec.sha256(fixture.candidate.image)
        val thrown = assertThrows(java.io.IOException::class.java) {
            UltraKernelOriginalRecovery(fixture.assets).recover(fixture.candidate.image,
                fixture.receipt.parentFile, listOf(fixture.base), output)
        }
        assertTrue(thrown.message.orEmpty().contains("kernel-fixture.img"))
        assertFalse(File(output, "original.img").exists())
        assertEquals(before, UltraKernelSpec.sha256(fixture.candidate.image))
    }

    @Test fun reverseWriteFailureDoesNotReturnANonmatchingReceipt() {
        val fixture = fixture()
        val output = temporary.newFolder("reverse-io")
        assertTrue(File(output, "original-kernel.img").mkdir())
        val thrown = assertThrows(java.io.IOException::class.java) {
            UltraKernelOriginalRecovery(fixture.assets).recover(fixture.candidate.image,
                fixture.receipt.parentFile, emptyList(), output)
        }
        assertTrue(thrown.message.orEmpty().contains("original-kernel.img"))
        assertFalse(File(output, "original.img").exists())
    }

    @Test fun anOutputFileIsReportedAsIoFailure() {
        val fixture = fixture()
        val output = temporary.newFile("occupied-output")
        val thrown = assertThrows(java.io.IOException::class.java) {
            UltraKernelOriginalRecovery(fixture.assets).recover(fixture.candidate.image,
                fixture.receipt.parentFile, emptyList(), output)
        }
        assertTrue(thrown.message.orEmpty().startsWith("ULTRA_KERNEL_RECOVERY_OUTPUT_CREATE_FAILED:"))
    }

    @Test fun anUndeletableRecoveryOutputIsReportedAsIoFailure() {
        val fixture = fixture()
        val output = temporary.newFolder("occupied-original")
        val original = File(output, "original.img")
        assertTrue(original.mkdir())
        File(original, "keep").writeText("retain")
        val thrown = assertThrows(java.io.IOException::class.java) {
            UltraKernelOriginalRecovery(fixture.assets).recover(fixture.candidate.image,
                fixture.receipt.parentFile, emptyList(), output)
        }
        assertTrue(thrown.message.orEmpty().startsWith("ULTRA_KERNEL_RECOVERY_OUTPUT_DELETE_FAILED:"))
    }

    private class FailingOutputFile(path: File, private val failure: java.io.IOException) : File(path.path) {
        override fun isDirectory(): Boolean = false
        override fun mkdirs(): Boolean = throw failure
    }

    private fun pinnedAssetsWithRejectedSibling(fixture: Fixture,
        targetDigestOverride: Pair<String, String>? = null): UltraKernelAssetSource {
        val directory = "ultra-kernels/releases/$release"
        val original = Json.parseToJsonElement(fixture.assets.read("$directory/meta.json")
            .toString(Charsets.UTF_8)) as JsonObject
        val pinned = buildJsonObject {
            original.forEach { (name, value) -> put(name, value) }
            put("knownBaseImageSha256", UltraKernelSpec.sha256(fixture.base))
            put("expectedBootSha256", fixture.candidate.bootSha256)
            put("expectedImageSize", fixture.candidate.image.length())
            targetDigestOverride?.let { (name, value) -> put(name, value) }
        }
        val rejected = buildJsonObject {
            pinned.forEach { (name, value) -> put(name, value) }
            put("id", "broken")
            put("spec", "broken.spec")
            put("specSha256", "0".repeat(64))
        }
        val meta = buildJsonObject {
            put("schema", 1)
            put("kernelRelease", release)
            put("defaultAsset", "fixture")
            put("assets", JsonArray(listOf(pinned, rejected)))
        }
        val spec = fixture.assets.read("$directory/fixture.spec")
        return UltraKernelMemoryAssets(mapOf(
            "$directory/meta.json" to meta.toString().toByteArray(),
            "$directory/fixture.spec" to spec,
            "$directory/broken.spec" to spec,
        ))
    }

    private fun upgradedAssets(fixture: Fixture, archive: Boolean = true, corrupt: Boolean = false,
        nextRecords: String = "1024|00|02"): UltraKernelAssetSource {
        val directory = "ultra-kernels/releases/$release"
        val originalSpec = fixture.assets.read("$directory/fixture.spec")
        val nextSpec = "#kernel=$release\n#key=${"Aa1".repeat(16)}\n$nextRecords".toByteArray()
        val originalMeta = Json.parseToJsonElement(fixture.assets.read("$directory/meta.json").toString(Charsets.UTF_8)) as JsonObject
        val nextMeta = buildJsonObject {
            originalMeta.forEach { (name, value) -> put(name, value) }
            put("specSha256", UltraKernelSpec.sha256(nextSpec))
            put("expectedKernelSha256", UltraKernelSpec.sha256(
                UltraKernelSpec.parse(nextSpec.toString(Charsets.UTF_8)).apply(kernel()).kernel))
        }.toString().toByteArray()
        val files = mutableMapOf("$directory/fixture.spec" to nextSpec, "$directory/meta.json" to nextMeta)
        if (archive) {
            files["ultra-kernels/recovery/releases/$release/meta.json"] = fixture.assets.read("$directory/meta.json")
            files["ultra-kernels/recovery/releases/$release/fixture.spec"] =
                if (corrupt) nextSpec else originalSpec
        }
        return UltraKernelMemoryAssets(files)
    }

    private fun buildHistorical(base: File, assets: UltraKernelAssetSource, output: File): UltraKernelBuilder.Candidate {
        val asset = UltraKernelReleaseCatalog.load(assets).assetsFor(release).single()
        val spec = UltraKernelSpec.parse(assets.read(asset.specAsset).toString(Charsets.UTF_8))
        return UltraKernelBuilder(output).generate(base, asset, spec)
    }

    private fun recover(fixture: Fixture, originals: List<File> = emptyList(), currentBoot: File = fixture.candidate.image,
        assets: UltraKernelAssetSource = fixture.assets) =
        UltraKernelOriginalRecovery(assets).recover(
            currentBoot, fixture.receipt.parentFile, originals,
            temporary.newFolder("recovery-${System.nanoTime()}"),
        )

    private fun fixture(records: String = "1024|00|01", signature: ByteArray = ByteArray(0), sourceSize: Int? = null,
        headerVersion: Int = 4, page: Int = 4096): Fixture {
        val directory = temporary.newFolder("fixture-${System.nanoTime()}")
        val specBytes = "#kernel=$release\n$records".toByteArray()
        val meta = buildJsonObject {
            put("schema", 1)
            put("kernelRelease", release)
            put("id", "fixture")
            put("spec", "fixture.spec")
            put("specSha256", UltraKernelSpec.sha256(specBytes))
            put("patchCount", records.lines().size)
            put("baseKernelSha256", UltraKernelSpec.sha256(kernel()))
            put("expectedKernelSha256", UltraKernelSpec.sha256(
                UltraKernelSpec.parse(specBytes.toString(Charsets.UTF_8)).apply(kernel()).kernel))
            put("expectedImageSize", 16_384)
            put("verification", "experimental")
            put("source", "model")
        }.toString()
        val assets = UltraKernelMemoryAssets(mapOf(
            "ultra-kernels/releases/$release/meta.json" to meta.toByteArray(),
            "ultra-kernels/releases/$release/fixture.spec" to specBytes,
        ))
        val base = File(directory, "original.img")
        val bodyEnd = (page + kernel().size + page - 1) / page * page
        val bytes = ByteArray(sourceSize ?: bodyEnd + if (signature.isEmpty()) 0 else page)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("ANDROID!".toByteArray())
            putInt(8, 8192)
            putInt(40, headerVersion)
            if (headerVersion < 3) {
                putInt(36, page)
                if (headerVersion > 0) putInt(1644, if (headerVersion == 1) 1648 else 1660)
            } else {
                putInt(20, if (headerVersion == 3) 1580 else 1584)
                if (headerVersion == 4) putInt(1580, signature.size)
            }
        }
        kernel().copyInto(bytes, page)
        signature.copyInto(bytes, bodyEnd)
        base.writeBytes(bytes)
        if (headerVersion < 3) {
            // AOSP v0-v2 include a component SHA-1 in the original header.
            val raw = File(directory, "raw-kernel").apply { writeBytes(kernel()) }
            val canonical = File(directory, "canonical.img")
            BootImageRebuilder.rebuild(base, raw, canonical)
            canonical.copyTo(base, overwrite = true)
        }
        val asset = UltraKernelReleaseCatalog.load(assets).assetsFor(release).single()
        val candidate = UltraKernelBuilder(File(directory, "work")).generate(base, asset, UltraKernelSpec.parse(specBytes.toString(Charsets.UTF_8)))
        return Fixture(assets, base, candidate, File(directory, "work/candidate-fixture.json"))
    }

    private fun kernel() = ByteArray(8192).apply {
        "ARMd".toByteArray().copyInto(this, 56)
        "Linux version $release (fixture)".toByteArray().copyInto(this, 128)
    }

    private fun rewriteReceipt(fixture: Fixture, changes: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val original = Json.parseToJsonElement(fixture.receipt.readText()) as JsonObject
        fixture.receipt.writeText(buildJsonObject {
            original.forEach { (name, value) -> put(name, value) }
            changes()
        }.toString())
    }

    private class Fixture(val assets: UltraKernelAssetSource, val base: File,
        val candidate: UltraKernelBuilder.Candidate, val receipt: File)
}
