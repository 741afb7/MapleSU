package dev.maplesu.manager.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guards for the constraints this work must never break.
 *
 * <p>They run on the host, so a violation is caught by `testDebugUnitTest` long before anyone
 * installs a build on a device. Each assertion names the constraint it protects; a failure
 * here means a change reintroduced something the product explicitly refuses.
 */
class ConstraintGuardTest {
    private val root: File by lazy {
        var directory: File? = File(".").absoluteFile
        while (directory != null && !File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
        }
        checkNotNull(directory) { "repository root not found from ${File(".").absolutePath}" }
    }

    private fun read(relative: String): String {
        val file = File(root, relative)
        assertTrue("missing source file: $relative", file.isFile)
        return file.readText()
    }

    /** The client must not leave any diagnostic trail on the device. */
    @Test fun clientKeepsNoDeviceSideLog() {
        val source = read("runtime/src/su_main.cpp")
        assertFalse("su_main.cpp writes a device-side log again",
            source.contains("/data/local/tmp"))
        assertFalse("su_main.cpp gained a debug probe again", source.contains("void diag("))
    }

    /** Zero mounts is a hard requirement: the escalation path must not mount anything. */
    @Test fun escalationPathMountsNothing() {
        listOf("runtime/src/su_main.cpp", "runtime/src/exec_main.cpp").forEach { relative ->
            val source = read(relative)
            val mountCalls = Regex("""(?<![A-Za-z0-9_])(mount|umount2?)\s*\(""").findAll(source).count()
            assertTrue("$relative calls mount/umount $mountCalls time(s)", mountCalls == 0)
            assertFalse("$relative uses MS_BIND", source.contains("MS_BIND"))
        }
    }

    /** A second implant stacks a side library and has corrupted targets, so wire() refuses. */
    @Test fun wiringRefusesASecondImplant() {
        val source = read("manager/src/main/java/dev/maplesu/manager/runtime/SuPathWiring.kt")
        assertTrue("wire() lost its already-implanted guard",
            source.contains("ALREADY_IMPLANTED"))
        assertTrue("wire() no longer counts existing side libraries",
            source.contains("sideLibraryCount("))
    }

    /** Shared and per-application entries both use the same unmodified client. */
    @Test fun publicationShipsACredentialFreeSharedClient() {
        val source = read("manager/src/main/java/dev/maplesu/manager/runtime/SuPublication.kt")
        assertFalse("shared publication embeds the credential again", source.contains("MPLSUKEY"))
        assertTrue("shared client is no longer 0711", source.contains("chmod 0711"))
        assertTrue("uid mapping is no longer a per-uid symlink",
            source.contains("ln -sfn"))
    }

    /** The four delivery states plus the install-mismatch state must stay reachable. */
    @Test fun displayStatesRemainTruthful() {
        val source = read("manager/src/main/java/dev/maplesu/manager/runtime/SuDelivery.kt")
        listOf("NOT_AUTHORIZED", "DELIVERED", "PENDING_WIRING", "WIRED", "STALE").forEach {
            assertTrue("state $it disappeared", source.contains("    $it,"))
        }
        // WIRED is PATH-only; the OR belongs to reconcile's re-implant guard.
        assertTrue("WIRED no longer keys on the PATH signal", source.contains("probe.pathWired -> State.WIRED"))
    }

    /**
     * Granting, revoking and the master switch must not start the resident executor: those
     * answers take milliseconds, so a root process kept alive for them is precisely what the
     * non-resident requirement forbids.
     */
    @Test fun authorizationUsesTheOneShotToolOnly() {
        val source = read("manager/src/main/java/dev/maplesu/manager/runtime/authorization/ManagerAuthorizations.kt")
        assertFalse("authorization consults the resident executor again",
            source.contains("NativeSuDaemon"))
        assertTrue("authorization stopped using the one-shot tool",
            source.contains("DirectPolicy.hasGrantStrict"))
    }

    /** No production consumer may recreate the retired client trailer or stdin frame. */
    @Test fun deliveredClientUsesCurrentCallerAdmission() {
        val client = read("runtime/src/su_main.cpp")
        assertFalse(client.contains("DELIVERED_MAGIC"))
        val delivery = read("manager/src/main/java/dev/maplesu/manager/runtime/SuDelivery.kt")
        assertTrue("client bootstrap needs its installed image path", delivery.contains("ln -sfn"))
        for (name in listOf("SuDelivery", "SuPublication", "SuPathWiring")) {
            val source = read("manager/src/main/java/dev/maplesu/manager/runtime/$name.kt")
            assertFalse("$name reads the retired secret store", source.contains("MapleSuCredentialStore"))
            assertFalse("$name recreates the secret trailer", source.contains("MPLSUKEY"))
            assertFalse("$name recreates the secret frame", source.contains("byteArrayOf(0, 0, 0, 48)"))
        }
        val publication = read("manager/src/main/java/dev/maplesu/manager/runtime/SuPublication.kt")
        assertFalse("Pending must not remove a previous installation", publication.contains("rm -rf '\$previous'"))
        assertFalse("a missing locator must stay unknown", publication.contains("/data/maplesu-su"))
    }

    @Test fun deliveredDigestBindsTheCompleteRawImage() {
        val image = File.createTempFile("maplesu-raw-client", ".elf")
        try {
            val method = SuDelivery::class.java.getDeclaredMethod("deliveredImageSha", File::class.java)
                .apply { isAccessible = true }
            val bytes = ByteArray(96) { it.toByte() }
            image.writeBytes(bytes)
            val first = method.invoke(SuDelivery, image) as String
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(expected, first)
            bytes[bytes.lastIndex] = 7
            image.writeBytes(bytes)
            assertNotEquals("the old trailer region is part of the actual file", first,
                method.invoke(SuDelivery, image))
        } finally {
            image.delete()
        }
    }
}
