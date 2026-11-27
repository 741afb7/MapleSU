package dev.maplesu.manager.parasite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ultra SDK 端口：可用性映射 / 参数校验 / 不崩 / 免 Key 参数透传。 */
class ParasiteSdkPortTest {
    private class FakeApi(
        var precheckResult: Map<String, AppDynlibStatus> = emptyMap(),
        var implantCode: Int = 0,
        var suEnvCode: Int = 0,
        var throws: Boolean = false,
    ) : ParasiteUltraApi {
        val calls = mutableListOf<String>()
        var precheckCmdline: String? = null
        var implantArgs: Pair<String, String>? = null

        override fun precheckApp(targetPidCmdline: String): Map<String, AppDynlibStatus> {
            calls += "precheck"
            precheckCmdline = targetPidCmdline
            if (throws) throw UnsupportedOperationException("binding TODO")
            return precheckResult
        }

        override fun implantApp(targetPidCmdline: String, soPath: String): Int {
            calls += "implantApp"
            implantArgs = targetPidCmdline to soPath
            if (throws) throw UnsupportedOperationException("binding TODO")
            return implantCode
        }

        override fun implantSuEnv(
            targetPidCmdline: String,
            soPath: String,
            suDir: String,
        ): Int {
            calls += "implantSuEnv"
            if (throws) throw UnsupportedOperationException("binding TODO")
            return suEnvCode
        }
    }

    private val api = FakeApi()

    private fun runningPort(api: ParasiteUltraApi = this.api) = UltraLinkedParasiteSdkPort(
        api = api,
        availabilityProvider = { UltraAvailability.RUNNING },
    )

    @Test
    fun unavailablePortReturnsDistinctCodes() {
        val codes = listOf(
            UltraAvailability.RUNNING.unavailableCode(),
            UltraAvailability.NOT_RUNNING.unavailableCode(),
            UltraAvailability.UNLINKED.unavailableCode(),
        )
        assertEquals(
            listOf(
                ParasiteCodes.ULTRA_UNAVAILABLE,
                ParasiteCodes.ULTRA_NOT_RUNNING,
                ParasiteCodes.ULTRA_API_UNLINKED,
            ),
            codes,
        )
        assertEquals(3, codes.distinct().size)
    }

    @Test
    fun unavailablePortNeverPretendsSuccess() {
        val port = UnavailableParasiteSdkPort(UltraAvailability.NOT_RUNNING)
        val precheck = port.precheck("com.example.host")
        assertTrue(precheck is ParasiteSdkResult.Failed)
        assertEquals(ParasiteCodes.ULTRA_NOT_RUNNING, (precheck as ParasiteSdkResult.Failed).code)
        assertEquals(
            ParasiteCodes.ULTRA_NOT_RUNNING,
            (port.implantApp("cmd", "/so") as ParasiteSdkResult.Failed).code,
        )
        assertEquals(
            ParasiteCodes.ULTRA_NOT_RUNNING,
            (port.implantSuEnv("cmd", "/so", "/su") as ParasiteSdkResult.Failed).code,
        )
    }

    @Test
    fun currentProductionPortIsUnlinked() {
        assertEquals(
            ParasiteCodes.ULTRA_API_UNLINKED,
            (UnavailableParasiteSdkPort.CURRENT.precheck("cmd") as ParasiteSdkResult.Failed).code,
        )
    }

    @Test
    fun invalidArgumentsAreRejectedBeforeNativeCalls() {
        val port = runningPort()
        assertEquals(
            ParasiteCodes.INVALID_ARGUMENT,
            (port.precheck("  ") as ParasiteSdkResult.Failed).code,
        )
        assertEquals(
            ParasiteCodes.INVALID_ARGUMENT,
            (port.implantApp("cmd", "") as ParasiteSdkResult.Failed).code,
        )
        assertEquals(
            ParasiteCodes.INVALID_ARGUMENT,
            (port.implantSuEnv("cmd", "/so", " ") as ParasiteSdkResult.Failed).code,
        )
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun precheckMapsDynlibsSortedWithStatuses() {
        api.precheckResult = linkedMapOf(
            "/data/app/lib/libb.so" to AppDynlibStatus.NOT_RUNNING,
            "/data/app/lib/liba.so" to AppDynlibStatus.RUNNING,
        )
        val result = runningPort().precheck("com.example.host")
        assertTrue(result is ParasiteSdkResult.Ok)
        val dynlibs = (result as ParasiteSdkResult.Ok).dynlibs
        assertEquals(
            listOf("/data/app/lib/liba.so", "/data/app/lib/libb.so"),
            dynlibs.map { it.path },
        )
        assertEquals(AppDynlibStatus.RUNNING, dynlibs[0].status)
        assertEquals("com.example.host", api.precheckCmdline)
    }

    @Test
    fun precheckEmptyMapIsNoEligibleDynlib() {
        api.precheckResult = emptyMap()
        val result = runningPort().precheck("com.example.host")
        assertEquals(ParasiteCodes.NO_ELIGIBLE_DYNLIB, (result as ParasiteSdkResult.Failed).code)
    }

    @Test
    fun implantRejectionsMapToDistinctCodes() {
        api.implantCode = 12
        assertEquals(
            ParasiteCodes.IMPLANT_FAILED,
            (runningPort().implantApp("cmd", "/so") as ParasiteSdkResult.Failed).code,
        )
        api.suEnvCode = 13
        assertEquals(
            ParasiteCodes.SU_ENV_FAILED,
            (runningPort().implantSuEnv("cmd", "/so", "/su") as ParasiteSdkResult.Failed).code,
        )
    }

    @Test
    fun nativeUnlinkedExceptionMapsToApiUnlinked() {
        api.throws = true
        val port = runningPort()
        assertEquals(
            ParasiteCodes.ULTRA_API_UNLINKED,
            (port.implantApp("cmd", "/so") as ParasiteSdkResult.Failed).code,
        )
        assertEquals(
            ParasiteCodes.ULTRA_API_UNLINKED,
            (port.precheck("cmd") as ParasiteSdkResult.Failed).code,
        )
    }

    @Test
    fun runningPrecheckNeedsOnlyBusinessArguments() {
        val port = UltraLinkedParasiteSdkPort(
            api = api,
            availabilityProvider = { UltraAvailability.RUNNING },
        )
        assertEquals(
            ParasiteCodes.NO_ELIGIBLE_DYNLIB,
            (port.precheck("cmd") as ParasiteSdkResult.Failed).code,
        )
    }

    @Test
    fun notRunningShortCircuitsBeforeNative() {
        val port = UltraLinkedParasiteSdkPort(
            api = api,
            availabilityProvider = { UltraAvailability.NOT_RUNNING },
        )
        assertEquals(
            ParasiteCodes.ULTRA_NOT_RUNNING,
            (port.precheck("cmd") as ParasiteSdkResult.Failed).code,
        )
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun successPathForwardsArgumentsAndNeverThrows() {
        val port = runningPort()
        val result = port.implantApp("com.example.host", "/data/app/lib/libfoo.so")
        assertTrue(result is ParasiteSdkResult.Ok)
        assertEquals("com.example.host", api.implantArgs!!.first)
        assertEquals("/data/app/lib/libfoo.so", api.implantArgs!!.second)

        assertEquals(ParasiteSdkResult.Ok(), port.implantSuEnv("cmd", "/so", "/su"))
        assertNull((port.precheck("cmd") as ParasiteSdkResult.Failed).detail.takeIf { false })
    }

    @Test
    fun dynlibStatusWireParsingIsExplicit() {
        assertEquals(AppDynlibStatus.RUNNING, AppDynlibStatus.fromWire("running"))
        assertEquals(AppDynlibStatus.NOT_RUNNING, AppDynlibStatus.fromWire("not_running"))
        // header: Unknown = 0；解析出 UNKNOWN 是显式的，绝不落到 RUNNING/NOT_RUNNING。
        assertEquals(AppDynlibStatus.UNKNOWN, AppDynlibStatus.fromWire("unknown"))
        assertNull(AppDynlibStatus.fromWire("bogus"))
        assertNull(AppDynlibStatus.fromWire(null))
    }
}
