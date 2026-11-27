package dev.maplesu.manager.runtime

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Verifies the library-free PATH injection through a process that reports its own PATH.
 *
 * <p>`/proc/<pid>/environ` shows the environment captured at `exec`, so a later `setenv`
 * never appears there. The fixture's own `System.getenv("PATH")` is used instead, and it
 * has to be logged *after* the injection: the activity is started a second time, the same
 * process is reused, and it prints the PATH it now holds.
 */
class InjectVerifyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixture = "dev.maplesu.wiredtarget"
    private val component = "$fixture/dev.maplesu.wiredtarget.Main"

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }

    @Test fun injectionReachesTheTargetProcess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyMapleSuBoot") == "true")
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySuPolicyMutations") == "true")
        val uid = context.packageManager.getApplicationInfo(fixture, 0).uid
        val original = DirectPolicy.hasGrantStrict(context, uid)
        DirectPolicy.grant(context, uid)
        try {
            assertTrue("SU publication failed", SuPublication.publish(context))
            val directory = requireNotNull(SuPublication.suDirectory(context)) {
                "SU publication returned no verified directory"
            }
            shell("am force-stop $fixture")
            Thread.sleep(1_200)
            shell("logcat -c")
            shell("am start -n $component --es scenario probe")
            Thread.sleep(2_000)
            val injected = SuPathWiring.injectPath(context, fixture)
            // Same process, second activity instance: this onCreate reads the PATH the
            // injection just installed, which the first one could not.
            shell("am start -n $component --es scenario probe")
            Thread.sleep(1_500)
            val paths = shell("logcat -d -s WIREDTARGET").lineSequence()
                .filter { it.contains("PATH=") }.toList()
            val line = paths.lastOrNull().orEmpty()
            android.util.Log.i("MapleSU",
                "MAPLESU_EVIDENCE inject_verify ok=${injected.ok} detail=${injected.detail} " +
                    "lines=${paths.size} path=[$line]")
            assertTrue("injection failed: ${injected.detail}", injected.ok)
            assertTrue("the fixture's own getenv(PATH) does not contain $directory: $line",
                line.contains(directory))
        } finally {
            if (original == true) DirectPolicy.grant(context, uid) else DirectPolicy.revoke(context, uid)
        }
    }
}
