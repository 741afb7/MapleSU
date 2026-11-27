package dev.maplesu.manager.runtime.ultra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import dev.maplesu.manager.runtime.authorization.ApplicationIdentity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UltraSdkOptimizationTest {
    private fun managerIdentity() = ApplicationIdentity(10500, "dev.maplesu.manager",
        listOf("dev.maplesu.manager"), setOf("a".repeat(64)), 10L, 11L, "/data/app/current/base.apk")
    private val manager = File(System.getProperty("user.dir") ?: ".").let {
        if (File(it, "src/main/java").isDirectory) it else File(it, "manager")
    }
    private fun source(name: String) = File(manager,
        "src/main/java/dev/maplesu/manager/runtime/ultra/$name").readText()

    @Test fun currentBuildLabelsRecoverTheRealSdkVersion() {
        for (label in listOf("maplesu-ultra-5.1.0-parasite-r7-20261105", "maplesu-ultra-5.1.0-optimized-r8-20261106")) {
            assertEquals("5.1.0", UltraEnvironmentVersion.displayOf(null, label))
        }
        assertEquals("5.0.5", UltraEnvironmentVersion.displayOf("5.0.5", "maplesu-ultra-5.1.0-optimized-r8-20261106"))
        for (bad in listOf("maplesu-ultra-5.1.0-", "maplesu-ultra-5.1.0-../bad", "maplesu-ultra-5.1.0\nfoo", "maplesu-ultra-5.1.0/extra")) {
            assertNull(bad, UltraEnvironmentVersion.displayOf(null, bad))
        }
    }

    @Test fun helperSizeComesFromTheSelectedBuildIdentity() {
        assertEquals(UltraSdkBuild.HELPER_SIZE.takeIf { it > 0 }, UltraInstalledIdentityProbe.bundledHelperSizeBytes())
    }

    @Test fun aDifferentSizedInstalledHelperIsHashedInTheSameReadback() {
        val bundledSize = 1_179_976L
        val actualSize = 1_163_328L
        val path = "/data/0123456789abcdef/init"
        val sha = "c".repeat(64)
        val receipt = NativeReceipt.parse(JSONObject()
            .put("schemaVersion", 1).put("backend", UltraRuntimeClient.BACKEND).put("bridgeVersion", 1)
            .put("operation", "read_installed_helper").put("ok", true).put("sdkError", "OK").put("sdkErrorCode", 0)
            .put("data", JSONObject().put("abiVersion", 1).put("complete", true).put("rootVerified", true)
                .put("sha256", sha).put("path", path).put("size", actualSize)
                .put("bytesRead", actualSize).put("normalizedOffset", 0L))
            .toString())
        assertNotEquals(bundledSize, actualSize)
        val observed = UltraInstalledIdentityProbe.parseReadback(receipt, path)
        assertEquals(sha, observed?.sha256)
        assertEquals(actualSize, observed?.size)
    }

    @Test fun pairingReturnsBeforeRecoveryMaintenance() {
        for (file in listOf("UltraAutoPair.kt")) {
            val text = source(file)
            assertFalse(file, text.contains("UltraEnvironmentInstaller.backupKey("))
            assertFalse(file, text.contains("UltraSelfWhitelist.ensure("))
            assertTrue(file, text.contains("UltraPairingMaintenance.request("))
        }
    }



    @Test fun localVersionAndIdentityDoNotAcquireTheMutatingSdkLock() {
        val text = source("UltraRuntimeClient.kt")
        assertFalse(text, text.contains("fun sdkVersion(): NativeReceipt = call("))
        assertFalse(text, text.contains("fun sdkBuildIdentity(): NativeReceipt = call("))
    }

    @Test fun settingsReadsOnlyProbeAndSkipsRootIdentityWhenVersionIsKnown() {
        val text = File(manager, "src/main/java/com/maplesu/core/ui/screen/settings/UltraEnvironmentSettings.kt").readText()
        val body = text.substringAfter("private suspend fun readFacts(").substringBefore("private fun readVersion(")
        assertFalse(body.contains("UltraAutoPair.ensure("))
        assertTrue(text.substringAfter("private fun readVersion(").substringBefore("private fun applyFacts(")
            .contains("if (raw != null) return@runCatching"))
    }

    @Test fun runtimeIdentityHasNoHistoricalSdkVersion() {
        val manifest = File(manager.parentFile, "runtime/maplesu-sdk/SDK-RELEASE.json").readText()
        val cmake = File(manager.parentFile, "runtime/maplesu-sdk/SDK-IDENTITY.cmake").readText()
        assertFalse(manifest.contains("4.6.5"))
        assertFalse(cmake.contains("4.6.5"))
        assertEquals(UltraSdkBuild.SDK_VERSION, JSONObject(manifest).getString("sdkVersion"))
    }




}
