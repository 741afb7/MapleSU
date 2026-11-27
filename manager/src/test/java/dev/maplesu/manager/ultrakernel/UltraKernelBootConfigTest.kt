package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class UltraKernelBootConfigTest {
    private fun config(line: String): Map<String, String> {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(line.toByteArray()) }
        return UltraKernelResidentAbi.targetConfig("IKCFG_ST".toByteArray() + output.toByteArray() + "IKCFG_ED".toByteArray())
    }
    @Test fun `boot callback reads enabled kprobes from actual target config`() {
        assertEquals("y", config("CONFIG_KPROBES=y\n")["CONFIG_KPROBES"])
    }
    @Test fun `disabled kprobes remains disabled`() {
        assertEquals("n", config("# CONFIG_KPROBES is not set\n")["CONFIG_KPROBES"])
    }
    @Test fun `undefined kprobes follows complete config disabled semantics`() {
        assertEquals("n", config("CONFIG_SMP=y\n")["CONFIG_KPROBES"])
    }
}
