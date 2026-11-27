package dev.maplesu.manager.runtime.ultra

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UltraBootSdkSessionTest {
    private val boot = "11111111-2222-4333-8444-555555555555"
    private val nextBoot = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private fun session() = requireNotNull(UltraBootSdkSession.create(boot))

    @Test fun nonSecretSubmissionGuardSurvivesReload() {
        val restored = requireNotNull(UltraBootSdkSession.parse(session().submitted().encode()))
        assertEquals(boot, restored.bootId)
        assertTrue(restored.installSubmitted)
        assertTrue(restored.current(boot))
        assertFalse(restored.current(nextBoot))
        assertFalse(restored.current(null))
        assertEquals(setOf("v", "bootId", "installSubmitted"), JSONObject(restored.encode()).keys().asSequence().toSet())
    }

    @Test fun malformedBootAndUnsupportedRecordsAreRejected() {
        assertNull(UltraBootSdkSession.create(null))
        assertNull(UltraBootSdkSession.create("invalid"))
        for (text in listOf(null, "", "garbage", "{}", "x".repeat(1025),
            JSONObject(session().encode()).put("v", 1).toString(),
            JSONObject(session().encode()).apply { remove("installSubmitted") }.toString())) {
            assertNull(UltraBootSdkSession.parse(text))
        }
    }

    @Test fun submittedObservationDoesNotChangeTheBootIdentity() {
        val before = session()
        assertFalse(before.installSubmitted)
        assertTrue(before.submitted().current(boot))
        assertEquals(before.bootId, before.submitted().bootId)
    }
}
