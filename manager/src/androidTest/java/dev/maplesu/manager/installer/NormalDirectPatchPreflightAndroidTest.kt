package dev.maplesu.manager.installer

import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.maplesu.core.ui.MainActivity
import com.maplesu.core.ui.util.LkmSelection
import com.maplesu.core.ui.util.FlashOutcome
import com.maplesu.core.ui.util.installBoot
import dev.maplesu.image.RamdiskInspector
import dev.maplesu.manager.workmode.Hashes
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read the current init_boot and export a Normal candidate; stop before the write callback. */
class NormalDirectPatchPreflightAndroidTest {
    @Test(timeout = 120_000)
    fun currentInitBootReachesTheNormalBuilderWithoutWritingPartitions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyNormalDirectPatch") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val taskId = UUID.randomUUID().toString()
            fun observe() = InstallerServiceClient.withService(context, rootTarget = InstallTargetMode.NORMAL) {
                InstallerServiceClient.decode(InstallerServiceClient.observe(it))
            }
            val before = observe()
            var outputPath: String? = null
            var sourceForm = ""
            try {
                StockInstallFlow.install(context, taskId, InstallTargetMode.NORMAL, "init_boot", null,
                    restoreOriginal = false, selectedManager = null, conflictAccepted = true,
                    onPrepared = { assertEquals("PREPARED", it.getString("state")) },
                    produce = { source, _ ->
                        assertEquals(before.partitions.getValue("init_boot").sha256, Hashes.sha256(source))
                        sourceForm = RamdiskInspector.inspect(source).form().toString()
                        val log = mutableListOf<String>()
                        val result = installBoot(
                            bootUri = Uri.fromFile(source), lkm = LkmSelection.KmiNone,
                            ota = false, partition = "init_boot", allowShell = false, enableAdb = false,
                            forceBackup = false, blockModules = "", onStdout = log::add, onStderr = log::add,
                            targetMode = InstallTargetMode.NORMAL, existingRootConflictAccepted = true,
                        )
                        assertEquals("Normal patch failed: ${result.err}; ${log.joinToString("\n")}", 0, result.code)
                        assertEquals(FlashOutcome.SAVED_ONLY, result.outcome)
                        assertFalse(result.outputPath.isNullOrBlank())
                        outputPath = result.outputPath
                        throw PreflightComplete()
                    },
                    onWriting = { throw AssertionError("Read-only Normal preflight reached the write stage") },
                )
                fail("Normal preflight did not reach its deliberate stop")
            } catch (_: PreflightComplete) {
                // StockInstallAttempt cancels PREPARED when the producer stops before commit.
            }
            val status = InstallerServiceClient.installationStatus(context, taskId, InstallTargetMode.NORMAL)
            assertEquals("CANCELLED", status.getString("state"))
            assertEquals(0, status.getInt("attemptedPartitionCount", -1))
            val after = observe()
            assertEquals(before.partitions.mapValues { it.value.sha256 }, after.partitions.mapValues { it.value.sha256 })
            instrumentation.sendStatus(2, Bundle().apply {
                putString("normalPatchOutput", outputPath)
                putString("normalSourceForm", sourceForm)
                putString("normalInitBootSha256", before.partitions.getValue("init_boot").sha256)
                putBoolean("normalPartitionsUnchanged", true)
            })
        }
    }

    private class PreflightComplete : RuntimeException("INSTALLER_PREFLIGHT_COMPLETE")
}
