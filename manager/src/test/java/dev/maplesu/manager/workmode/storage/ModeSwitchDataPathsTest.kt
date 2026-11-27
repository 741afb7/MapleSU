package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.workmode.Hashes
import dev.maplesu.manager.workmode.WorkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeSwitchDataPathsTest {
    private val key16 = "a1b2c3d4e5f60718"
    private val rootKey = key16 + "9".repeat(32)

    @Test fun usesOnlyTheVerifiedLocatorAndNeverDerivesASecretDirectory() {
        val source = ModeSwitchDataPaths.fromLocal(ultraDirectory = "/data/$key16")
        assertEquals(listOf("/data/$key16"), source.entries(WorkMode.LITE).map { it.livePath })
        assertTrue(source.entries(WorkMode.KSU).isEmpty())
        assertTrue(ModeSwitchDataPaths.fromLocal().entries(WorkMode.LITE).isEmpty())
    }

    @Test
    fun keepsKnownPreviousPublicationAndPersistedRuntimeDirectoryAndDeduplicatesThem() {
        val source = ModeSwitchDataPaths.fromLocal(
            ultraDirectory = "/data/$key16",
            previousPublication = "/data/$key16",
            runtimeDirectory = "/data/0123456789abcdef",
        )
        assertEquals(listOf("/data/$key16", "/data/0123456789abcdef"), source.entries(WorkMode.LITE).map { it.livePath })
        val out = MoveSet.plan("out", WorkMode.LITE, WorkMode.KSU,
            dynamicFrom = source.entries(WorkMode.LITE), dynamicTo = source.entries(WorkMode.KSU))
        val back = MoveSet.plan("back", WorkMode.KSU, WorkMode.LITE,
            dynamicFrom = source.entries(WorkMode.KSU), dynamicTo = source.entries(WorkMode.LITE))
        assertEquals(out.outbound.filter { it.livePath.startsWith("/data/$key16") },
            back.inbound.filter { it.livePath.startsWith("/data/$key16") })
    }

    @Test
    fun registeredExternalTreesStayWithTheirDeclaredModeAndMount() {
        val source = ModeSwitchDataPaths.fromLocal(registeredExternal = mapOf(
            WorkMode.LITE to listOf("/data/adb/custom-ultra", "/data/adb/custom-ultra"),
            WorkMode.KSU to listOf("/metadata/custom-normal"),
        ))
        assertEquals(MoveMount.SAME_AS_STORE, source.entries(WorkMode.LITE).single().mount)
        assertEquals(WorkMode.LITE, source.entries(WorkMode.LITE).single().owner)
        assertEquals(MoveMount.CROSS_MOUNT_COPY, source.entries(WorkMode.KSU).single().mount)
        assertEquals(WorkMode.KSU, source.entries(WorkMode.KSU).single().owner)
    }

    @Test
    fun rejectsInvalidPublishedAndCredentialPathsWithoutQuotingTheirContents() {
        val errors = listOf(
            runCatching { ModeSwitchDataPaths.fromLocal(ultraDirectory = "/data/$key16/injected") }.exceptionOrNull(),
            runCatching { ModeSwitchDataPaths.fromLocal(ultraDirectory = "/".repeat(48)) }.exceptionOrNull(),
            runCatching { ModeSwitchDataPaths.fromLocal(previousPublication = "/data/$key16/../adb") }.exceptionOrNull(),
            runCatching { ModeSwitchDataPaths.fromLocal(runtimeDirectory = "/data/adb") }.exceptionOrNull(),
        )
        assertTrue(errors.all { it is IllegalArgumentException })
        assertTrue(errors.all { !it!!.message.orEmpty().contains(rootKey) && !it.message.orEmpty().contains(key16) })
    }

    @Test
    fun rejectsProtectedTreesStaticOverlapsAndTraversalInExternalRegistration() {
        for (path in listOf(MoveSet.CONTROL_ROOT, MoveSet.INSTALLER_ROOT, "/data/adb/modules/test",
            "/data/adb/custom/../modules", "/data/adb/custom/", "/metadata/vold", "/system/custom")) {
            val failure = runCatching {
                ModeSwitchDataPaths.fromLocal(registeredExternal = mapOf(WorkMode.KSU to listOf(path)))
            }.exceptionOrNull()
            assertTrue(path, failure is IllegalArgumentException)
            assertEquals("MOVE_EXTERNAL_PATH_INVALID", failure!!.message)
        }
        assertTrue(runCatching { ModeSwitchDataPaths.fromLocal(registeredExternal = mapOf(
            WorkMode.LITE to listOf("/data/adb/custom", "/data/adb/custom/nested"),
        )) }.isFailure)
    }

    @Test
    fun diagnosticsHidePrivateDirectoryNamesIncludingListAndWindowsPathBoundaries() {
        val source = "/data/$key16/bin/su [/data/$key16] C:\\tmp\\data\\$key16\\config"
        val redacted = MoveSet.redactEvidence(source)
        assertFalse(redacted, redacted.contains(key16))
        assertTrue(redacted, redacted.contains("/data/<private>"))
        assertEquals("/data/adb/modules", MoveSet.redactEvidence("/data/adb/modules"))
        val report = MoveReport(listOf("/data/$key16"), emptyList(), emptyList(),
            listOf(MoveFailure("/data/$key16", "FAILED", "source=/data/$key16/bin")))
        assertFalse(report.toString(), report.toString().contains(key16))
        assertEquals("/data/$key16", report.movedOut.single())
    }
}
