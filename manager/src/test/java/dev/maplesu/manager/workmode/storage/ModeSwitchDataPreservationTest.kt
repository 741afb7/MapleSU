package dev.maplesu.manager.workmode.storage

import dev.maplesu.manager.workmode.WorkMode
import dev.maplesu.manager.workmode.switch.FileSwitchJournal
import dev.maplesu.manager.workmode.switch.FileSwitchMoveStage
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModeSwitchDataPreservationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val roots get() = MovePathRoots(File(tmp.root, "live"), File(tmp.root, "store"))
    private val journal get() = FileSwitchJournal(File(tmp.root, "journal.jsonl"))

    private fun write(file: File, content: String) {
        file.parentFile!!.mkdirs()
        file.writeText(content)
    }

    @Test
    fun populatedModesRoundTripWhileTheInstallerLockRemainsHeld() {
        val roots = roots
        val key16 = "a1b2c3d4e5f60718"
        val paths = ModeSwitchDataPaths.fromLocal(ultraDirectory = "/data/$key16",
            previousPublication = "/data/${"7".repeat(16)}")
        val stage = FileSwitchMoveStage(roots, paths.entries(WorkMode.LITE), paths.entries(WorkMode.KSU))
        paths.entries(WorkMode.LITE).forEach { write(roots.live("${it.livePath}/state"), it.livePath) }
        val lock = roots.live("${MoveSet.INSTALLER_ROOT}/install.lock")
        write(lock, "lock")
        write(roots.live("/data/adb/maplesu/settings.json"), "ultra-settings")
        write(roots.live("/data/adb/maplesu-su-policy/grants.json"), "ultra-grants")
        write(roots.live("/data/adb/maplesu-policy.lock"), "policy-lock")
        write(roots.live("/data/adb/modules_update/demo/module.prop"), "ultra-update")
        write(roots.live("/data/adb/modules/demo/config"), "ultra-module")
        val moduleLink = roots.live("/data/adb/modules/demo/current")
        Files.createSymbolicLink(moduleLink.toPath(), File("config").toPath())
        val normalModules = roots.store(MoveSet.storePathFor(WorkMode.KSU, "/data/adb/modules"))
        write(File(normalModules, "normal/config"), "normal-module")
        val metadata = roots.store(MoveSet.storePathFor(WorkMode.KSU, "/metadata/ksu"))
        write(File(metadata, "state"), "normal-metadata")
        val journal = journal

        FileChannel.open(lock.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val outward = stage.plan("ultra-to-normal", WorkMode.LITE, WorkMode.KSU)
                val moved = stage.moveOut(outward, journal)
                assertTrue(moved.toString(), moved.ok)
                assertTrue(lock.isFile)
                val incoming = stage.moveIn(outward, journal)
                assertTrue(incoming.toString(), incoming.ok)
                val materialized = stage.materialize(outward, journal)
                assertTrue(materialized.toString(), materialized.ok)
                assertEquals("ultra-module", roots.live("/data/adb/modules/demo/config").readText())
                assertEquals("normal-module", File(normalModules, "normal/config").readText())
                assertEquals("normal-metadata", roots.live("/metadata/ksu/state").readText())

                paths.entries(WorkMode.LITE).forEach {
                    assertFalse(roots.live(it.livePath).exists())
                    assertEquals(it.livePath, File(roots.store(MoveSet.storePathFor(it.owner, it.livePath)), "state").readText())
                }
                val returnStage = FileSwitchMoveStage(roots, paths.entries(WorkMode.KSU), paths.entries(WorkMode.LITE))
                val back = returnStage.plan("normal-to-ultra", WorkMode.KSU, WorkMode.LITE)
                val movedOut = returnStage.moveOut(back, journal)
                assertTrue(movedOut.toString(), movedOut.ok)
                val movedIn = returnStage.moveIn(back, journal)
                assertTrue(movedIn.toString(), movedIn.ok)
                val restored = returnStage.materialize(back, journal)
                assertTrue(restored.toString(), restored.ok)
                assertTrue(channel.isOpen)
                assertEquals(4L, channel.size())
                assertEquals("ultra-settings", roots.live("/data/adb/maplesu/settings.json").readText())
                assertEquals("ultra-grants", roots.live("/data/adb/maplesu-su-policy/grants.json").readText())
                assertEquals("policy-lock", roots.live("/data/adb/maplesu-policy.lock").readText())
                assertEquals("ultra-update", roots.live("/data/adb/modules_update/demo/module.prop").readText())
                assertEquals("ultra-module", roots.live("/data/adb/modules/demo/config").readText())
                assertTrue(Files.isSymbolicLink(moduleLink.toPath()))
                assertEquals("config", Files.readSymbolicLink(moduleLink.toPath()).toString())
                assertEquals("normal-module", File(normalModules, "normal/config").readText())
                assertEquals("normal-metadata", File(metadata, "state").readText())
                paths.entries(WorkMode.LITE).forEach {
                    assertEquals(it.livePath, roots.live("${it.livePath}/state").readText())
                    assertFalse(roots.store(MoveSet.storePathFor(it.owner, it.livePath)).exists())
                    assertFalse(outward.manifest.readText().contains(it.livePath))
                    assertFalse(back.manifest.readText().contains(it.livePath))
                    assertFalse(File(tmp.root, "journal.jsonl").readText().contains(it.livePath))
                    assertFalse(movedIn.toString().contains(it.livePath))
                }
            }
        }
        assertEquals("lock", lock.readText())
    }

    @Test
    fun privateDirectoryFailuresAreRedactedInResultsManifestAndJournal() {
        val key16 = "a1b2c3d4e5f60718"
        val path = "/data/$key16"
        val paths = ModeSwitchDataPaths.fromLocal(ultraDirectory = path)
        val stage = FileSwitchMoveStage(roots, paths.entries(WorkMode.LITE), emptyList())
        write(roots.live("$path/config"), "live")
        write(File(roots.store(MoveSet.storePathFor(WorkMode.LITE, path)), "config"), "store")
        val plan = stage.plan("private-failure", WorkMode.LITE, WorkMode.KSU)
        val result = stage.moveOut(plan, journal)
        assertFalse(result.ok)
        assertFalse(result.toString(), result.toString().contains(key16))
        assertFalse(plan.manifest.readText().contains(key16))
        assertFalse(File(tmp.root, "journal.jsonl").readText().contains(key16))
        val verify = stage.verify(plan)
        assertFalse(verify.ok)
        assertFalse(verify.detail, verify.detail.contains(key16))
    }

    @Test
    fun preservedMergeRejectsAConflictingChildWithoutOverwritingIt() {
        val entry = MoveSet.LITE_MOVE_SET.first { it.livePath == "/data/adb/maplesu" }
        write(roots.live("${MoveSet.INSTALLER_ROOT}/install.lock"), "held")
        write(roots.live("${entry.livePath}/config"), "live")
        write(File(roots.store(MoveSet.storePathFor(entry.owner, entry.livePath)), "config"), "stored")
        val plan = MovePlan("conflict", WorkMode.LITE, WorkMode.KSU, listOf(entry), emptyList(), emptyMap())
        val result = FileMoveExecutor(roots).execute(plan, journal)
        assertFalse(result.ok)
        assertEquals(MoveSet.Errors.DEST_EXISTS, result.failures.single().code)
        assertEquals("live", roots.live("${entry.livePath}/config").readText())
        assertEquals("stored", File(roots.store(MoveSet.storePathFor(entry.owner, entry.livePath)), "config").readText())
    }

    @Test
    fun rollbackMergesOutwardChildrenBackAroundTheInstallerLock() {
        val entry = MoveSet.LITE_MOVE_SET.first { it.livePath == "/data/adb/maplesu" }
        write(roots.live("${MoveSet.INSTALLER_ROOT}/install.lock"), "held")
        write(roots.live("${entry.livePath}/config"), "config")
        val executor = FileMoveExecutor(roots)
        val plan = MovePlan("rollback", WorkMode.LITE, WorkMode.KSU, listOf(entry), emptyList(), emptyMap())
        val journal = journal
        val out = executor.execute(plan, journal)
        assertTrue(out.toString(), out.ok)
        val rolledBack = executor.rollback(plan, journal)
        assertTrue(rolledBack.toString(), rolledBack.ok)
        assertEquals("held", roots.live("${MoveSet.INSTALLER_ROOT}/install.lock").readText())
        assertEquals("config", roots.live("${entry.livePath}/config").readText())
        assertFalse(roots.store(MoveSet.storePathFor(entry.owner, entry.livePath)).exists())
    }
}
