package dev.maplesu.manager.ultra

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.maplesu.manager.runtime.ultra.UltraAuthorization
import dev.maplesu.manager.runtime.ultra.UltraRuntimeClient
import dev.maplesu.manager.runtime.ultra.UltraSuChannel
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class UltraSelfHealChannelAndroidTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val evidence = JSONObject()

    @Test
    fun suChannelAfterSuAuth() {
        val mode = InstrumentationRegistry.getArguments().getString("suProbe")
        assumeTrue("opt-in: -e suProbe true|grant", mode == "true" || mode == "grant")

        val packageName = context.packageName
        evidence.put("package", packageName)
        evidence.put("uid", android.os.Process.myUid())
        evidence.put("mode", mode)

        val probe = UltraSuChannel()
        evidence.put("suBeforeWhitelist", probe.rootUid())
        evidence.put("suBeforeFailure", probe.lastFailure)

        val client = UltraRuntimeClient()
        val snapshot = client.suAuthSnapshot()
        evidence.put("suAuthListOk", snapshot.ok)
        evidence.put("suAuthListSdkError", snapshot.receipt.sdkError)
        evidence.put("suAuthPackages", snapshot.packages)
        evidence.put("alreadyWhitelisted", snapshot.packages.contains(packageName))

        if (mode == "grant" && !snapshot.packages.contains(packageName)) {
            val receipt = client.addSuAuth(packageName)
            evidence.put("addSuAuthOk", receipt.succeeded)
            evidence.put("addSuAuthSdkError", receipt.sdkError)
            evidence.put("addSuAuthReason", receipt.reason)
        }

        val after = UltraSuChannel()
        val uid = after.rootUid()
        evidence.put("suAfterWhitelist", uid)
        evidence.put("suAfterFailure", after.lastFailure)

        if (uid == 0) {
            val discovery = after.runRoot(DISCOVERY_SCRIPT)
            evidence.put("discoveryExit", discovery?.exitCode)
            evidence.put("discovery", discovery?.output?.take(8_000))
        }

        // 只读刷新，供后续 UI/日志对照。
        evidence.put("authRefreshOk", UltraAuthorization.refresh(context))
        evidence.put("grantedCount", UltraAuthorization.grantedPackages().size)

        val output = File(context.filesDir, "ultra-su-auth-channel.json")
        output.writeText(evidence.toString(2))
        Log.i("MAPLESU_EVIDENCE", "ultra-su-auth-channel " + evidence.toString())
    }

    private companion object {
        /** 只读发现脚本：确认 uid、/data/adb 布局、Ultra/Lite 标记文件位置。 */
        val DISCOVERY_SCRIPT = """
            id
            echo ---DATA_ADB---
            ls -la /data/adb 2>&1 | head -40
            echo ---ALLOWLIST---
            find /data/adb -maxdepth 3 -iname '*allowlist*' 2>/dev/null | head -20
            echo ---MODES---
            cat /data/adb/maplesu-workmode/control/modes.json 2>&1 | head -20
            echo ---MAPLESU---
            ls -la /data/adb/maplesu 2>&1 | head -20
            echo ---WORKMODE---
            ls -la /data/adb/maplesu-workmode 2>&1 | head -20
            echo ---SDK-INPUTS---
            ls -d ${dev.maplesu.manager.compat.StoredInputs.ENVIRONMENT_GLOB} 2>&1 | head -10
        """.trimIndent()
    }
}
