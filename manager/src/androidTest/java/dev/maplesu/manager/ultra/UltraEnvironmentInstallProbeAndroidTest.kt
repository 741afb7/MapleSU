package dev.maplesu.manager.ultra

import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.ultra.bridge.NativeBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * One-shot environment activation attempt, HotLoad mode, via the SDK's own entry.
 *
 * Why HotLoad: MapleSU Ultra's own Manager treats HotLoad as an independent runtime activation
 * and its Boot mode needs a boot image this repo cannot generate. This test therefore asks
 * the SDK itself whether it can provision the environment, instead of hand-rolling any
 * exploit chain - the instruction is to use MapleSU's implementation, not to invent one.
 *
 * Safety: it runs ONCE, records everything, and never retries or uninstalls. HotLoad effects
 * are runtime-only, so a reboot is the recovery path. The test refuses to run unless the
 * caller opts in explicitly with `-e verifyUltraEnvInstall true`.
 *
 * It records, rather than asserts, the outcome: a refusal is a finding about which
 * precondition is missing, and the exact `sdkError` is the evidence.
 */
class UltraEnvironmentInstallProbeAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun parse(raw: String?): JSONObject? = raw?.let { runCatching { JSONObject(it) }.getOrNull() }

    @Test
    fun hotloadActivationOnceThroughTheSdk() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyUltraEnvInstall") == "true")
        val arguments = InstrumentationRegistry.getArguments()

        val exploit = arguments.getString("ultraExploit", "MAGICA") ?: "MAGICA"
        val mode = arguments.getString("ultraMode", "HotLoad") ?: "HotLoad"
        // Write every step to disk as it happens: a Boot-mode install is documented to be able
        // to restart the device, and a reboot loses anything that was only in memory.
        val trace = File(context.filesDir, "ultra-env-install-$mode-$exploit.trace")
        fun persist(stage: String, payload: String) {
            // fsync so a Boot-mode install that restarts the device cannot lose the record.
            java.io.FileOutputStream(trace, true).use { stream ->
                stream.write((stage + " || " + payload + " ## ").toByteArray())
                stream.flush()
                stream.fd.sync()
            }
        }
        persist("begin", "mode=$mode exploit=$exploit")

        val evidence = JSONObject()
        evidence.put("mode", mode)
        evidence.put("exploit", exploit)

        // The state query is not required for installation, and on some kernel builds it is the
        // call that faults, so allow it to be skipped to test the install path on its own.
        val skipState = arguments.getString("ultraSkipState").equals("true", ignoreCase = true)
        val before = if (skipState) {
            persist("stateSkipped", "ultraSkipState=true")
            null
        } else {
            persist("stateCalling", "about to call getEnvironmentState")
            val parsed = parse(NativeBridge.getEnvironmentState())
            persist("stateBefore", parsed?.toString() ?: "null")
            parsed
        }
        evidence.put("stateBefore", before?.optJSONObject("data")?.optString("state") ?: before?.optString("sdkError"))

        // The single activation attempt. No loop, no retry: a retried install with side
        // effects is exactly what the spec forbids.
        persist("installCalling", "about to call installEnvironment")
        val installRaw = NativeBridge.installEnvironment(mode, exploit)
        persist("installReturned", installRaw)
        val install = parse(installRaw)
        evidence.put("installReceipt", installRaw)
        evidence.put("installOk", install?.optBoolean("ok") ?: false)
        evidence.put("installSdkError", install?.optString("sdkError"))
        evidence.put("installSdkErrorCode", install?.optInt("sdkErrorCode"))

        // Observe, do not push: three samples over ~4s.
        val samples = JSONArray()
        repeat(3) { index ->
            if (index > 0) Thread.sleep(2000)
            val sample = parse(NativeBridge.getEnvironmentState())
            samples.put(
                JSONObject()
                    .put("index", index)
                    .put("raw", sample?.toString() ?: "")
                    .put("state", sample?.optJSONObject("data")?.optString("state") ?: JSONObject.NULL)
                    .put("sdkError", sample?.optString("sdkError") ?: JSONObject.NULL),
            )
        }
        evidence.put("stateSamples", samples)

        val version = parse(NativeBridge.getInstalledEnvironmentVersion())
        evidence.put("installedVersionReceipt", version?.toString() ?: "")

        val list = parse(NativeBridge.getModuleList())
        evidence.put("moduleListErrorAfter", list?.optString("sdkError"))

        persist("stateSamples", samples.toString())
        persist("done", "evidence complete")
        val output = File(context.filesDir, "ultra-env-install-$mode-$exploit.json")
        output.writeText(evidence.toString(2))
        android.util.Log.i("MAPLESU_EVIDENCE", "ultra-env-install " + evidence.toString())
        assertTrue("evidence written", output.isFile && output.length() > 0)
    }
}
