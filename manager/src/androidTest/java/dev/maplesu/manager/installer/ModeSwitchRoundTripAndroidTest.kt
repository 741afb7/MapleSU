package dev.maplesu.manager.installer

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.maplesu.core.ui.MainActivity
import dev.maplesu.manager.workmode.ModeRuntime
import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.service.ModeSwitchRootSession
import dev.maplesu.manager.workmode.service.ModeSwitchRuntime
import dev.maplesu.manager.workmode.service.SwitchOutcome
import dev.maplesu.manager.modules.UltraRootKeyStore
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentUninstaller
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentInstaller
import dev.maplesu.manager.runtime.ultra.UltraEnvironmentProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in: drives the production switch, with reboot controlled by the host. */
class ModeSwitchRoundTripAndroidTest {
    @Test(timeout = 600_000)
    fun currentDeviceSwitch() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("modeSwitchDevice") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun report(stage: String, value: String) = instrumentation.sendStatus(2, Bundle().apply {
            putString("stage", stage)
            putString("value", value)
        })
        ActivityScenario.launch(MainActivity::class.java).use {
            when (arguments.getString("action")) {
                "temporary-uninstall" -> {
                    val outcome = UltraEnvironmentUninstaller.uninstall(context, permanent = false)
                    report("temporary-uninstall", outcome.toString())
                    assertTrue(outcome.ok)
                    assertEquals("temporary", context.getSharedPreferences("maplesu-ultra", 0)
                        .getString("environmentRemoval", null))
                    assertTrue(UltraRootKeyStore.isSuspended(context))
                    return@use
                }
                "environment-removed" -> {
                    assertEquals("temporary", context.getSharedPreferences("maplesu-ultra", 0)
                        .getString("environmentRemoval", null))
                    assertTrue("automatic reinstall not inhibited", UltraRootKeyStore.isSuspended(context))
                    val state = UltraEnvironmentProbe.refresh(context)
                    report("environment", state.name)
                    assertEquals(UltraEnvironmentProbe.State.NotInstalled, state)
                    return@use
                }
                "install-environment" -> {
                    UltraRootKeyStore.allowEnvironmentInstall(context)
                    val outcome = UltraEnvironmentInstaller.install(context)
                    report("install-environment", outcome.summary)
                    assertTrue(outcome.ok)
                    return@use
                }
            }
            if (arguments.getString("action") == "sdk-inspect") {
                val client = UltraRuntimeClient()
                val receipt = client.runRootCommand("""
                    id
                    cat /proc/sys/kernel/random/boot_id
                    slot=${'$'}(getprop ro.boot.slot_suffix)
                    sha256sum /dev/block/by-name/boot${'$'}slot /dev/block/by-name/init_boot${'$'}slot
                    for f in /data/adb/maplesu-workmode/tx/*/state.json; do
                      [ ! -f "${'$'}f" ] || { echo STATE; cat "${'$'}f"; echo; }
                    done
                    for d in /data/local/maplesu-recovery/environment-archives/*; do
                      [ ! -d "${'$'}d" ] || ls -l "${'$'}d"
                    done
                """.trimIndent())
                report("sdk-root", "ok=${receipt.succeeded} error=${receipt.sdkError} " +
                    client.decodeText(receipt.string("result")).orEmpty())
                assertTrue("SDK root failed", receipt.succeeded)
                return@use
            }
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (arguments.getString("action") !in setOf("reboot", "root-command") && ModeRuntime.active == null &&
                SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250)
            }
            report("mode", ModeRuntime.active?.name.orEmpty())
            val session = ModeSwitchRootSession(context)
            val ready = session.ensureReady()
            assertTrue("root session: ${ready.code}", ready.ok)
            when (arguments.getString("action", "inspect")) {
                "root-command" -> {
                    val result = session.execute(requireNotNull(arguments.getString("script")))
                    report("root-command", "exit=${result.code} ${result.detail}\n${result.output}")
                    assertTrue(result.ok)
                }
                "inspect" -> {
                    val result = session.execute("""
                        id
                        cat /proc/sys/kernel/random/boot_id
                        slot=${'$'}(getprop ro.boot.slot_suffix)
                        sha256sum /dev/block/by-name/boot${'$'}slot /dev/block/by-name/init_boot${'$'}slot
                        for f in /data/adb/maplesu/installer/*/record.json; do
                          [ ! -f "${'$'}f" ] || { stat -c 'RECORD %Y %n' "${'$'}f"; cat "${'$'}f"; echo; }
                        done
                        for f in /data/adb/maplesu/installer/mode-switch-lease.properties /data/local/maplesu-recovery/mode-switch-lease.properties /data/adb/maplesu-workmode/switch.json; do
                          [ ! -f "${'$'}f" ] || { echo "CONTROL ${'$'}f"; cat "${'$'}f"; echo; }
                        done
                    """.trimIndent())
                    report("persistent-state", result.output)
                    assertTrue("inspection: ${result.code} ${result.detail}", result.ok)
                }
                "start" -> {
                    val target = requireNotNull(WorkMode.fromWire(arguments.getString("target").orEmpty()))
                    val facade = requireNotNull(ModeSwitchRuntime.facadeOrNull())
                    val outcome = runBlocking(Dispatchers.IO) {
                        facade.startSwitch(target, onProgress = { report("progress", it) })
                    }
                    report("outcome", outcome.toString())
                    assertTrue("switch failed: $outcome", outcome is SwitchOutcome.AwaitingReboot)
                    outcome as SwitchOutcome.AwaitingReboot
                    assertFalse("operation still active", facade.operationActive)
                    assertEquals("REBOOT_REQUIRED", outcome.status?.state)
                    assertTrue(outcome.status?.rebootRequired == true)
                    File(context.filesDir, "mode-switch-device-task.txt").writeText(outcome.switchId)
                    report("switchId", outcome.switchId)
                }
                "verify" -> {
                    val taskId = arguments.getString("switchId")
                        ?: File(context.filesDir, "mode-switch-device-task.txt").readText().trim()
                    val facade = requireNotNull(ModeSwitchRuntime.facadeOrNull())
                    val outcome = runBlocking(Dispatchers.IO) { facade.retryVerify(taskId) }
                    report("outcome", outcome.toString())
                    assertTrue("verification failed: $outcome", outcome is SwitchOutcome.Verified)
                    val status = runBlocking(Dispatchers.IO) { facade.statusFor(taskId) }
                    assertEquals("VERIFIED", status?.state)
                    assertTrue(status?.bootObserved == true)
                    assertFalse(status?.rebootRequired == true)
                    assertEquals(WorkMode.fromWire(status?.toMode.orEmpty()), ModeRuntime.active)
                    assertTrue(status?.partitions?.all { it.targetSha256 == it.observedSha256 } == true)
                    assertFalse(facade.operationActive)
                    report("switchId", taskId)
                }
                "reboot" -> {
                    val taskId = arguments.getString("switchId")
                        ?: File(context.filesDir, "mode-switch-device-task.txt").readText().trim()
                    val facade = requireNotNull(ModeSwitchRuntime.facadeOrNull())
                    assertTrue("reboot not requested", runBlocking(Dispatchers.IO) { facade.reboot(taskId) })
                }
                "abort" -> {
                    val taskId = requireNotNull(arguments.getString("switchId"))
                    val facade = requireNotNull(ModeSwitchRuntime.facadeOrNull())
                    val result = runBlocking(Dispatchers.IO) { facade.abort(taskId) }
                    report("abort", result.toString())
                    assertTrue(result?.ok == true)
                }
                "rollback" -> {
                    val taskId = requireNotNull(arguments.getString("switchId"))
                    val facade = requireNotNull(ModeSwitchRuntime.facadeOrNull())
                    val result = runBlocking(Dispatchers.IO) { facade.rollback(taskId) }
                    report("rollback", result.toString())
                    assertTrue(result?.ok == true)
                    assertEquals("ROLLED_BACK", result?.status?.state)
                    assertFalse(facade.operationActive)
                }
                else -> error("unknown action")
            }
        }
    }
}
