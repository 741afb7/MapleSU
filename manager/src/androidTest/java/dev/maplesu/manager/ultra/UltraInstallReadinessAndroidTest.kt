package dev.maplesu.manager.ultra

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.ultra.bridge.NativeBridge
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only SDK observations: no key provisioning, install, root commands or partition writes. */
class UltraInstallReadinessAndroidTest {
    @Test fun captureEnvironmentAndKernelChannel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyInstallReadiness") == "true")
        val state = JSONObject(NativeBridge.getEnvironmentState())
        val channel = JSONObject(NativeBridge.testBasics("Channel"))
        val version = JSONObject(NativeBridge.getInstalledEnvironmentVersion())
        // Only structured verdicts are recorded; preserve SDK errors without diagnostic payloads.
        val result = JSONObject().put("environment", state.optJSONObject("data")?.optString("state"))
            .put("environmentError", state.optString("sdkError"))
            .put("channelOk", channel.optBoolean("ok")).put("channelError", channel.optString("sdkError"))
            .put("versionOk", version.optBoolean("ok")).put("versionError", version.optString("sdkError"))
        Log.i("MapleSUInstallReadiness", result.toString())
    }
}
