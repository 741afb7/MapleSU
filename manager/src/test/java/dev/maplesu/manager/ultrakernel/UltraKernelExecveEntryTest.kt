package dev.maplesu.manager.ultrakernel

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UltraKernelExecveEntryTest {
    @Test fun `both captured native wrappers prove the legacy entry`() {
        val (raw, symbols) = fixture()
        val resolved = UltraKernelExecveEntry.resolve(symbols, raw)
        assertEquals(512, resolved.offsetOf("do_execveat_common"))
        assertEquals(listOf(512), resolved.offsetsOf("do_execveat_common"))
        assertEquals(128, resolved.offsetOf("do_execve"))
    }

    @Test fun `a present common entry takes priority over the legacy route`() {
        val (raw, _) = fixture()
        val symbols = FakeSymbols(names + ("do_execveat_common" to 768), raw.size)
        assertSame(symbols, UltraKernelExecveEntry.resolve(symbols, raw))
    }

    @Test fun `every changed argument frame call and epilogue instruction rejects the route`() {
        val (raw, symbols) = fixture()
        for ((start, count) in listOf(128 to 15, 256 to 13)) {
            repeat(count) { index ->
                val changed = raw.copyOf()
                UltraKernelA64.writeLe32(changed, start + index * 4, UltraKernelA64.NOP.toLong())
                assertSame("wrapper=$start word=$index", symbols, UltraKernelExecveEntry.resolve(symbols, changed))
            }
        }
    }

    @Test fun `missing duplicate or truncated wrappers never establish the ABI`() {
        val (raw, _) = fixture()
        for (name in names.keys) {
            val missing = FakeSymbols(names - name, raw.size)
            assertSame(missing, UltraKernelExecveEntry.resolve(missing, raw))
        }
        val duplicate = object : UltraKernelSymbols by FakeSymbols(names, raw.size) {
            override fun offsetsOf(name: String) = if (name == "do_execve") listOf(128, 384)
                else listOfNotNull(offsetOf(name))
        }
        assertSame(duplicate, UltraKernelExecveEntry.resolve(duplicate, raw))
        val truncated = FakeSymbols(names + ("next" to 140), raw.size)
        assertSame(truncated, UltraKernelExecveEntry.resolve(truncated, raw))
    }

    @Test fun `real NGKI generation keeps its stack allocation and replays SCS once`() {
        val root = File(System.getProperty("maplesu.repo", ".."))
        val raw = UltraKernelPlan.readKernel(File(root, "Boot_Research/NGKI/boot.img"))
        val symbols = UltraKernelKallsymsTable.decode(raw)
        val entry = requireNotNull(symbols.offsetOf("__do_execve_file"))
        val plan = UltraKernelSpecGenerator().generate(raw)
        val hook = plan.records.single { it.role == "do_execveat_common+0x0" }
        val frame = plan.records.single { it.role == "arm64_notify_die+0x0" }
        val guard = plan.records.single { it.role == "execve-null-guard" }
        val patched = plan.apply(raw)
        assertEquals(entry + 4L, hook.offset)
        assertEquals(0xD10203FF.toInt(), UltraKernelA64.le32Bits(patched.kernel, entry))
        assertEquals(0xF800865E.toInt(), UltraKernelA64.le32Bits(frame.replacement, 20))
        assertEquals(frame.offset + 52, guard.offset)
        assertEquals(16, guard.length)
        val branch = UltraKernelA64.le32Bits(hook.replacement, 0)
        assertEquals(guard.offset,
            hook.offset + UltraKernelA64.signExtend((branch and 0x03FFFFFF).toLong(), 26) * 4)
        assertEquals(0, patched.derived.outsideSites)
        val filldir = requireNotNull(symbols.offsetOf("filldir64"))
        for ((role, at) in listOf("free_initmem+0x0" to 52, "__traceiter_kvm_userspace_exit+0x70" to 48)) {
            val data = plan.records.single { it.role == role }
            assertEquals(filldir, UltraKernelA64.le32Bits(data.replacement, at))
        }
    }

    private fun fixture(): Pair<ByteArray, UltraKernelSymbols> {
        val raw = ByteArray(1024)
        for ((start, hex, call) in listOf(
            Triple(128, "5e8600f8fd7bbfa9fd030091e50302aae30301aae10300aa600c8012e2031faae4031faae6031f2ae7031faa37feff97fd7bc1a85e8e5ff8c0035fd6", 44),
            Triple(256, "5e8600f8fd7bbfa9fd030091e603042ae50303aae30302aae2031faae4031faae7031faa2afeff97fd7bc1a85e8e5ff8c0035fd6", 36),
        )) {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(raw, start)
            UltraKernelA64.writeLe32(raw, start + call,
                UltraKernelA64.encodeBl((start + call).toLong(), 512).toLong())
        }
        return raw to FakeSymbols(names, raw.size)
    }

    companion object {
        private val names = mapOf("do_execve" to 128, "do_execveat" to 256, "__do_execve_file" to 512)
    }
}
