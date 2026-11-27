package dev.maplesu.manager.ultrakernel

import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraKernelKallsymsMemoryTest {
    @Test fun completeSymbolTableFits256MiBWithRetainedManagerLoad() {
        val paths = linkedSetOf<String>()
        paths.addAll(System.getProperty("java.class.path").orEmpty().split(File.pathSeparator))
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.urLs.filter { it.protocol == "file" }
                .forEach { paths += File(it.toURI()).path }
            loader = loader.parent
        }
        val root = File(System.getProperty("maplesu.repo", "..")).absoluteFile
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-Xms256m", "-Xmx256m", "-XX:+UseSerialGC", "-cp", paths.joinToString(File.pathSeparator),
            javaClass.name, File(root, "native-inputs/kernels/android15-6.6-346d51ea/Image").path,
        ).redirectErrorStream(true).start()
        try {
            assertTrue("symbol memory probe timed out", process.waitFor(60, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            assertEquals(output, 3, Regex("DECODE_OK").findAll(output).count())
            assertEquals(output, 3, Regex("RELEASE_OK").findAll(output).count())
        } finally { process.destroyForcibly() }
    }

    companion object {
        private fun usedHeap() = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

        private fun decode(image: File): Pair<WeakReference<ByteArray>, WeakReference<UltraKernelKallsymsTable>> {
            val raw = image.readBytes()
            val table = UltraKernelKallsymsTable.decode(raw)
            check(table.symbolCount == 106142 && table.offsetOf("_text") == 0)
            check(table.offsetsOf("put_cred") == listOf(4024336, 6470304, 14112676))
            println("DECODE_OK symbols=${table.symbolCount} heap=${usedHeap()}")
            return WeakReference(raw) to WeakReference(table)
        }

        @JvmStatic fun main(args: Array<String>) {
            val pressure = Array(176) { ByteArray(1024 * 1024).also { it[0] = 1 } }
            repeat(3) { iteration ->
                System.gc()
                val before = usedHeap()
                val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
                pools.forEach { it.resetPeakUsage() }
                val (raw, symbols) = decode(File(args.single()))
                val peak = pools.sumOf { it.peakUsage.used }
                System.gc()
                check(raw.get() == null && symbols.get() == null)
                val after = usedHeap()
                check(after <= before + 8 * 1024 * 1024)
                println("RELEASE_OK iteration=${iteration + 1} before=$before after=$after poolPeaks=$peak")
            }
            Reference.reachabilityFence(pressure)
        }
    }
}
