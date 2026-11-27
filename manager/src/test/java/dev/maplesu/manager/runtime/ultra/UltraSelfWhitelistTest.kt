// SPDX-License-Identifier: GPL-3.0-or-later
package dev.maplesu.manager.runtime.ultra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Package-port fixtures retain write receipts and distinguish membership from delivery. */
private fun whitelistReceipt(error: String = "OK") = NativeReceipt(
    1, UltraRuntimeClient.BACKEND, 1, "su_auth", error == "OK", error,
    if (error == "OK") 0 else -1, null, null, "",
)

class UltraSelfWhitelistTest {

    private class FakePort(
        var snapshot: List<String>? = emptyList(),
        var addResult: String = "OK",
        var removeResult: String = "OK",
    ) : SuAuthPort {
        val adds = mutableListOf<String>()
        val removes = mutableListOf<String>()
        var snapshotCalls = 0

        override fun snapshot(): List<String>? {
            snapshotCalls++
            return snapshot
        }

        override fun add(packageName: String): NativeReceipt {
            adds += packageName
            if (addResult == "OK") snapshot = (snapshot.orEmpty() + packageName).distinct()
            return whitelistReceipt(addResult)
        }

        override fun remove(packageName: String): NativeReceipt {
            removes += packageName
            snapshot = snapshot.orEmpty().filterNot { it == packageName }
            return whitelistReceipt(removeResult)
        }
    }

    private val pkg = "dev.maplesu.manager"

    @Test
    fun committedMembershipDoesNotClaimSuccessfulDelivery() {
        var packages = emptyList<String>()
        var writes = 0
        val port = object : SuAuthPort {
            override fun snapshot() = packages
            override fun add(packageName: String): NativeReceipt {
                writes++
                packages = listOf(packageName)
                return whitelistReceipt("ERR_SU_DELIVERY_INCOMPLETE")
            }
            override fun remove(packageName: String) = whitelistReceipt("UNEXPECTED")
        }
        val result = UltraSelfWhitelist.ensure(port, pkg)
        assertTrue(result.inList)
        assertTrue(result.listVerified)
        assertFalse(result.added)
        assertEquals("ERR_SU_DELIVERY_INCOMPLETE", result.failure)
        assertFalse(result.succeeded)
        assertEquals(1, writes)
    }

    @Test
    fun okReceiptWithoutPersistedEntryIsNotAConfirmedGrant() {
        val port = object : SuAuthPort {
            override fun snapshot() = emptyList<String>()
            override fun add(packageName: String) = whitelistReceipt()
            override fun remove(packageName: String) = whitelistReceipt()
        }
        val result = UltraSelfWhitelist.ensure(port, pkg)
        assertFalse(result.inList)
        assertFalse(result.added)
        assertEquals("READBACK_MISMATCH", result.failure)
    }

    @Test
    fun forceRefreshDoesNotRevokeAnExistingGrant() {
        val port = FakePort(snapshot = listOf(pkg))
        UltraSelfWhitelist.ensure(port, pkg, force = true)
        assertTrue("Refreshing the UID mapping must not remove the working grant", port.removes.isEmpty())
    }

    @Test
    fun failedReadbackDoesNotMasqueradeAsVerified() {
        var reads = 0
        val port = object : SuAuthPort {
            override fun snapshot(): List<String>? = if (reads++ == 0) emptyList() else null
            override fun add(packageName: String) = whitelistReceipt()
            override fun remove(packageName: String) = whitelistReceipt()
        }
        val result = UltraSelfWhitelist.ensure(port, pkg)
        assertFalse(result.inList)
        assertFalse(result.listVerified)
        assertEquals("READBACK_FAILED", result.failure)
    }

    @Test
    fun addsSelfWhenAbsentFromList() {
        val port = FakePort(snapshot = listOf("com.example.other"))
        val result = UltraSelfWhitelist.ensure(port, pkg)

        assertTrue(result.inList)
        assertTrue(result.added)
        assertFalse(result.alreadyPresent)
        assertTrue(result.attempted)
        assertTrue(result.listVerified)
        assertEquals(listOf(pkg), port.adds)
    }

    @Test
    fun alreadyPresentIsIdempotentAndDoesNotCallAdd() {
        val port = FakePort(snapshot = listOf("com.example.other", pkg))
        val result = UltraSelfWhitelist.ensure(port, pkg)

        assertTrue(result.inList)
        assertFalse(result.added)
        assertTrue(result.alreadyPresent)
        // 幂等纪律：已在列表里就绝不重复 add。
        assertFalse(result.attempted)
        assertTrue(port.adds.isEmpty())
        assertEquals(1, port.snapshotCalls)
    }

    @Test
    fun addFailureIsReportedWithoutPretendingSuccess() {
        val port = FakePort(snapshot = emptyList(), addResult = "-7")
        val result = UltraSelfWhitelist.ensure(port, pkg)

        assertFalse(result.inList)
        assertFalse(result.added)
        assertTrue(result.attempted)
        assertEquals("ADD_FAILED:-7", result.failure)
        assertEquals(listOf(pkg), port.adds)
    }

    @Test
    fun unreadableInitialSnapshotCanBeVerifiedAfterAdd() {
        // 初始读取失败仍可尝试一次，成功后的快照决定最终状态。
        val port = FakePort(snapshot = null, addResult = "OK")
        val result = UltraSelfWhitelist.ensure(port, pkg)

        assertTrue(result.inList)
        assertTrue(result.added)
        assertTrue(result.listVerified)
        assertEquals(1, port.adds.size)
    }

    @Test
    fun unreadableSnapshotAndAddFailureIsHonest() {
        val port = FakePort(snapshot = null, addResult = "EIO")
        val result = UltraSelfWhitelist.ensure(port, pkg)

        assertFalse(result.inList)
        assertFalse(result.listVerified)
        assertEquals("ADD_FAILED:EIO", result.failure)
    }

    @Test
    fun blankPackageIsRejectedWithoutTouchingSdk() {
        val port = FakePort()
        val result = UltraSelfWhitelist.ensure(port, "   ")

        assertFalse(result.inList)
        assertEquals("EMPTY_PACKAGE", result.failure)
        assertEquals(0, port.snapshotCalls)
        assertTrue(port.adds.isEmpty())
    }

    @Test
    fun forceReaddRefreshesStaleEntryWithoutTouchingOthers() {
        // 设备实测：重装换 UID 后，su_auth 里包名还在，但 Ultra 的 su 重定向不认。
        // SDK add 本身重建 UID 表；这里验证没有先删除原持久条目。
        val port = FakePort(snapshot = listOf("com.example.other", pkg))
        val result = UltraSelfWhitelist.ensure(port, pkg, force = true)

        assertTrue(result.inList)
        assertTrue(result.added)
        assertFalse(result.alreadyPresent)
        assertTrue(result.attempted)
        assertTrue(port.removes.isEmpty())
        assertEquals(listOf(pkg), port.adds)
    }

    @Test
    fun forceReaddOnAbsentEntryDoesNotRemove() {
        val port = FakePort(snapshot = listOf("com.example.other"))
        val result = UltraSelfWhitelist.ensure(port, pkg, force = true)

        assertTrue(result.inList)
        assertTrue(result.added)
        assertTrue(port.removes.isEmpty())
        assertEquals(listOf(pkg), port.adds)
    }

    @Test
    fun forceReaddAddFailureIsReportedHonestly() {
        val port = FakePort(snapshot = listOf(pkg), addResult = "-9")
        val result = UltraSelfWhitelist.ensure(port, pkg, force = true)

        assertFalse(result.inList)
        assertFalse(result.added)
        assertEquals("ADD_FAILED:-9", result.failure)
        assertTrue(port.removes.isEmpty())
        assertEquals(listOf(pkg), port.snapshot)
        assertEquals(1, port.adds.size)
    }

    @Test
    fun summaryCarriesNoKeyOrSecretMaterial() {
        val port = FakePort(snapshot = emptyList())
        val summary = UltraSelfWhitelist.ensure(port, pkg).summary

        assertFalse(UltraSdkLogging.containsKeyLike(summary))
        assertTrue(summary.contains("inList=true"))
        assertTrue(summary.contains("added=true"))
    }
}

/**
 * 已提交安装的授权维护编排。
 *
 * <p>[UltraEnvironmentInstaller.afterInstall] 是纯编排：安装回执是否成功决定要不要动手；
 * 授权维护失败保留真实诊断，不改变已提交的安装结果。
 */
class UltraEnvironmentInstallerAfterInstallTest {

    private class Calls {
        var whitelist = 0
    }

    private fun whitelistOk() = UltraSelfWhitelist.Result(
        inList = true,
        added = true,
        alreadyPresent = false,
        attempted = true,
        listVerified = true,
    )

    @Test
    fun failedInstallDoesNotWhitelist() {
        val calls = Calls()
        val outcome = UltraEnvironmentInstaller.afterInstall(
            succeeded = false,
            sdkError = "-1",
            reason = "NOT_INSTALLED",
            whitelist = { calls.whitelist++; whitelistOk() },
        )

        assertFalse(outcome.ok)
        assertEquals(0, calls.whitelist)
        assertEquals("-1", outcome.sdkError)
        assertEquals("NOT_INSTALLED", outcome.reason)
    }

    @Test
    fun successfulInstallWhitelistsOnce() {
        val calls = Calls()
        val order = mutableListOf<String>()
        val outcome = UltraEnvironmentInstaller.afterInstall(
            succeeded = true,
            sdkError = "OK",
            reason = null,
            whitelist = { order += "whitelist"; calls.whitelist++; whitelistOk() },
        )

        assertTrue(outcome.ok)
        assertEquals(1, calls.whitelist)
        assertEquals(listOf("whitelist"), order)
    }

    @Test
    fun whitelistFailureKeepsInstallSuccessful() {
        val calls = Calls()
        val outcome = UltraEnvironmentInstaller.afterInstall(
            succeeded = true,
            sdkError = "OK",
            reason = null,
            whitelist = {
                calls.whitelist++
                UltraSelfWhitelist.Result(
                    inList = false,
                    added = false,
                    alreadyPresent = false,
                    attempted = true,
                    listVerified = true,
                    failure = "ADD_FAILED:-7",
                )
            },
        )

        // 安装本身仍然成功；白名单失败只记录。
        assertTrue(outcome.ok)
        assertEquals(1, calls.whitelist)
        assertTrue(outcome.whitelist.orEmpty().contains("ADD_FAILED:-7"))
    }

    @Test
    fun whitelistExceptionKeepsInstallSuccessful() {
        val outcome = UltraEnvironmentInstaller.afterInstall(
            succeeded = true,
            sdkError = "OK",
            reason = null,
            whitelist = { throw IllegalStateException("jni gone") },
        )

        assertTrue(outcome.ok)
        assertEquals("EXCEPTION:WHITELIST", outcome.whitelist)
    }

    @Test
    fun outcomeSummaryNeverContainsKeyMaterial() {
        val outcome = UltraEnvironmentInstaller.afterInstall(
            succeeded = true,
            sdkError = "OK",
            reason = null,
            whitelist = { whitelistOk() },
        )

        assertFalse(UltraSdkLogging.containsKeyLike(outcome.summary))
    }
}
