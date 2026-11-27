package dev.maplesu.manager.ultrakernel

import dev.maplesu.image.UltraInitBootProducer
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Uses the same container byte fixtures; no module/shim executes and no runtime support is asserted. */
class UltraInitBootReceiptTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completeReceiptIsPublishedOnlyAfterCandidateReadback() {
        val (source, artifact) = fixture()
        val before = source.readBytes()
        val bridgeBefore = artifact.bridgeBytes()
        val work = temporary.newFolder("complete")
        val built = UltraKernelBuilder(work).generateInitBoot(source, artifact)
        val receipt = File(work, built.image.nameWithoutExtension + ".json")
        assertTrue(receipt.isFile)
        assertTrue(receipt.readText().contains("\"runtimeVerified\":false"))
        assertTrue(receipt.readText().contains(built.bootSha256))
        assertTrue(work.listFiles().orEmpty().none { it.name.endsWith(".pending") })
        assertArrayEquals(before, source.readBytes())
        assertArrayEquals(bridgeBefore, artifact.bridgeBytes())
    }

    @Test fun partialWriteAndCloseFailureRemoveCandidateAndPartialReceipt() {
        for (reason in listOf("partial-write", "close-failure")) {
            val (source, artifact) = fixture()
            val before = source.readBytes()
            val work = temporary.newFolder(reason)
            val primary = IOException(reason)
            val builder = UltraKernelBuilder(work) { file, bytes ->
                file.outputStream().use { stream ->
                    stream.write(bytes, 0, if (reason == "partial-write") 9 else bytes.size)
                }
                throw primary
            }
            val failure = runCatching { builder.generateInitBoot(source, artifact) }.exceptionOrNull()
            assertSame(primary, failure)
            assertTrue(work.listFiles().orEmpty().isEmpty())
            assertArrayEquals(before, source.readBytes())
        }
    }

    @Test fun cleanupFailureIsSuppressedOnTheOriginalReceiptError() {
        val (source, artifact) = fixture()
        val work = temporary.newFolder("cleanup")
        val primary = IOException("receipt-close")
        val builder = UltraKernelBuilder(work) { file, bytes ->
            file.writeBytes(bytes.copyOf(9))
            val output = work.listFiles()!!.single { it.extension == "img" }
            check(output.delete() && output.mkdir())
            File(output, "busy").writeText("force nonempty-directory cleanup failure")
            throw primary
        }
        val failure = runCatching { builder.generateInitBoot(source, artifact) }.exceptionOrNull()
        assertSame(primary, failure)
        assertTrue(primary.suppressed.isNotEmpty())
        assertTrue(work.listFiles().orEmpty().none { it.extension == "json" || it.name.endsWith(".pending") })
    }

    @Test fun cancellationAfterReceiptWriteDoesNotReturnAnInstallableCandidate() {
        val (source, artifact) = fixture()
        val sourceBefore = source.readBytes()
        val bridgeBefore = artifact.bridgeBytes()
        val work = temporary.newFolder("cancel-receipt")
        val builder = UltraKernelBuilder(work) { file, bytes ->
            file.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
            Thread.currentThread().interrupt()
        }
        val failure = try {
            runCatching { builder.generateInitBoot(source, artifact) }.exceptionOrNull().also {
                assertTrue(Thread.currentThread().isInterrupted)
            }
        } finally { Thread.interrupted() }
        assertTrue(failure is InterruptedIOException)
        assertEquals("ULTRA_KERNEL_INIT_BOOT_CANCELLED", failure?.message)
        assertTrue(work.listFiles().orEmpty().isEmpty())
        assertArrayEquals(sourceBefore, source.readBytes())
        assertArrayEquals(bridgeBefore, artifact.bridgeBytes())
    }

    @Suppress("UNCHECKED_CAST")
    private fun fixture(): Pair<File, UltraInitBootProducer.Artifact> {
        val helpers = Class.forName("dev.maplesu.image.UltraInitBootProducerTest")
        val files = helpers.getDeclaredMethod("kmiBundle").apply { isAccessible = true }
            .invoke(null) as Map<String, ByteArray>
        val directory = temporary.newFolder()
        files.forEach { (path, bytes) -> File(directory, path).writeBytes(bytes) }
        val ramdisk = helpers.getDeclaredMethod("stock", java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            .apply { isAccessible = true }.invoke(null, false, false) as ByteArray
        val image = helpers.getDeclaredMethod("image", ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(null, ByteArray(0), ramdisk) as ByteArray
        val source = File(directory, "init_boot.img").also { it.writeBytes(image) }
        val artifact = UltraInitBootProducer.readArtifact(File(directory, "manifest.json"))
            .withDeploymentNamespace(UltraKernelDeploymentNamespace.create().record())
        return source to artifact
    }
}
