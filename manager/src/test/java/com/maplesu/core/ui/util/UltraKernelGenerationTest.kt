package com.maplesu.core.ui.util

import com.maplesu.core.ui.screen.install.UltraKernelGenerationPort
import dev.maplesu.manager.ultrakernel.UltraKernelGenerateCodes
import dev.maplesu.manager.ultrakernel.UltraKernelGenerateException
import dev.maplesu.manager.ultrakernel.UltraKernelPlan
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercise the production keyless generation port with real plan and boot-container invariants. */
class UltraKernelGenerationTest {
    private val release = "6.1.25-android14-11-o-g8b7f8f0a5e4d-ab1234567"

    private fun fixturePlan(kernelRelease: String = release): UltraKernelPlan {
        val original = ByteArray(16) { 0x2A }
        val replacement = ByteArray(16) { 0x45 }
        val offset = 0x200
        val kernel = ByteArray(offset + original.size + 16)
        "Linux version $kernelRelease\n".toByteArray(Charsets.US_ASCII).copyInto(kernel, 0x40)
        original.copyInto(kernel, offset)
        val plan = UltraKernelPlan.fromRecords(
            kernelRelease = kernelRelease,
            records = listOf(UltraKernelPlan.PatchRecord("die+0x0", offset.toLong(), original, replacement)),
            origin = UltraKernelPlan.Origin.GENERATED,
            evidence = UltraKernelPlan.SiteEvidence(18, 18, emptyList(), emptyList()),
        )
        assertNull(plan.slots)
        return plan.withDerived(plan.apply(kernel).derived)
    }

    /** Minimal valid Android boot v3, or init_boot with no kernel. */
    private fun withBootFile(hasKernel: Boolean = true, hasRamdisk: Boolean = false, block: (File) -> Unit) {
        val file = File.createTempFile("ultra-kernel-boot-", ".img")
        try {
            val bytes = ByteArray(4096 + (if (hasKernel) 4096 else 0) + (if (hasRamdisk) 4096 else 0))
            "ANDROID!".toByteArray(Charsets.US_ASCII).copyInto(bytes)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(8, if (hasKernel) 64 else 0)
                putInt(12, if (hasRamdisk) 64 else 0)
                putInt(20, 1580)
                putInt(40, 3)
            }
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }

    @Test fun aSelectedImageRendersWithoutPairingAndReportsTheDerivedDigest() {
        val plan = fixturePlan()
        var rendered = 0
        var seenBoot: File? = null
        val port = UltraKernelSourcedGeneration(render = { boot ->
            rendered++
            seenBoot = boot
            plan
        })
        withBootFile { boot ->
            val answer = port.plan(boot, release) as UltraKernelGenerationPort.GenerationPlan.Available
            assertEquals(plan.derived?.patchedKernelSha256, answer.specSha256)
            assertEquals(1, answer.patches)
            assertFalse(answer.deferred)
            assertSame(boot, seenBoot)
        }
        assertEquals(1, rendered)
    }

    @Test fun selected61And510ImagesRenderImmediatelyWithoutDeviceDetection() {
        for (imageRelease in listOf(release, "5.10.226-android12-9-o-gb4f7990ee2d5")) {
            var rendered = 0
            val plan = fixturePlan(imageRelease)
            val port = UltraKernelSourcedGeneration(render = { rendered++; plan })
            withBootFile { boot ->
                repeat(2) {
                    val answer = port.plan(boot, imageRelease) as UltraKernelGenerationPort.GenerationPlan.Available
                    assertFalse(answer.deferred)
                    assertEquals(plan.derived?.patchedKernelSha256, answer.specSha256)
                }
            }
            assertEquals(2, rendered)
        }
    }

    @Test fun modernKernelPreviewsRenderWithAndWithoutRamdisk() {
        for (imageRelease in listOf("6.1.25-android14-11-fixture", "6.6.1-android15-1-fixture",
            "6.12.1-android16-1-fixture", "6.18.1-android17-1-fixture", "6.6.127-custom")) {
            var rendered = 0
            val port = UltraKernelSourcedGeneration(render = { rendered++; fixturePlan(imageRelease) })
            for (hasRamdisk in listOf(false, true)) withBootFile(hasRamdisk = hasRamdisk) { boot ->
                val answer = port.plan(boot, imageRelease) as UltraKernelGenerationPort.GenerationPlan.Available
                assertFalse(answer.deferred)
                assertEquals(1, answer.patches)
            }
            assertEquals(2, rendered)
        }
    }

    @Test fun aSiteCoverageFailureIsReportedWithItsOwnCode() {
        val port = UltraKernelSourcedGeneration(render = {
            throw UltraKernelGenerateException(
                UltraKernelGenerateCodes.SITE_COVERAGE,
                "3/18 differing=die+0x0,free_initmem+0x0",
            )
        })
        withBootFile { boot ->
            val answer = port.plan(boot, release) as UltraKernelGenerationPort.GenerationPlan.Unavailable
            assertEquals(UltraKernelGenerateCodes.SITE_COVERAGE, answer.code)
            assertEquals("3/18 differing=die+0x0,free_initmem+0x0", answer.detail)
        }
    }

    @Test fun anUnexpectedRendererFailureStillCarriesAStableCode() {
        val port = UltraKernelSourcedGeneration(render = {
            throw IllegalStateException("ULTRA_KERNEL_GENERATE_KALLSYMS:CANDIDATES:2")
        })
        withBootFile { boot ->
            val answer = port.plan(boot, release) as UltraKernelGenerationPort.GenerationPlan.Unavailable
            assertEquals("ULTRA_KERNEL_GENERATE_KALLSYMS", answer.code)
        }
    }

    @Test fun anActualLegacyModuleRouteDefersWithoutRunningTheRawRenderer() {
        var rendered = 0
        var inspected: File? = null
        val port = UltraKernelSourcedGeneration(
            render = { rendered++; error("component uses its own builder") },
            legacyModuleRequired = { inspected = it; true },
        )
        withBootFile { boot ->
            val answer = port.plan(boot, release) as UltraKernelGenerationPort.GenerationPlan.Available
            assertTrue(answer.deferred)
            assertEquals(0, answer.patches)
            assertSame(boot, inspected)
        }
        assertEquals(0, rendered)
    }

    @Test fun absentCurrentPartitionImagesRemainDeferred() {
        var rendered = 0
        val port = UltraKernelSourcedGeneration(render = { rendered++; error("missing image") })
        for (image in listOf(null, File("does-not-exist.img"))) {
            val answer = port.plan(image, release) as UltraKernelGenerationPort.GenerationPlan.Available
            assertTrue(answer.deferred)
            assertEquals(0, answer.patches)
            assertEquals("", answer.specSha256)
        }
        assertEquals(0, rendered)
    }

    @Test fun initBootWithoutKernelDefersUntilThePairedBootIsAvailable() {
        var rendered = 0
        val port = UltraKernelSourcedGeneration(render = { rendered++; error("no kernel") })
        withBootFile(hasKernel = false) { boot ->
            val answer = port.plan(boot, release) as UltraKernelGenerationPort.GenerationPlan.Available
            assertTrue(answer.deferred)
            assertEquals(0, answer.patches)
        }
        assertEquals(0, rendered)
    }

    @Test fun aMalformedContainerFailsBeforeTheRendererRuns() {
        var rendered = 0
        val port = UltraKernelSourcedGeneration(render = { rendered++; error("invalid image") })
        withBootFile { boot ->
            boot.writeBytes(ByteArray(32))
            val answer = port.plan(boot, release) as UltraKernelGenerationPort.GenerationPlan.Unavailable
            assertEquals("ULTRA_KERNEL_UNKNOWN", answer.code)
            assertEquals("TRUNCATED_HEADER", answer.detail)
        }
        assertEquals(0, rendered)
    }
}
