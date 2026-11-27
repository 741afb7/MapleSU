package dev.maplesu.manager.installer

import dev.maplesu.installer.BlockDeviceTransaction
import dev.maplesu.manager.ultrakernel.UltraKernelFileAssets
import dev.maplesu.manager.ultrakernel.UltraKernelFlow
import dev.maplesu.manager.ultrakernel.UltraKernelSpec
import dev.maplesu.manager.ultrakernel.UltraKernelPlan
import dev.maplesu.manager.ultrakernel.UltraKernelVivoFilter
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real image generation and readback, using ordinary files in place of physical partitions. */
@RunWith(Parameterized::class)
class InstallerKernelRoundTripTest(private val directory: String, private val vivoVrFilter: Boolean) {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun generatedImageWritesRestoresAndRecoversFromAPartialWrite() {
        val source = File("../MapleSU_Reference/Boots/$directory/boot.img")
        assumeTrue("real boot fixture absent: $directory", source.isFile)
        val originalHash = UltraKernelSpec.sha256(source)
        val flow = UltraKernelFlow(File(temporary.root, "patch"), UltraKernelFileAssets(File("src/main/assets")), vivoVrFilter)
        val preparation = flow.prepare(source)
        val candidate = flow.candidateForInstall(source, preparation)
        assertEquals(candidate.bootSha256, UltraKernelSpec.sha256(candidate.image))
        if (vivoVrFilter) assertTrue("the image retains its early vr filter",
            UltraKernelVivoFilter.present(UltraKernelPlan.readKernel(candidate.image)))
        val partition = source.copyTo(File(temporary.root, "partition.img"))
        val pre = source.copyTo(File(temporary.root, "pre.img"))
        val states = mutableListOf<String>()
        val journal = BlockDeviceTransaction.Journal { state, _ -> states += state }
        FilePartition(partition).use { device ->
            val installed = BlockDeviceTransaction.commit(device, candidate.image.toPath(),
                candidate.bootSha256, pre.toPath(), originalHash, journal)
            assertEquals("REBOOT_REQUIRED", installed.state)
            assertEquals(BlockDeviceTransaction.paddedDigest(candidate.image.toPath(), device.size()),
                BlockDeviceTransaction.digest(device))
            assertTrue("the flashed image remains recognizable without a supplied credential",
                flow.prepare(partition).alreadyInstalled)
            assertEquals("RESTORED", BlockDeviceTransaction.restore(device, pre.toPath(), originalHash, journal).state)
            assertEquals(originalHash, UltraKernelSpec.sha256(partition))

            device.failNextWrite = true
            val interrupted = BlockDeviceTransaction.commit(device, candidate.image.toPath(),
                candidate.bootSha256, pre.toPath(), originalHash, journal)
            assertEquals("RESTORED", interrupted.state)
            assertEquals("INSTALLER_DEVICE_WRITE_FAILED", interrupted.error)
            assertEquals(originalHash, UltraKernelSpec.sha256(partition))
            assertTrue(states.contains("RECOVERY_REQUIRED"))
        }
        assertEquals("the original input is read-only", originalHash, UltraKernelSpec.sha256(source))
    }

    private class FilePartition(file: File) : BlockDeviceTransaction.Device {
        private val data = RandomAccessFile(file, "rw")
        private val size = data.length()
        var failNextWrite = false
        override fun size() = size
        override fun revalidate() { check(data.length() == size) }
        override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
            data.seek(offset)
            return data.read(buffer, 0, length)
        }
        override fun write(offset: Long, buffer: ByteArray, start: Int, length: Int): Int {
            data.seek(offset)
            if (failNextWrite) {
                failNextWrite = false
                val originalByte = data.readUnsignedByte()
                data.seek(offset)
                data.write(originalByte xor 0xff)
                data.fd.sync()
                data.seek(offset)
                check(data.readUnsignedByte() != originalByte)
                throw IOException("INSTALLER_DEVICE_WRITE_FAILED")
            }
            data.write(buffer, start, length)
            return length
        }
        override fun sync() = data.fd.sync()
        override fun close() = data.close()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0},vivo={1}")
        fun samples(): Collection<Array<Any>> = listOf(
            "OnePlus10_Pro_5.10", "OnePlus11_5.15", "OnePlus12_6.1",
            "OnePlusAce6_6.6", "OnePlus15_6.12", "OnePlus16_6.18",
        ).flatMap { sample -> listOf(arrayOf<Any>(sample, false), arrayOf<Any>(sample, true)) }
    }
}
