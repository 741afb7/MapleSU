package dev.maplesu.manager.runtime.ultra

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UltraBootSdkGatewayTest {
    private val boot = "11111111-2222-4333-8444-555555555555"
    private val base = "0xffff800000000000"
    private val diagnostic = "untrusted-output"
    private fun ready(capabilities: Int = 7) = UltraBootSdkGateway.Receipt(boot, 1, 0, capabilities, base)
    private fun empty() = UltraBootSdkGateway.Receipt(boot, 0, 0, 0, "0x0000000000000000")
    private fun json() = JSONObject().put("v", 1).put("bootId", boot).put("state", 1)
        .put("error", 0).put("capabilities", 7).put("kernelBase", base)
    private fun parse(record: JSONObject) = UltraBootSdkGateway.parse("MAPLESU_BOOT_SDK:$record", boot)

    private inner class Port : UltraBootSdkGateway.Port {
        val calls = mutableListOf<String>()
        var valid = true
        var receipt: UltraBootSdkGateway.Receipt? = empty()
        var afterStart: UltraBootSdkGateway.Receipt? = ready()
        var guardStored = false
        var guardOk = true
        var bindOk = true
        var submissions = 0
        var startError: Exception? = null
        var startFailure: String? = null
        var after: (String) -> Unit = {}
        private fun <T> observed(name: String, value: T): T = value.also {
            calls += name
            after(name)
        }
        override fun current() = valid && !Thread.currentThread().isInterrupted
        override fun query() = observed("query", receipt)
        override fun markSubmitted(): Boolean {
            if (guardOk) guardStored = true
            return observed("guard", guardOk)
        }
        override fun start(): String? {
            assertTrue("The bootstrap guard must be durable before submission", guardStored)
            submissions++
            receipt = afterStart
            observed("start", Unit)
            startError?.let { throw it }
            return startFailure
        }
        override fun bind(receipt: UltraBootSdkGateway.Receipt): Boolean {
            assertTrue(receipt.ready)
            return observed("bind", bindOk)
        }
        fun run() = UltraBootSdkGateway.connect(guardStored, this)
    }

    @Test fun bootstrapIsGuardedAndTheResidentReceiptIsBoundExactlyOnce() {
        val port = Port()
        val result = port.run()
        assertTrue(result.bound)
        assertTrue(result.submitted)
        assertEquals(listOf("query", "guard", "start", "query", "bind"), port.calls)
        assertTrue(port.run().bound)
        assertEquals(1, port.submissions)
    }

    @Test fun anAlreadyReadyGatewayNeedsNoNewSubmission() {
        val port = Port().apply { receipt = ready() }
        assertTrue(port.run().bound)
        assertFalse(port.guardStored)
        assertEquals(listOf("query", "bind"), port.calls)
        assertEquals(0, port.submissions)
    }

    @Test fun lostStartReplyOnlyQueriesAndNeverReplays() {
        val port = Port().apply { startError = IllegalStateException("reply lost") }
        assertTrue(port.run().bound)
        assertTrue(port.run().bound)
        assertEquals(1, port.submissions)
    }

    @Test fun anEmptyGatewayRecoversAPreviouslyUnstartedSubmission() {
        val port = Port().apply { afterStart = empty() }
        assertEquals("SDK_GATEWAY_NOT_READY", port.run().reason)
        val reloaded = Port().apply { guardStored = port.guardStored; afterStart = ready() }
        assertTrue(reloaded.run().bound)
        assertEquals(1, port.submissions)
        assertEquals(1, reloaded.submissions)
        assertEquals(listOf("query", "start", "query", "bind"), reloaded.calls)
        assertTrue(reloaded.run().bound)
        assertEquals(1, reloaded.submissions)
    }

    @Test fun aFailedStartKeepsItsCauseAndOnlyAnEmptyReceiptPermitsLaterRecovery() {
        val port = Port().apply {
            afterStart = empty()
            startFailure = "BOOT_SDK_DYNAMIC_CONFIG:-95"
        }
        assertEquals("BOOT_SDK_DYNAMIC_CONFIG:-95", port.run().reason)
        assertEquals(1, port.submissions)
        port.receipt = UltraBootSdkGateway.Receipt(boot, 2, 0, 0, "0x0000000000000000")
        assertEquals("SDK_BOOTSTRAP_RECONCILIATION_REQUIRED", port.run().reason)
        assertEquals(1, port.submissions)
        port.receipt = empty()
        port.afterStart = ready()
        assertTrue(port.run().bound)
        assertEquals(2, port.submissions)
    }

    @Test fun aLostStartReplyWithoutAResidentReceiptNeverReplaysInTheSameConnection() {
        val port = Port().apply {
            afterStart = null
            startFailure = "SDK_BOOTSTRAP_COMMAND_FAILED:124"
        }
        assertEquals("SDK_BOOTSTRAP_RECONCILIATION_REQUIRED:SDK_BOOTSTRAP_COMMAND_FAILED:124", port.run().reason)
        assertEquals(1, port.submissions)
        assertEquals("SDK_GATEWAY_UNAVAILABLE", port.run().reason)
        assertEquals(1, port.submissions)
    }

    @Test fun bootstrapFailureDiagnosticsContainOnlyTheStageAndNumericExit() {
        assertEquals("BOOT_SDK_ABI:prepare_creds", UltraBootSdkGateway.commandFailure(1,
            "BOOT_SDK_ABI:prepare_creds\n$base $diagnostic"))
        assertEquals("MODULE_POLICY_LAYOUT_ABI_MISSING:vmalloc", UltraBootSdkGateway.commandFailure(1,
            "{\"event\":\"result\",\"status\":\"error\",\"message\":\"MODULE_POLICY_LAYOUT_ABI_MISSING:vmalloc\"}"))
        assertEquals("BOOT_SDK_DYNAMIC_CONFIG:-95", UltraBootSdkGateway.commandFailure(1,
            "BOOT_SDK_DYNAMIC_CONFIG:-95"))
        assertEquals("BOOT_SDK_CONFIG_CAPTURE:-95", UltraBootSdkGateway.commandFailure(1,
            "BOOT_SDK_CONFIG_CAPTURE:-95"))
        assertEquals("BOOT_SDK_CONFIG_PROBE:-22", UltraBootSdkGateway.commandFailure(1,
            "BOOT_SDK_CONFIG_PROBE:-22"))
        assertEquals("BOOT_SDK_CONFIG_CAPTURE:-95", UltraBootSdkGateway.commandFailure(1,
            "BOOT_SDK_CONFIG_CAPTURE:-95"))
        assertEquals("BOOT_SDK_CONFIG_PROBE:-22", UltraBootSdkGateway.commandFailure(1,
            "BOOT_SDK_CONFIG_PROBE:-22"))
        assertEquals("SDK_BOOTSTRAP_COMMAND_FAILED:124", UltraBootSdkGateway.commandFailure(124,
            "$base $diagnostic"))
    }

    @Test fun aMissingReplyAfterSubmissionKeepsTheDurableGuard() {
        val port = Port().apply { afterStart = null }
        assertEquals("SDK_BOOTSTRAP_RECONCILIATION_REQUIRED", port.run().reason)
        assertTrue(port.guardStored)
        assertEquals(1, port.submissions)
    }

    @Test fun missingQueryOrFailedGuardNeverStartsTheGateway() {
        val missing = Port().apply { receipt = null }
        assertEquals("SDK_GATEWAY_UNAVAILABLE", missing.run().reason)
        assertFalse(missing.guardStored)
        val failedGuard = Port().apply { guardOk = false }
        assertEquals("SDK_BOOTSTRAP_GUARD_STORE_FAILED", failedGuard.run().reason)
        assertEquals(0, failedGuard.submissions)
    }

    @Test fun initializingAndFailedStatesRemainObservationsOnly() {
        for (state in listOf(2, 3)) {
            val port = Port().apply {
                guardStored = true
                receipt = UltraBootSdkGateway.Receipt(boot, state, if (state == 3) -12 else 0,
                    0, "0x0000000000000000")
            }
            assertFalse(port.run().bound)
            assertEquals(0, port.submissions)
            assertFalse(port.calls.contains("bind"))
        }
    }

    @Test fun sessionChangesAtEachBoundaryStopFurtherWork() {
        for (boundary in listOf("query", "guard", "start", "bind")) {
            val port = Port().apply { after = { if (it == boundary) valid = false } }
            assertEquals(boundary, "SESSION_CHANGED", port.run().reason)
            if (boundary in listOf("query", "guard")) assertEquals(0, port.submissions)
            if (boundary != "bind") assertFalse(port.calls.contains("bind"))
        }
    }

    @Test fun anUnverifiedBindDoesNotClaimTheSdkIsConnected() {
        val port = Port().apply { bindOk = false }
        assertEquals("SDK_BIND_UNVERIFIED", port.run().reason)
        assertTrue(port.guardStored)
        assertEquals(1, port.submissions)
    }

    @Test fun interruptedStartNeverBindsOrReplays() {
        val port = Port().apply { startError = InterruptedException("cancelled") }
        try {
            assertEquals("SESSION_CHANGED", port.run().reason)
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(port.guardStored)
            assertFalse(port.calls.contains("bind"))
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun parsedReadinessSeparatesTheChannelFromTheEnvironmentAbi() {
        val basic = requireNotNull(parse(json().put("capabilities", 3)))
        assertTrue(basic.ready)
        assertFalse(basic.environment)
        assertTrue(requireNotNull(parse(json())).environment)
        assertFalse(basic.toString().contains(diagnostic))
        assertFalse(basic.toString().contains(base))
    }

    @Test fun malformedForeignAndDuplicateReceiptsAreRejected() {
        val line = "MAPLESU_BOOT_SDK:${json()}"
        assertNull(UltraBootSdkGateway.parse("$line\n$line", boot))
        assertNull(UltraBootSdkGateway.parse(line, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
        assertNull(UltraBootSdkGateway.parse("x".repeat(16_385), boot))
        assertNull(UltraBootSdkGateway.parse("MAPLESU_BOOT_SDK:{}", boot))
        for (record in listOf(json().put("v", 2), json().put("state", 4), json().put("capabilities", 15),
            json().put("capabilities", 4), json().put("kernelBase", "0x1234"),
            json().put("state", 3).put("error", -12))) {
            assertNull(parse(record))
        }
    }

    @Test fun kernelBaseReadbackMustBeUniqueAndMatchTheControlReceipt() {
        assertTrue(UltraBootSdkGateway.kernelBaseMatches("Output addr: $base\n", base))
        assertFalse(UltraBootSdkGateway.kernelBaseMatches("Output addr: $base\nOutput addr: $base", base))
        assertFalse(UltraBootSdkGateway.kernelBaseMatches("Output addr: 0xffff800000001000", base))
        assertFalse(UltraBootSdkGateway.kernelBaseMatches(null, base))
    }

    @Test fun kernelBaseEvidenceMustContainOneCanonicalKernelAddress() {
        assertEquals("0xffff800000001000", UltraBootSdkGateway.kernelBase("Output addr: 0xFFFF800000001000\n"))
        for (invalid in listOf(null, "", "Output addr: 0x0000000000000000", "Output addr: 0x1234",
            "Output addr: 0xffff800000001000\nOutput addr: 0xffff800000001000")) {
            assertEquals(null, UltraBootSdkGateway.kernelBase(invalid))
        }
    }

}
