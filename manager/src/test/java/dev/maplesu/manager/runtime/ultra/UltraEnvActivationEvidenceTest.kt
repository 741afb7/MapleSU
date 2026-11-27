// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ultra 环境激活证据采集（2026-10-30）。
 *
 * 覆盖：严格判定（Running + 版本回读 + testRoot）、不泄漏 root key、JSON 结构、
 * 写出后回读校验、失败路径。全部主机 JVM 可跑（注入假回执，不碰 Android/JNI）。
 */
class UltraEnvActivationEvidenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var sequence = 0

    private class FakePort(
        var sdk: UltraEvidenceReceipt = okReceipt("get_sdk_version", version = "1.0.0-ultra"),
        var environment: UltraEvidenceReceipt = okReceipt("get_maplesu_environment_state", state = "Running"),
        var installedVersion: UltraEvidenceReceipt =
            okReceipt("get_installed_maplesu_environment_version", version = "4.0.0"),
        var testRoot: UltraEvidenceReceipt = okReceipt("get_root_status_report"),
        var failOn: String? = null,
    ) : UltraEvidencePort {
        override val bridgeLoaded: Boolean = true

        override fun sdkVersion(): UltraEvidenceReceipt = take("sdk") { sdk }

        override fun environmentState(): UltraEvidenceReceipt = take("environment") { environment }

        override fun installedEnvironmentVersion(): UltraEvidenceReceipt =
            take("installedVersion") { installedVersion }

        override fun testRoot(): UltraEvidenceReceipt = take("testRoot") { testRoot }

        private fun take(name: String, block: () -> UltraEvidenceReceipt): UltraEvidenceReceipt {
            if (failOn == name) throw IllegalStateException("fake call failure: $name")
            return block()
        }
    }

    private fun evidence(
        port: FakePort,
        outputDir: File = File(tmp.root, "files-${sequence++}").apply { mkdirs() },
        ownerIdentityPresent: Boolean = true,
        mode: String? = "LITE",
        snapshot: UltraProbeSnapshot = UltraProbeSnapshot("Running", "state=Running", 1234L, 1_699_999_999_000L),
        dataDir: UltraDataDirProbe = UltraDataDirProbe(
            UltraEnvActivationEvidence.PATH_TEMPLATE,
            exists = true,
            readable = true,
            entries = listOf("file init size=4096", "dir su"),
            error = null,
        ),
    ): UltraEnvActivationEvidence = UltraEnvActivationEvidence.forTest(
        port = port,
        snapshot = { snapshot },
        ownerIdentityPresent = { ownerIdentityPresent },
        dataDirProbe = { dataDir },
        outputDir = { outputDir },
        currentMode = { mode },
        clock = { 1_700_000_000_000L },
    )

    // ---------------------------------------------------------------- strict verdict

    @Test
    fun strictActivationRequiresRunningVersionAndTestRoot() {
        val result = evidence(FakePort()).collect()
        assertTrue("evidence archive must be written", result.ok)
        assertTrue(result.strictReady)
        assertNotNull(result.file)
        val json = result.json!!
        assertTrue(json.contains("\"strictReady\": true"))
        assertTrue(json.contains("\"verdict\": \"ACTIVATED\""))
        assertTrue(json.contains("\"stateRunning\": true"))
        assertTrue(json.contains("\"versionReadbackOk\": true"))
        assertTrue(json.contains("\"testRootOk\": true"))
    }

    @Test
    fun faultIsNotActivation() {
        val port = FakePort(environment = okReceipt("get_maplesu_environment_state", state = "Fault"))
        val result = evidence(port).collect()
        assertFalse(result.strictReady)
        val json = result.json!!
        assertTrue(json.contains("\"verdict\": \"NOT_ACTIVATED\""))
        assertTrue(json.contains("\"stateRunning\": false"))
        // Fault 的状态仍在证据里（可归档、可解释），只是不算激活
        assertTrue(json.contains("\"state\": \"Fault\""))
    }

    @Test
    fun nonRunningStatesAreNeverActivated() {
        listOf("NotInstalled", "NoKey", "Unknown", "").forEach { state ->
            val port = FakePort(environment = okReceipt("get_maplesu_environment_state", state = state))
            val result = evidence(port).collect()
            assertFalse("state '$state' must not be activation", result.strictReady)
        }
        // SDK 不可用：回执缺失（state=null）同样不是激活
        val unavailable = FakePort(
            environment = UltraEvidenceReceipt.unavailable("get_maplesu_environment_state", "bridge missing"),
        )
        assertFalse(evidence(unavailable).collect().strictReady)
        for (code in listOf(1, -1, Int.MIN_VALUE)) {
            for (receipt in listOf(
                okReceipt("get_maplesu_environment_state", state = "Running").copy(sdkErrorCode = code),
                okReceipt("get_installed_maplesu_environment_version", version = "4.0.0").copy(sdkErrorCode = code),
                okReceipt("get_root_status_report").copy(sdkErrorCode = code),
            )) {
                assertFalse(receipt.succeeded)
                val port = FakePort()
                when (receipt.operation) {
                    "get_maplesu_environment_state" -> port.environment = receipt
                    "get_installed_maplesu_environment_version" -> port.installedVersion = receipt
                    else -> port.testRoot = receipt
                }
                val result = evidence(port).collect()
                assertFalse("${receipt.operation} code=$code must not prove activation", result.strictReady)
                assertTrue(result.json!!.contains("\"sdkErrorCode\": $code"))
            }
        }
    }

    @Test
    fun runningWithoutVersionReadbackIsNotActivation() {
        val port = FakePort(
            installedVersion = okReceipt("get_installed_maplesu_environment_version"),
        )
        val result = evidence(port).collect()
        assertFalse(result.strictReady)
        assertTrue(result.json!!.contains("\"versionReadbackOk\": false"))
    }

    @Test
    fun runningWithFailedVersionReceiptIsNotActivation() {
        val port = FakePort(
            installedVersion = UltraEvidenceReceipt(
                operation = "get_installed_maplesu_environment_version",
                ok = false,
                sdkError = "SDK_ERROR_ENV_VERSION_UNAVAILABLE",
            ),
        )
        val result = evidence(port).collect()
        assertFalse(result.strictReady)
        assertTrue(result.json!!.contains("\"versionReadbackOk\": false"))
    }

    @Test
    fun runningWithoutTestRootIsNotActivation() {
        val failing = FakePort(
            testRoot = UltraEvidenceReceipt(
                operation = "get_root_status_report",
                ok = false,
                sdkError = "SDK_ERROR_ROOT_TEST_FAILED",
            ),
        )
        val result = evidence(failing).collect()
        assertFalse(result.strictReady)
        assertTrue(result.json!!.contains("\"testRootOk\": false"))
    }

    @Test
    fun reportAvailabilityAndSdkOkDoNotProveRoot() {
        for (proof in listOf(null, false)) {
            val report = okReceipt("get_root_status_report").copy(rootVerified = proof)
            assertTrue(report.succeeded)
            val result = evidence(FakePort(testRoot = report)).collect()
            assertFalse(result.strictReady)
            assertTrue(result.json!!.contains("\"rootVerified\": $proof"))
        }
    }

    // ---------------------------------------------------------------- security

    @Test
    fun jsonNeverContainsRootKeyMaterial() {
        val key = "A1B2C3D4E5F60718".repeat(3) // 48 chars，模拟 Ultra rootKey
        assertEquals(48, key.length)
        val result = evidence(FakePort(), ownerIdentityPresent = true).collect()
        val json = result.json!!
        assertFalse("root key must never appear", json.contains(key))
        assertFalse("key prefix (rootKey16) must never appear", json.contains(key.take(16)))
        assertTrue(json.contains("\"present\": true"))
        assertTrue(json.contains("\"pathTemplate\": \"/data/<deployment>/\""))
        // 只允许出现路径模板，不允许出现真实 /data/<key16> 路径
        assertFalse(json.contains("/data/${key.take(16)}"))
    }

    @Test
    fun absentRootKeyIsRecordedAsFalse() {
        val result = evidence(FakePort(), ownerIdentityPresent = false).collect()
        assertTrue(result.json!!.contains("\"present\": false"))
    }

    // ---------------------------------------------------------------- archive

    @Test
    fun evidenceIsWrittenToOutputDirAndReadBack() {
        val dir = tmp.newFolder("files")
        val result = evidence(FakePort(), outputDir = dir).collect()
        assertTrue(result.ok)
        assertNotNull(result.file)
        val file = result.file!!
        assertTrue(file.isFile)
        assertTrue(file.name.startsWith(UltraEnvActivationEvidence.FILE_PREFIX))
        assertTrue(file.name.endsWith(".json"))
        assertEquals(result.json, file.readText())
        assertEquals(sha256(result.json!!), result.sha256)
        assertNull(result.failure)
    }

    @Test
    fun jsonStructureContainsAllRequiredSections() {
        val json = evidence(FakePort()).collect().json!!
        listOf(
            "\"schemaVersion\": 1",
            "\"kind\": \"ultra-env-activation-evidence\"",
            "\"collectedAtUtc\"",
            "\"currentMode\": \"LITE\"",
            "\"bridge\"",
            "\"receipts\"",
            "\"environmentState\"",
            "\"installedEnvironmentVersion\"",
            "\"testRoot\"",
            "\"probeSnapshot\"",
            "\"state\": \"Running\"",
            "\"activation\"",
            "\"criteria\"",
            "\"ownerIdentity\"",
            "\"dataDir\"",
            "\"entries\"",
        ).forEach { fragment ->
            assertTrue("missing fragment: $fragment", json.contains(fragment))
        }
    }

    @Test
    fun probeSnapshotAndDataDirAreArchived() {
        val json = evidence(
            FakePort(),
            snapshot = UltraProbeSnapshot("Fault", "state=Fault", 77L, 1_699_999_999_000L),
            dataDir = UltraDataDirProbe(
                UltraEnvActivationEvidence.PATH_TEMPLATE,
                exists = false,
                readable = false,
                entries = emptyList(),
                error = "NOT_PRESENT_OR_UNREADABLE",
            ),
        ).collect().json!!
        assertTrue(json.contains("\"state\": \"Fault\""))
        assertTrue(json.contains("\"detail\": \"state=Fault\""))
        assertTrue(json.contains("\"ageMs\": 1000"))
        assertTrue(json.contains("\"error\": \"NOT_PRESENT_OR_UNREADABLE\""))
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun callFailureIsArchivedAndNeverActivation() {
        val port = FakePort(failOn = "testRoot")
        val result = evidence(port).collect()
        assertTrue("archive should still be produced for diagnosis", result.ok)
        assertFalse(result.strictReady)
        assertTrue(result.json!!.contains("\"callError\": \"IllegalStateException: fake call failure: testRoot\""))
    }

    @Test
    fun writeFailureIsReportedNotPretended() {
        val notADirectory = File(tmp.root, "not-a-dir").apply { writeText("x") }
        val result = evidence(FakePort(), outputDir = notADirectory).collect()
        assertFalse(result.ok)
        assertNull(result.file)
        assertTrue(result.failure!!.startsWith("EVIDENCE_DIR_UNAVAILABLE"))
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** 顶层 helper：`FakePort` 的默认参数在嵌套类里不能引用外部测试类的实例成员。 */
private fun okReceipt(
    operation: String,
    state: String? = null,
    version: String? = null,
): UltraEvidenceReceipt = UltraEvidenceReceipt(
    operation = operation,
    bridgeLoaded = true,
    ok = true,
    sdkError = "OK",
    sdkErrorCode = 0,
    state = state,
    version = version,
    rootVerified = if (operation == "get_root_status_report") true else null,
    raw = "{\"backend\":\"maplesu_ultra\",\"operation\":\"$operation\",\"ok\":true,\"sdkError\":\"OK\"}",
)
