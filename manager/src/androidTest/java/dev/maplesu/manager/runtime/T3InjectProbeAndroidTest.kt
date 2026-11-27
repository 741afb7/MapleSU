package dev.maplesu.manager.runtime

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.authorization.DirectPolicy
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T3: PATH injection into an application that has **no native library at all**.
 *
 * <p>The probe (`dev.maplesu.purejava`, built from
 * `.analysis/root-nonresident-20261027/t3-purejava`) ships no `.so`, so the implant has
 * nothing to patch and only injection can reach it. It reports its own `getenv("PATH")` and
 * the result of a bare `su` from inside its process, and it re-probes on a broadcast, so the
 * before/after comparison happens in one process instead of across a restart.
 *
 * <p>`/proc/<pid>/environ` is not used anywhere here: it is the exec-time snapshot, and
 * neither mechanism can ever appear in it.
 */
class T3InjectProbeAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val probe = "dev.maplesu.purejava"
    private val component = "$probe/dev.maplesu.purejava.Main"

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }

    private fun lines(): List<String> =
        shell("logcat -d -s PUREJAVA").lineSequence().filter { it.contains("PUREJAVA") }.toList()

    @Test fun injectionGivesALibraryFreeApplicationABareSu() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyMapleSuBoot") == "true")
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySuPolicyMutations") == "true")
        val uid = context.packageManager.getApplicationInfo(probe, 0).uid
        val original = DirectPolicy.hasGrantStrict(context, uid)
        DirectPolicy.grant(context, uid)
        try {
            assertTrue("SU publication failed", SuPublication.publish(context))
            val directory = requireNotNull(SuPublication.suDirectory(context)) {
                "SU publication returned no verified directory"
            }
            shell("am force-stop $probe")
            Thread.sleep(800)
            shell("logcat -c")
            shell("am start -n $component")
            Thread.sleep(2_500)
            val before = lines()
            val beforePath = before.lastOrNull { it.contains("PATH=") }.orEmpty()
            val beforeBare = before.lastOrNull { it.contains("BARE") }.orEmpty()

            val injected = SuPathWiring.injectPath(context, probe)
            // Same process, fresh reading of its own environment and of a bare `su`.
            shell("am broadcast -a dev.maplesu.purejava.PROBE -p $probe")
            Thread.sleep(2_000)
            val after = lines()
            val afterPath = after.lastOrNull { it.contains("PATH=") }.orEmpty()
            val afterBare = after.lastOrNull { it.contains("BARE") }.orEmpty()

            android.util.Log.i("MapleSU", "MAPLESU_EVIDENCE t3_probe injected=${injected.ok} " +
                "code=${injected.code} detail=${injected.detail}")
            android.util.Log.i("MapleSU", "MAPLESU_EVIDENCE t3_probe_before path=[$beforePath] bare=[$beforeBare]")
            android.util.Log.i("MapleSU", "MAPLESU_EVIDENCE t3_probe_after path=[$afterPath] bare=[$afterBare]")

            assertTrue("injection failed: ${injected.detail}", injected.ok)
            assertTrue("the target's PATH already contained $directory before injection: $beforePath",
                !beforePath.contains(directory))
            assertTrue("the target's own getenv(PATH) does not contain $directory: $afterPath",
                afterPath.contains(directory))
            // A bare `su` before the injection cannot be found at all (127); afterwards the
            // client is found and started, and answers with its own message.
            assertTrue("bare su resolved before the injection: $beforeBare",
                beforeBare.contains("rc=127"))
            assertTrue("bare su still does not resolve after the injection: $afterBare",
                !afterBare.contains("rc=127"))
        } finally {
            if (original == true) DirectPolicy.grant(context, uid) else DirectPolicy.revoke(context, uid)
        }
    }

    /**
     * The reason the old fallback missed: a library-free target makes the implant refuse
     * before it can even enumerate a library - there is no `lib/` directory, so the refusal
     * is "no delivered client", not "no patchable JNI library". The old caller compared
     * `detail` against that second string, which this population never produces.
     */
    @Test fun theImplantRefusesALibraryFreeTargetWithAMachineReadableReason() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyMapleSuBoot") == "true")
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySuPolicyMutations") == "true")
        val uid = context.packageManager.getApplicationInfo(probe, 0).uid
        val original = DirectPolicy.hasGrantStrict(context, uid)
        DirectPolicy.grant(context, uid)
        SuPublication.publish(context)
        try {
            shell("am start -n $component")
            Thread.sleep(2_000)
            val wired = SuPathWiring.wire(context, probe)
            val libraryFree = SuPathWiring.hasNoNativeLibraries(context, probe)
            android.util.Log.i("MapleSU", "MAPLESU_EVIDENCE t3_wire_reason ok=${wired.ok} " +
                "code=${wired.code} reason=${wired.reason} libraryFree=$libraryFree detail=${wired.detail}")
            assertTrue("the implant claimed to wire a target with no libraries: ${wired.detail}", !wired.ok)
            assertTrue("the target is expected to have no native library directory", libraryFree)
            assertTrue("the refusal is not machine-readable: ${wired.reason}",
                wired.reason == SuPathWiring.Reason.NO_CANDIDATES ||
                    wired.reason == SuPathWiring.Reason.NO_PATCHABLE ||
                    wired.reason == SuPathWiring.Reason.CLIENT)
        } finally {
            if (original == true) DirectPolicy.grant(context, uid) else DirectPolicy.revoke(context, uid)
        }
    }
}
