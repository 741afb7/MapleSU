package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.BootImageInspector
import dev.maplesu.image.BootImageRebuilder
import dev.maplesu.image.KernelCompression
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Checks both the shipped templates and generation without relying on a dynamic-SCS symbol. */
class UltraKernelBootSafetyTest {
    private val sample = File("../MapleSU_Reference/Boots/OnePlusAce6_6.6/boot.img")
    private val assets = UltraKernelFileAssets(File("src/main/assets"))
    private val credential = UltraKernelCredential.of("Aa1".repeat(16))
    private val workspaces = ArrayList<File>()

    @After fun cleanup() { workspaces.forEach { it.deleteRecursively() } }

    @Test fun everyShippedVariantKeepsPacAndReplaysTheNativeFrame() {
        val (kernel, symbols) = fixture()
        val entry = requireNotNull(symbols.offsetOf("do_execveat_common"))
        val variants = UltraKernelCatalog.load(assets).orderedForRelease(UltraKernelSpec.releaseOf(kernel))
        assertEquals(setOf("k21", "k22"), variants.map { it.id }.toSet())
        for (asset in variants) {
            val patched = UltraKernelCatalog.spec(assets, asset).apply(kernel).kernel
            assertEquals(asset.id, UltraKernelA64.PACIA1716, UltraKernelA64.le32Bits(patched, entry))
            val word = UltraKernelA64.le32Bits(patched, entry + 4)
            assertEquals(asset.id, 0x14000000, word and 0xfc000000.toInt())
            val save = entry + 4 + UltraKernelA64.signExtend((word and 0x03ffffff).toLong(), 26).toInt() * 4
            assertEquals(asset.id, UltraKernelA64.le32Bits(kernel, entry + 4),
                UltraKernelA64.le32Bits(patched, save - 8))
        }
    }

    @Test fun aMissingScsSymbolStillPreservesPac() {
        val (kernel, symbols) = fixture()
        val entry = requireNotNull(symbols.offsetOf("do_execveat_common"))
        val records = UltraKernelRecipe.k22().render(withoutScs(symbols), kernel, credential)
        assertEquals(entry + 4, records.single { it.role == "do_execveat_common+0x0" }.site)
        val frame = records.single { it.role == "arm64_notify_die+0x0" }
        assertEquals(UltraKernelA64.le32Bits(kernel, entry + 4),
            UltraKernelA64.le32Bits(frame.replacement, 20))
    }

    @Test fun aMissingScsSymbolStillRejectsPcRelativeReplay() {
        val (kernel, symbols) = fixture()
        UltraKernelA64.writeLe32(kernel, requireNotNull(symbols.offsetOf("do_execveat_common")) + 4,
            0x90000000L)
        val failure = assertThrows(UltraKernelGenerateException::class.java) {
            UltraKernelRecipe.k22().render(withoutScs(symbols), kernel, credential)
        }
        assertEquals("HOOK_FRAME_UNSUPPORTED:do_execveat_common", failure.detail)
    }

    @Test fun aStaticShadowPushAfterPacIsReplayedExactlyOnce() {
        val boot = File("../MapleSU_Reference/Boots/OnePlus11_5.15/boot.img")
        assumeTrue("static SCS fixture absent", boot.isFile)
        val kernel = UltraKernelPlan.readKernel(boot)
        val symbols = UltraKernelKallsymsTable.decode(kernel)
        val entry = requireNotNull(symbols.offsetOf("do_execveat_common"))
        assertEquals(0xF800865E.toInt(), UltraKernelA64.le32Bits(kernel, entry + 4))
        val plan = UltraKernelSpecGenerator().generate(kernel)
        val patched = plan.apply(kernel).kernel
        val frame = plan.records.single { it.role == "arm64_notify_die+0x0" }
        assertEquals(UltraKernelA64.PACIA1716, UltraKernelA64.le32Bits(patched, entry))
        assertEquals(0xF800865E.toInt(), UltraKernelA64.le32Bits(frame.replacement, 20))
        assertEquals(1, (0 until frame.length step 4).count {
            UltraKernelA64.le32Bits(frame.replacement, it) == 0xF800865E.toInt()
        })
    }

    @Test fun aSameReleaseDifferentBaseRegeneratesEvenWhenEveryTemplateByteMatches() {
        val (kernel, _) = fixture()
        kernel[kernel.lastIndex] = (kernel.last().toInt() xor 1).toByte()
        val asset = UltraKernelReleaseCatalog.load(assets).assetsFor(UltraKernelSpec.releaseOf(kernel)).first()
        val spec = loadReleaseSpec(assets, asset)
        assertTrue(spec.mismatched(kernel).isEmpty())
        assertNotEquals(asset.baseKernelSha256, UltraKernelSpec.sha256(kernel))
        val workspace = Files.createTempDirectory("ultrakernel-same-release-").toFile().also { workspaces += it }
        val changed = File(workspace, "different-base.img")
        val container = File(workspace, "kernel.bin")
        container.writeBytes(KernelCompression.encode(kernel, BootImageInspector.inspect(sample).compression))
        BootImageRebuilder.rebuildV4(sample, container, changed)
        val error = assertThrows(IllegalStateException::class.java) {
            UltraKernelBuilder(File(workspace, "direct")).generate(changed, asset, spec)
        }
        assertTrue(error.message.orEmpty().startsWith("ULTRA_KERNEL_TEMPLATE_BASE_MISMATCH"))
        val flow = UltraKernelFlow(File(workspace, "flow"), assets)
        val prepared = flow.prepare(changed)
        assertTrue(prepared.generated)
        assertFalse(prepared.alreadyInstalled)
        assertEquals(flow.generatePlan(changed).derived?.patchedKernelSha256,
            requireNotNull(prepared.candidate).kernelSha256)
    }

    private fun fixture(): Pair<ByteArray, UltraKernelKallsymsTable> {
        assumeTrue("6.6.89 sample absent", sample.isFile)
        val kernel = UltraKernelPlan.readKernel(sample)
        return kernel to UltraKernelKallsymsTable.decode(kernel)
    }

    private fun withoutScs(symbols: UltraKernelSymbols): UltraKernelSymbols = object : UltraKernelSymbols by symbols {
        override fun offsetOf(name: String): Int? =
            if (name == "__pi_scs_patch" || name == "scs_patch") null else symbols.offsetOf(name)
    }
}
