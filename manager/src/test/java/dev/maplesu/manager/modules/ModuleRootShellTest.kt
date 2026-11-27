package dev.maplesu.manager.modules

import dev.maplesu.manager.runtime.RootBackend
import dev.maplesu.manager.runtime.RootExec
import dev.maplesu.manager.runtime.RootExecResult
import dev.maplesu.manager.runtime.RootExecutionRouter
import dev.maplesu.manager.runtime.RootExecutor
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.SwitchOutcome
import dev.maplesu.manager.workmode.superuser.LiteImplantQuiesceGate
import dev.maplesu.manager.workmode.ui.WorkModeSwitchSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模块 root 通道：fake router 下脚本执行/错误码/输出捕获（替代 libsu su 后的 ≥8 例）。 */
class ModuleRootShellTest {
    private class FakeRunner(var result: ModuleScriptOutput = ModuleScriptOutput(0, "ok")) :
        ModuleScriptRunner {
        val scripts = mutableListOf<String>()
        override fun run(script: String): ModuleScriptOutput {
            scripts += script
            return result
        }
    }

    private class FakeRoot(
        var available: Boolean = true,
        var result: RootExecResult = RootExecResult(RootBackend.ULTRA_ENV, 0, "routed-out"),
    ) : RootExecutor {
        override val backend: RootBackend = RootBackend.ULTRA_ENV
        var scripts = mutableListOf<String>()
        override fun available(): Boolean = available
        override fun run(script: String): RootExecResult {
            scripts += script
            return result
        }
    }

    @After
    fun tearDown() {
        RootExec.resetForTest()
        LiteImplantQuiesceGate.resetForTest()
        WorkModeSwitchSession.finish(SwitchOutcome.Blocked("", "", emptyList()))
    }

    private fun moduleRootQuiescing(): Boolean {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val runner = unsafe.allocateInstance(ManagementModuleScriptRunner::class.java)
        return ManagementModuleScriptRunner::class.java.getDeclaredMethod("isQuiescing")
            .apply { isAccessible = true }.invoke(runner) as Boolean
    }

    @Test
    fun pendingRebootDoesNotBlockModuleRootOperations() {
        WorkModeSwitchSession.finish(SwitchOutcome.AwaitingReboot("pending-reboot", null))
        assertTrue(WorkModeSwitchSession.pending)
        assertFalse(moduleRootQuiescing())
    }

    @Test
    fun executingModeSwitchStillPausesModuleRootOperations() {
        WorkModeSwitchSession.begin()
        assertTrue(moduleRootQuiescing())
    }

    @Test
    fun activeWriterQuiescenceStillPausesModuleRootOperations() {
        WorkModeSwitchSession.finish(SwitchOutcome.Blocked("", "", emptyList()))
        LiteImplantQuiesceGate.tryEnter()
        assertTrue(moduleRootQuiescing())
    }

    @Test
    fun successfulJobCapturesOutputLines() {
        val runner = FakeRunner(ModuleScriptOutput(0, "line1\nline2"))
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = ModuleRootShell(runner).newJob().add("echo hi").to(out, err).exec()
        assertTrue(result.isSuccess)
        assertEquals(0, result.code)
        assertEquals(listOf("line1", "line2"), result.out)
        assertEquals(listOf("line1", "line2"), out)
        assertTrue(err.isEmpty())
    }

    @Test
    fun failurePreservesExitCodeAndMapsOutputIntoErrWhenNoStderr() {
        val runner = FakeRunner(ModuleScriptOutput(11, "syntax error"))
        val result = ModuleRootShell(runner).newJob().add("bad").to(ArrayList(), ArrayList()).exec()
        assertFalse(result.isSuccess)
        assertEquals(11, result.code)
        assertEquals(listOf("syntax error"), result.err)
    }

    @Test
    fun explicitStderrIsCapturedSeparately() {
        val runner = FakeRunner(ModuleScriptOutput(1, "out", "err"))
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = ModuleRootShell(runner).newJob().add("x").to(out, err).exec()
        assertEquals(listOf("out"), result.out)
        assertEquals(listOf("err"), err)
        assertEquals(listOf("out"), out)
        assertEquals(listOf("err"), err)
    }

    @Test
    fun addedCommandsAreJoinedAndRunInOneScript() {
        val runner = FakeRunner(ModuleScriptOutput(0, ""))
        ModuleRootShell(runner).newJob().add("cmd1").add("cmd2").exec()
        assertEquals(1, runner.scripts.size)
        assertEquals("cmd1\ncmd2", runner.scripts.single())
    }

    @Test
    fun emptyJobIsHarmlessSuccess() {
        val runner = FakeRunner()
        val result = ModuleRootShell(runner).newJob().exec()
        assertTrue(result.isSuccess)
        assertEquals(0, result.code)
        assertTrue(runner.scripts.isEmpty())
    }

    @Test
    fun shellCloseIsIdempotent() {
        val shell = ModuleRootShell(FakeRunner())
        shell.close()
        shell.close()
        assertTrue(shell.newJob().add("echo ok").exec().isSuccess)
    }

    @Test
    fun rootExecRunnerUsesRouterAndSucceeds() {
        val root = FakeRoot()
        RootExec.installForTest(RootExecutionRouter(ultraEnv = root, modeProvider = { WorkMode.LITE }))
        val result = RootExecModuleScriptRunner.run("echo routed")
        assertTrue(result.isSuccess)
        assertEquals("routed-out", result.out)
        assertEquals("echo routed", root.scripts.single())
    }

    @Test
    fun successfulRootCommandsRetainTheirStderr() {
        for (backend in listOf(RootBackend.KSU, RootBackend.HOOK, RootBackend.ULTRA_ENV)) {
            val root = object : RootExecutor {
                override val backend = backend
                override fun available() = true
                override fun run(script: String) = RootExecResult(backend, 0, "stdout", "stderr warning")
            }
            RootExec.installForTest(RootExecutionRouter(
                ultraEnv = root.takeIf { backend == RootBackend.ULTRA_ENV },
                modeProvider = { if (backend == RootBackend.KSU) WorkMode.KSU else WorkMode.LITE },
                ksuProvider = { root.takeIf { backend == RootBackend.KSU } },
                hook = root.takeIf { backend == RootBackend.HOOK },
            ))
            val result = RootExecModuleScriptRunner.run("module-command")
            assertEquals(0, result.code)
            assertEquals("stdout", result.out)
            assertEquals("stderr warning", result.err)
        }
    }

    @Test
    fun moduleOutputPreservesBlankLinesAndIndependentStreams() {
        val runner = FakeRunner(ModuleScriptOutput(0, "first\n\nthird\n\n", "\nwarning\n"))
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = ModuleRootShell(runner).newJob().add("module-command").to(out, err).exec()
        assertEquals(listOf("first", "", "third", ""), result.out)
        assertEquals(listOf("", "warning"), result.err)
        assertEquals(result.out, out)
        assertEquals(result.err, err)
        runner.result = ModuleScriptOutput(0, "\n", "last\r\n")
        val blank = ModuleRootShell(runner).newJob().add("module-command").exec()
        assertEquals(listOf(""), blank.out)
        assertEquals(listOf("last"), blank.err)
    }

    @Test
    fun rootExecRunnerFailsClosedWhenBackendUnavailable() {
        RootExec.resetForTest()
        val result = RootExecModuleScriptRunner.run("echo routed")
        assertFalse(result.isSuccess)
        assertTrue(result.code != 0)
        assertTrue(result.err.isNotEmpty())
    }

    @Test
    fun routerFailureIsNotTreatedAsScriptSuccess() {
        val root = FakeRoot(
            available = true,
            result = RootExecResult(RootBackend.ULTRA_ENV, 9, "denied", "ULTRA_ROOT_FAILED"),
        )
        RootExec.installForTest(RootExecutionRouter(ultraEnv = root, modeProvider = { WorkMode.LITE }))
        val result = RootExecModuleScriptRunner.run("id")
        assertFalse(result.isSuccess)
        assertEquals(9, result.code)
        assertTrue(result.err.contains("ULTRA_ROOT_FAILED"))
    }

    @Test
    fun channelSyntaxCheckAndCommandShapes() {
        val runner = FakeRunner(ModuleScriptOutput(0, "", ""))
        val channel = LiteScriptModuleRootChannel(runner)
        channel.syntaxCheck("/data/app/staging/service.sh")
        channel.runCommand("chmod 0755 /data/adb/modules/x/service.sh")
        assertEquals(2, runner.scripts.size)
        assertTrue(runner.scripts[0].startsWith("/system/bin/sh -n '"))
        assertTrue(runner.scripts[1].contains("chmod 0755"))
    }

    @Test
    fun installStyleSuccessAndFailureFlow() {
        val runner = FakeRunner(ModuleScriptOutput(0, "installed"))
        val shell = ModuleRootShell(runner)
        assertTrue(shell.newJob().add("install").exec().isSuccess)
        runner.result = ModuleScriptOutput(1, "", "script failed")
        val failure = shell.newJob().add("install").exec()
        assertFalse(failure.isSuccess)
        assertEquals(1, failure.code)
        assertTrue(failure.err.contains("script failed"))
    }
}
