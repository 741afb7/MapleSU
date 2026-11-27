package dev.maplesu.manager.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDeploymentRequestsTest {
    @Test fun publicationRetriesBackOffAndStopAfterThreeAttempts() {
        val first = requireNotNull(RuntimePublicationRetry(7).next(7))
        val second = requireNotNull(first.next(7))
        val third = requireNotNull(second.next(7))
        org.junit.Assert.assertEquals(listOf(2_000L, 4_000L, 8_000L),
            listOf(first.delayMillis, second.delayMillis, third.delayMillis))
        org.junit.Assert.assertNull(third.next(7))
    }

    @Test fun aNewExplicitPublicationCancelsThePreviousRetryGeneration() {
        org.junit.Assert.assertNull(RuntimePublicationRetry(7).next(8))
        org.junit.Assert.assertNull(RuntimePublicationRetry(7, 2).next(8))
    }

    @Test fun publicationRecoverySkipsEnvironmentAndGrantMutationsAndChecksSessionIdentity() {
        val source = listOf(java.io.File("src/main/java/dev/maplesu/manager/runtime/RuntimeDeployment.kt"),
            java.io.File("manager/src/main/java/dev/maplesu/manager/runtime/RuntimeDeployment.kt"))
            .first { it.isFile }.readText()
        assertTrue(source.contains("prepareEnvironment = expectedSession == null"))
        assertTrue(source.contains("expected.current()"))
        assertTrue(source.contains("publicationSession(context)?.let(expected::sameOwner) == true"))
        assertTrue(source.contains("changed || superseded || completions.isNotEmpty()"))
        assertTrue(source.contains("runtime.origin, runtime.attemptId, ModeRuntime.active"))
        assertTrue(source.contains("SuEntryMode.read(context), ModeRuntime.activation"))
        assertTrue(source.contains("UltraSdkCredentials.captureOwnerMarker(context)"))
        assertFalse(source.contains("sdk.key"))
        assertFalse(source.contains("sdkKey"))
        assertTrue(source.contains("publicationGeneration.incrementAndGet()"))
        assertFalse(source.contains("reconcilePending("))
        assertFalse(source.contains("mirror.apply("))
    }

    @Test fun foregroundRecoveryUsesTheCurrentSessionAndSkipsAnAlreadyVerifiedPublication() {
        val source = listOf(java.io.File("src/main/java/dev/maplesu/manager/runtime/RuntimeDeployment.kt"),
            java.io.File("manager/src/main/java/dev/maplesu/manager/runtime/RuntimeDeployment.kt"))
            .first { it.isFile }.readText()
        val resume = source.substringAfter("fun resumeBootSdk(").substringBefore("private fun start(")
        assertTrue(resume.contains("publicationSession(application) ?: return"))
        assertTrue(resume.contains("HookSuCompatibility.traditionalReady(application)"))
        assertTrue(resume.contains("publicationGeneration.incrementAndGet()), session)"))
        assertFalse(resume.contains("UltraEnvironmentAutoSetup.ensure"))
    }

    @Test fun oneIdleRequestSchedulesOnlyOneWorker() {
        val requests = RuntimeDeploymentRequests()
        assertTrue(requests.request())
        val generation = requests.snapshot()
        assertFalse(requests.finish(generation))
    }

    @Test fun grantDuringDeploymentRequiresAnotherPassWithoutACallback() {
        val requests = RuntimeDeploymentRequests()
        assertTrue(requests.request())
        val generation = requests.snapshot()
        assertFalse(requests.request())
        assertFalse(requests.request())
        assertTrue(requests.finish(generation))
        assertTrue(requests.request())
        assertFalse(requests.finish(requests.snapshot()))
    }

    @Test fun requestsBeforeTheSnapshotAreIncludedInTheCurrentPass() {
        val requests = RuntimeDeploymentRequests()
        assertTrue(requests.request())
        assertFalse(requests.request())
        assertFalse(requests.finish(requests.snapshot()))
    }

    @Test fun grantAfterTheWorkerFinishesCanScheduleItself() {
        val requests = RuntimeDeploymentRequests()
        assertTrue(requests.request())
        assertFalse(requests.finish(requests.snapshot()))
        assertTrue(requests.request())
        assertFalse(requests.finish(requests.snapshot()))
    }
}
