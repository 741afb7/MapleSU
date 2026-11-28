package com.maplesu.core.ui.viewmodel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Android VM binds identity and Root health to this session's own observation. */
class HomeUltraStartupRoutingContractTest {
    @Test fun verifiedUltraIdentityUsesOneFreshHealthReturnWithoutRepeatingTheProbe() {
        val relative = "src/main/java/com/maplesu/core/ui/viewmodel/HomeViewModel.kt"
        val source = listOf(File(relative), File("manager/$relative")).first { it.isFile }.readText().replace("\r\n", "\n")
        val collection = source.substringAfter("private suspend fun collectEnvironment(")
            .substringBefore("private suspend fun refreshInformation(")
        assertEquals(1, Regex("UltraEnvironmentProbe\\.observeFresh\\(ksuApp\\)").findAll(collection).count())
        assertTrue(collection.contains("val knownUltra = evidence?.verdict == EnvironmentVerdict.ULTRA"))
        assertTrue(collection.contains("healthObservation = observation"))
        assertTrue(collection.contains("projected.afterUltraEnvironmentHealth("))
        assertTrue(collection.contains("expectedOwner = evidence?.ultraOwnerIdentity"))
        assertFalse(collection.contains("currentSnapshot("))
        val build = source.substringAfter("private fun buildState(")
        assertFalse(build.contains("UltraEnvironmentProbe.refresh("))
        assertFalse(build.contains("UltraEnvironmentProbe.currentSnapshot("))
        assertFalse(build.contains("MapleSuRuntime.refresh("))
        val identity = build.substringAfter("val environmentMode = when (evidence?.verdict)")
            .substringBefore("val confirmedNotInstalled")
        assertTrue(identity.contains("else -> null"))
        assertFalse(identity.contains("ModeRuntime.active"))
    }
}
