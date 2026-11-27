package dev.maplesu.manager.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SuPublicationTest {
    private fun withRecord(value: String, check: (File) -> Unit) {
        val record = File.createTempFile("maplesu-published-directory", ".txt")
        try {
            record.writeText(value)
            check(record)
            assertEquals("reading a locator must leave its record intact", value, record.readText())
        } finally {
            record.delete()
        }
    }

    @Test fun readsTheDirectorySavedByThePreviousProductionPublisher() {
        withRecord("/data/0123456789abcdef\n") {
            assertEquals("/data/0123456789abcdef", SuPublication.previousDirectory(it))
        }
    }

    @Test fun readsTheCurrentSuClientSubtree() {
        withRecord("/data/aB01cD23eF45gH67/su-client") {
            assertEquals("/data/aB01cD23eF45gH67/su-client", SuPublication.previousDirectory(it))
        }
    }

    @Test fun rejectsOtherPathShapesAndShellOrTraversalContent() {
        listOf("", "/data/0123456789abcde", "/data/0123456789abcdef0",
            "/data/0123456789abcdeg", "/data/0123456789ABCDEF",
            "/data/0123456789abcdef/", "/data/0123456789abcdef/su",
            "/data/0123456789abcdef/su-client/", "/data/0123456789abcdef/../su-client",
            "/data//0123456789abcdef", "/data/maplesu-su", "/data/adb/ksu",
            "data/0123456789abcdef", "/data/0123456789abcdef;id",
            "/data/0123456789abcdef\n/data/fedcba9876543210", "/data/0123456789abcdef\u0000"
        ).forEach { value ->
            withRecord(value) { assertNull("unexpected locator: $value", SuPublication.previousDirectory(it)) }
        }
    }

    @Test fun missingRecordAndDirectoriesDoNotBecomeLocators() {
        val directory = java.nio.file.Files.createTempDirectory("maplesu-record-test").toFile()
        try {
            assertNull(SuPublication.previousDirectory(File(directory, "absent")))
            assertNull(SuPublication.previousDirectory(directory))
        } finally {
            directory.delete()
        }
    }

    @Test fun aSavedLegacyLocatorStillRequiresRootAndFrameworkReadiness() {
        withRecord("/data/0123456789abcdef") { record ->
            val directory = checkNotNull(SuPublication.previousDirectory(record))
            for (code in listOf(13, 14, 0)) {
                var calls = 0
                val failure = assertThrows(RootServiceBootstrap.Failure::class.java) {
                    RootServiceBootstrap.launch("start-service", listOf(
                        RootServiceBootstrap.Candidate("maplesu-su:previous") { command ->
                            calls++
                            assertTrue(command.contains("/system/bin/id -u"))
                            assertTrue(command.contains("service check activity"))
                            assertEquals("/data/0123456789abcdef/su", "$directory/su")
                            RootServiceBootstrap.Result(code, "")
                        }
                    ))
                }
                assertEquals("probe", failure.phase)
                assertEquals(1, calls)
            }
        }
    }
}
