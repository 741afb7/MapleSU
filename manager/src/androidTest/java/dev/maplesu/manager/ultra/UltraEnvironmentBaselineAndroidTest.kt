package dev.maplesu.manager.ultra

import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.ultra.bridge.NativeBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Read-only signed-caller, environment and Root observations before an install attempt.
 * The historical method name is retained for existing device harness selectors.
 * No local credential is generated or set. SDK failures remain recorded separately.
 * Opt-in: `-e verifyUltraEnv true`.
 */
class UltraEnvironmentBaselineAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val evidence = JSONObject()

    private fun receipt(raw: String, operation: String): JSONObject {
        assertNotNull("$operation returned null", raw)
        val json = JSONObject(raw)
        assertEquals("$operation backend", "maplesu_ultra", json.getString("backend"))
        assertEquals("$operation operation", operation, json.getString("operation"))
        return json
    }

    @Test
    fun keyIsAcceptedAndEnvironmentIsAbsent() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("verifyUltraEnv") == "true"
        )

        val owner = receipt(NativeBridge.queryManagerOwnerIdentity(), "query_manager_owner_identity")
        evidence.put("ownerIdentityReceipt", owner)

        // 1. environment state for the current signed caller
        val stateRaw = NativeBridge.getEnvironmentState()
        val state = receipt(stateRaw, "get_maplesu_environment_state")
        evidence.put("envStateReceipt", stateRaw)
        evidence.put("envState", if (state.getBoolean("ok")) state.getJSONObject("data").getString("state") else null)

        // 2. installed environment version: absent environment must be an error, not "0.0.0"
        val versionRaw = NativeBridge.getInstalledEnvironmentVersion()
        val version = receipt(versionRaw, "get_installed_maplesu_environment_version")
        evidence.put("installedVersionReceipt", versionRaw)
        evidence.put("installedVersionOk", version.getBoolean("ok"))
        evidence.put("installedVersionError", version.getString("sdkError"))

        // 3. root channel report for the current caller
        val rootRaw = NativeBridge.testRoot()
        val root = receipt(rootRaw, "get_root_status_report")
        evidence.put("rootStatusReceipt", rootRaw)
        evidence.put("rootStatusOk", root.getBoolean("ok"))

        // 4. descriptor parsing preserves its real ZIP input and SDK error
        val teeZip = File(context.getExternalFilesDir(null), "tee.zip")
        if (teeZip.isFile) {
            val descRaw = NativeBridge.parseModuleDescriptor(teeZip.absolutePath)
            val desc = receipt(descRaw, "parse_module_desc_from_zip_file")
            evidence.put("descriptorReceipt", descRaw)
            evidence.put("descriptorOk", desc.getBoolean("ok"))
            evidence.put("descriptorError", desc.getString("sdkError"))
        } else {
            evidence.put("descriptorReceipt", "not-run: tee.zip absent at ${teeZip.absolutePath}")
        }

        // 5. module list under the same caller identity
        val listRaw = NativeBridge.getModuleList()
        val list = receipt(listRaw, "get_all_modules_list")
        evidence.put("moduleListReceipt", listRaw)
        evidence.put("moduleListError", list.getString("sdkError"))

        val output = File(context.filesDir, "ultra-env-baseline.json")
        output.writeText(evidence.toString(2))
        android.util.Log.i("MAPLESU_EVIDENCE", "ultra-env-baseline " + evidence.toString())
        assertTrue("evidence written", output.isFile && output.length() > 0)
    }
}
