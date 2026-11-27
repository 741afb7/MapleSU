package dev.maplesu.manager.modules.webui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleWebUiPreparationTest {
    private val spec = WebUiOpenSpec("native123", WebUiRoute.ULTRA_SDK)
    private val gate = WebUiCompatibilityCheck { WebUiGateVerdict.permit("UNVERIFIED") }
    private val ready = HttpReadyProbe { HttpReadyResult.readyAt(it) }

    @Test fun recoveryRunsBeforeSdkAndKeepsRealModuleDirectory() = runBlocking {
        val order = mutableListOf<String>()
        val events = mutableListOf<WebUiLifecycleStage>()
        val result = WebUiOpenCoordinator(
            SdkOpenRunner { order += "sdk"; SdkOpenOutcome(true, 1234,
                pageOpener = WebUiPageOpener.MAPLESU, moduleDir = "/data/native/modules/native123/") },
            ready, gate, onStage = { events += it.stage },
            prepareEnvironment = { order += "prepare"; null },
            pageOpen = { order += "page"; assertEquals("/data/native/modules/native123/", it.moduleDir); null },
        ).open(spec)
        assertTrue(result.succeeded)
        assertEquals(listOf("prepare", "sdk", "page"), order)
        assertTrue(events.indexOf(WebUiLifecycleStage.ENVIRONMENT_PREPARED) < events.indexOf(WebUiLifecycleStage.SDK_OPEN))
    }

    @Test fun realPreparationFailureDoesNotSubmitModuleOrPage() = runBlocking {
        var calls = 0
        val failure = WebUiFailure(WebUiFailureReason.ENVIRONMENT_NOT_RUNNING, "SDK_GATEWAY_IDENTITY_CHANGED")
        val result = WebUiOpenCoordinator(SdkOpenRunner { calls++; SdkOpenOutcome(true, 1234) }, ready, gate,
            prepareEnvironment = { failure }, pageOpen = { calls++; null }).open(spec)
        assertFalse(result.succeeded)
        assertEquals(failure, result.failure)
        assertEquals(0, calls)
    }

    @Test fun compatibilityGateStillPrecedesEnvironmentMutation() = runBlocking {
        var preparations = 0
        val rejected = WebUiFailure(WebUiFailureReason.BLOCKED_INCOMPATIBLE, "actual mismatch")
        val result = WebUiOpenCoordinator(SdkOpenRunner { error("SDK submitted") }, ready,
            WebUiCompatibilityCheck { WebUiGateVerdict.refusal(rejected) },
            prepareEnvironment = { preparations++; null }).open(spec)
        assertEquals(rejected, result.failure)
        assertEquals(0, preparations)
    }

    @Test fun overlappingRequestsShareOnePreparationAndSubmission() = runBlocking {
        var preparations = 0
        var submissions = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = WebUiOpenCoordinator(SdkOpenRunner { submissions++; SdkOpenOutcome(true, 1234) }, ready, gate,
            prepareEnvironment = { preparations++; entered.complete(Unit); release.await(); null })
        val first = async { coordinator.open(spec) }
        entered.await()
        val second = async { coordinator.open(spec) }
        kotlinx.coroutines.yield()
        release.complete(Unit)
        assertTrue(listOf(first, second).awaitAll().all { it.succeeded })
        assertEquals(1, preparations)
        assertEquals(1, submissions)
    }

    @Test fun cancellationDuringPreparationDoesNotStartLateModule() = runBlocking {
        var submissions = 0
        val entered = CompletableDeferred<Unit>()
        val coordinator = WebUiOpenCoordinator(SdkOpenRunner { submissions++; SdkOpenOutcome(true, 1234) }, ready, gate,
            prepareEnvironment = { entered.complete(Unit); CompletableDeferred<Unit>().await(); null })
        val opening = launch { coordinator.open(spec) }
        entered.await()
        opening.cancelAndJoin()
        assertEquals(0, submissions)
    }

    @Test fun alreadyRunningPathDoesNotAddPreparation() = runBlocking {
        val events = mutableListOf<WebUiLifecycleStage>()
        val result = WebUiOpenCoordinator(SdkOpenRunner { SdkOpenOutcome(true, 1234) }, ready, gate,
            onStage = { events += it.stage }).open(spec)
        assertTrue(result.succeeded)
        assertFalse(events.contains(WebUiLifecycleStage.ENVIRONMENT_PREPARING))
    }

    @Test fun absentAndExplicitSdkOpenersKeepLegacyOwnership() = runBlocking {
        for (value in listOf(null, "sdk")) {
            var pages = 0
            val result = WebUiOpenCoordinator(SdkOpenRunner { SdkOpenOutcome(true, 1234,
                pageOpener = WebUiPageOpener.fromWire(value)) }, ready, gate,
                pageOpen = { pages++; null }).open(spec)
            assertTrue(result.succeeded)
            assertEquals(WebUiPageOpener.SDK, result.opener)
            assertEquals(0, pages)
        }
    }

    @Test fun unknownOrMalformedOpenersDoNotClaimSdkOpenedThePage() = runBlocking {
        for (value in listOf("", "null", "future", "MANAGER", 1, false, JSONObject.NULL, Any())) {
            var probes = 0
            var pages = 0
            val result = WebUiOpenCoordinator(SdkOpenRunner { SdkOpenOutcome(true, 1234,
                pageOpener = WebUiPageOpener.fromWire(value)) },
                HttpReadyProbe { probes++; HttpReadyResult.readyAt(it) }, gate,
                pageOpen = { pages++; null }).open(spec)
            assertFalse(value.toString(), result.succeeded)
            assertEquals(WebUiFailureReason.SDK_REJECTED, result.failure?.reason)
            assertEquals(0, probes)
            assertEquals(0, pages)
        }
    }

    @Test fun malformedDirectoryCannotSilentlySelectBridgeFreeWebView() {
        for (path in listOf(null, "", "/", "relative/module", "/data/../modules/native123", "/data/./modules/native123", "/data/modules/a\u0000b")) {
            val failure = ModuleWebUiPlan.failureFromSdkOpen(SdkOpenOutcome(true, 1234,
                pageOpener = WebUiPageOpener.MAPLESU, moduleDir = path))
            assertEquals(path, WebUiFailureReason.SDK_REJECTED, failure?.reason)
        }
        assertEquals("/data/native/modules/native123/", ModuleWebUiPlan.moduleDirectoryOrNull("/data/native/modules/native123/"))
    }

    @Test fun nativeFilenameIdsAndPosixDirectoriesReachTheSdkAndPageUnchanged() = runBlocking {
        for (id in listOf("模块", "a b", "a\\b", "a\nb", "a..b", "x".repeat(65))) {
            val target = ModuleWebUiPlan.moduleId32OrNull("maplesu:$id")!!
            val directory = "/data/native/modules/$id/"
            var pages = 0
            val result = WebUiOpenCoordinator(SdkOpenRunner {
                assertEquals(id, it)
                SdkOpenOutcome(true, 23456, pageOpener = WebUiPageOpener.MAPLESU, moduleDir = directory)
            }, ready, gate, pageOpen = {
                assertEquals(directory, it.moduleDir)
                pages++
                null
            }).open(WebUiOpenSpec(target, WebUiRoute.ULTRA_SDK))
            assertTrue(id, result.succeeded)
            assertEquals(1, pages)
        }
    }
}
