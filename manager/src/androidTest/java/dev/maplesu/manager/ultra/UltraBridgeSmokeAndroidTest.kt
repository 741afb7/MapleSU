package dev.maplesu.manager.ultra

import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.ultra.bridge.NativeBridge
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Ultra bridge smoke test: does the fixed prebuilt SDK actually load and answer on this
 * device, and does every answer arrive as a structured receipt?
 *
 * This test deliberately does NOT need root and does NOT install an environment. It
 * separates four things the delivery must never merge:
 *
 *  - `sdkLoaded`         the library and its 30 entries are present in the APK
 *  - `sdkCompatible`     the SDK answers a read-only version query
 *  - `ultraEnvironmentReady` the SDK reports **Running only** (strict; Fault is not ready,
 *    matching `UltraReadinessRules` / `UltraEnvironmentProbe.isRunning()`)
 *  - `moduleRuntimeVerified` a module was installed and its effect read back (not here)
 *
 * A missing environment is an expected, recorded result: the receipt must say
 * ERR_MAPLESU_ENV_NOT_INSTALL rather than fabricate a list.
 *
 * Opt-in: `-e verifyUltraBridge true`. The opt-in keeps an unexpected native crash out of
 * unrelated regression runs.
 */
class UltraBridgeSmokeAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val evidence = JSONObject()

    private fun requireOptIn() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("verifyUltraBridge") == "true"
        )
    }

    private fun receipt(raw: String, operation: String): JSONObject {
        assertNotNull("$operation returned null", raw)
        val json = JSONObject(raw)
        assertEquals("$operation schemaVersion", 1, json.getInt("schemaVersion"))
        assertEquals("$operation backend", "maplesu_ultra", json.getString("backend"))
        assertEquals("$operation operation", operation, json.getString("operation"))
        assertTrue("$operation must report ok", json.has("ok"))
        assertTrue("$operation must carry sdkError", json.has("sdkError"))
        assertTrue("$operation must carry sdkErrorCode", json.has("sdkErrorCode"))
        // A receipt is JSON, never a bare boolean or a human sentence.
        assertTrue("$operation receipt must be a JSON object", raw.trimStart().startsWith("{"))
        return json
    }

    // SELinux mode is deliberately NOT measured from this process: the Manager runs as an
    // ordinary application and can neither read /sys/fs/selinux/enforce nor exec the shell
    // tool. The authoritative Enforcing + policyvers measurement is taken outside the app
    // domain by the device harness and merged into this evidence file.

    @Test
    fun sdkLoadsAndAnswersWithStructuredReceipts() {
        requireOptIn()

        // --- 1. library load + read-only version query -----------------------------
        val versionRaw = NativeBridge.getSdkVersion()
        val version = receipt(versionRaw, "get_sdk_version")
        assertTrue("get_sdk_version must succeed", version.getBoolean("ok"))
        assertEquals("get_sdk_version sdkError", "OK", version.getString("sdkError"))
        assertEquals("get_sdk_version code", 0, version.getInt("sdkErrorCode"))
        val versionText = version.getJSONObject("data").getString("version")
        assertTrue(
            "SDK version must be dotted numeric, got '$versionText'",
            Regex("""^\d+\.\d+\.\d+$""").matches(versionText)
        )
        evidence.put("sdkVersion", versionText)
        evidence.put("sdkVersionReceipt", versionRaw)
        evidence.put("sdkLoaded", true)
        evidence.put("sdkCompatible", true)

        // --- 1b. no libc++_shared dependency slipped in ---------------------------
        val maps = File("/proc/self/maps").readLines()
        val sharedCpp = maps.filter { it.contains("libc++_shared.so") }
        evidence.put("libcxxSharedMapped", sharedCpp.isNotEmpty())
        assertTrue(
            "The bridge must stay on the static C++ runtime expected by the imported archive",
            sharedCpp.isEmpty()
        )

        // --- 2. system status + SELinux identity ---------------------------------
        val statusRaw = NativeBridge.getSystemStatusJson()
        val status = receipt(statusRaw, "get_system_status")
        assertTrue("get_system_status must succeed", status.getBoolean("ok"))
        val statusData = status.getJSONObject("data")
        // The Manager runs as an ordinary application, and reading /sys/fs/selinux/enforce
        // is not permitted in that domain - the same probe run from a shell can read it.
        // A per-domain "unreadable" is a fact to record, not a failure to paper over: the
        // authoritative Enforcing measurement is taken outside the app domain below.
        val readableFromApp = statusData.getBoolean("selinux_readable")
        evidence.put("selinuxReadableFromAppDomain", readableFromApp)
        evidence.put("selinuxEnforceFromAppDomain", statusData.getInt("selinux"))
        evidence.put("systemStatusReceipt", statusRaw)
        if (readableFromApp) {
            assertTrue("SELinux must be Enforcing", statusData.getBoolean("selinux_enforcing"))
        }

        // --- 3. environment state: honest, never defaulted to Running -------------
        val envRaw = NativeBridge.getEnvironmentState()
        val env = receipt(envRaw, "get_maplesu_environment_state")
        evidence.put("envStateReceipt", envRaw)
        val envState = if (env.getBoolean("ok")) env.getJSONObject("data").getString("state") else null
        evidence.put("envState", envState ?: JSONObject.NULL)
        // 严格口径：只有字面量 Running 是 ready；Fault 不是 ready（与 UltraReadinessRules 一致）。
        evidence.put("ultraEnvironmentReady", envState == "Running")
        if (env.getBoolean("ok")) {
            assertTrue(
                "Unknown environment state must surface as an error, got '$envState'",
                envState == "NotInstalled" || envState == "Running" || envState == "Fault"
            )
            // This device is not expected to have a Ultra environment installed. If it ever
            // reports Running, that is a finding, not a failure of the bridge.
        }

        // --- 4. module list: a missing environment is an error, not an empty list --
        val listRaw = NativeBridge.getModuleList()
        val list = receipt(listRaw, "get_all_modules_list")
        evidence.put("moduleListReceipt", listRaw)
        val listOk = list.getBoolean("ok")
        val listError = list.getString("sdkError")
        evidence.put("moduleListOk", listOk)
        evidence.put("moduleListSdkError", listError)
        if (!listOk) {
            // Which error the SDK raises depends on where it stops first: without a Ultra boot
            // it reports ERR_MODULE_NO_BOOT, and with a boot but no environment it reports
            // ERR_MAPLESU_ENV_NOT_INSTALL. The contract being tested is that a refusal is an
            // error with a name, never an empty list presented as a successful read.
            assertTrue("A refused module list must name an SDK error", listError.isNotEmpty())
            assertTrue("A refused list must not look like a successful empty read",
                listError != "OK")
            assertFalse("A refused list must not carry a data.modules array", list.has("data") &&
                list.getJSONObject("data").has("modules"))
        }

        // --- 5. former-boolean entries now answer with receipts -------------------
        // Boot-fail-protect was removed from the product (user directive 2026-11-01). The JNI
        // entry point is still bound in the pinned .so, so what must be asserted is that the
        // *product* surface no longer exposes it.
        val bootProtectApi = listOf("setBootFailProtect", "isBootFailProtect")
            .filter { name -> UltraRuntimeClient::class.java.methods.any { it.name == name } }
        assertTrue("boot-fail-protect must not be exposed by UltraRuntimeClient: $bootProtectApi",
            bootProtectApi.isEmpty())
        evidence.put("bootFailProtectProductApi", "removed")

        val adbRaw = NativeBridge.isAdbForcedDisabled()
        receipt(adbRaw, "is_adb_forced_disabled").let { evidence.put("adbDisabledReceipt", adbRaw) }

        val logRaw = NativeBridge.isLogEnabled()
        receipt(logRaw, "is_maplesu_log_enabled").let { evidence.put("logEnabledReceipt", logRaw) }

        val oemRaw = NativeBridge.oneplusBypassIsWorkNormal()
        receipt(oemRaw, "oneplus_bypass_is_work_normal").let { evidence.put("oneplusReceipt", oemRaw) }

        // --- 6. an unknown enum token must be refused, not guessed ----------------
        val badBasics = receipt(
            NativeBridge.testBasics("NoSuchItem"),
            "test_maplesu_basics"
        )
        assertFalse("An unknown BasicItem must be refused", badBasics.getBoolean("ok"))
        assertEquals(
            "MAPLESU_BRIDGE_BAD_DIAG_ITEM",
            badBasics.getString("sdkError")
        )

        val badMode = receipt(
            NativeBridge.installEnvironment("Sideways", "NONE"),
            "install_maplesu_environment"
        )
        assertFalse("An unknown InstallMode must be refused", badMode.getBoolean("ok"))
        assertEquals("MAPLESU_BRIDGE_BAD_INSTALL_MODE", badMode.getString("sdkError"))

        // --- 7. evidence written for the harness to merge with the out-of-domain
        //        SELinux measurement --------------------------------------------------
        val output = File(context.filesDir, "ultra-bridge-evidence.json")
        output.writeText(evidence.toString(2))
        android.util.Log.i("MAPLESU_EVIDENCE", "ultra-bridge " + evidence.toString())
        assertTrue("evidence written", output.isFile && output.length() > 0)
    }
}
