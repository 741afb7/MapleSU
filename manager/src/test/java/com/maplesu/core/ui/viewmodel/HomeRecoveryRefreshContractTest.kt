package com.maplesu.core.ui.viewmodel

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** The page and explicit refresh share the existing asynchronous Ultra recovery pipeline. */
class HomeRecoveryRefreshContractTest {
    private fun source(path: String): String = listOf(File("src/main/java/$path"), File("manager/src/main/java/$path"))
        .first { it.isFile }.readText().replace("\r\n", "\n")

    @Test fun aClaimedRefreshRequestsRecoveryBeforeCheckingEnvironmentPresence() {
        val refresh = source("com/maplesu/core/ui/viewmodel/HomeViewModel.kt")
            .substringAfter("fun refresh() {").substringBefore("private suspend fun checkEnvironmentPresence()")
        val claim = refresh.indexOf("refreshFlight.launch(viewModelScope) {")
        val recovery = refresh.indexOf("ksuApp.requestUltraRecovery()")
        val check = refresh.indexOf("checkEnvironmentPresence()")
        assertTrue(claim >= 0 && recovery > claim && check > recovery)
        assertEquals(1, "requestUltraRecovery()".toRegex(RegexOption.LITERAL).findAll(refresh).count())
    }

    @Test fun aRefreshQueuesBehindPublicationInsteadOfReturningWhenItIsLocked() {
        val viewModel = source("com/maplesu/core/ui/viewmodel/HomeViewModel.kt")
        val refresh = viewModel.substringAfter("fun refresh() {")
            .substringBefore("private suspend fun checkEnvironmentPresence()")
        assertFalse(refresh.contains("publishMutex.isLocked"))
        assertTrue(refresh.contains("probeAndPublish("))
        val probe = viewModel.substringAfter("private suspend fun probeAndPublish(")
            .substringBefore("private suspend fun refreshInformation(")
        assertTrue(probe.contains("): HomeUiState = coroutineScope {"))
        assertTrue(probe.contains("environmentMutex.withLock {"))
        assertTrue(viewModel.contains("private val environmentMutex = Mutex()"))
        assertTrue(viewModel.contains("private val publishMutex = Mutex()"))
        val publication = viewModel.substringAfter("private suspend fun publishState(")
            .substringBefore("fun refreshEntryFacts()")
        assertTrue(publication.contains("publishMutex.withLock"))
        val ownerGuard = publication.indexOf("if (!owner.isCurrent(environmentOwner)) return@withLock")
        assertTrue(ownerGuard >= 0 && publication.indexOf("_uiState.update") > ownerGuard)
    }

    @Test fun aPageReturnRefreshesAfterTheFirstVisibleEntry() {
        val page = source("com/maplesu/core/ui/screen/home/HomeScreen.kt")
        assertTrue(page.contains("var initialPageEntryHandled by rememberSaveable { mutableStateOf(false) }"))
        val entry = page.substringAfter("LaunchedEffect(isCurrentPage) {")
            .substringBefore("LifecycleResumeEffect(Unit)")
        assertTrue(entry.contains("if (isCurrentPage) {"))
        assertTrue(entry.contains("if (initialPageEntryHandled) viewModel.refresh()"))
        assertTrue(entry.contains("viewModel.refreshEntryFacts()"))
        assertTrue(entry.indexOf("viewModel.refresh()") < entry.indexOf("initialPageEntryHandled = true"))
    }

    @Test fun resumeAndPageEntryKeepIndependentFirstRunGuards() {
        val page = source("com/maplesu/core/ui/screen/home/HomeScreen.kt")
        val resume = page.substringAfter("LifecycleResumeEffect(Unit) {")
            .substringBefore("onPauseOrDispose")
        assertTrue(resume.contains("initialResumeHandled.value && latestIsCurrentPage"))
        assertTrue(resume.contains("viewModel.refresh()"))
        assertTrue(resume.contains("viewModel.refreshEntryFacts()"))
        assertFalse(resume.contains("initialPageEntryHandled"))
    }
}
