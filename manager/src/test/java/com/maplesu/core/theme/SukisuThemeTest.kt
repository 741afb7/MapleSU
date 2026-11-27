package com.maplesu.core.theme

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SukisuThemeTest {
    @get:Rule val temporary = TemporaryFolder()

    /** Independent producer using the published SukiSU file format. */
    private fun fixture(json: String, vararg entries: Pair<String, ByteArray>): ByteArray {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { output ->
            (listOf("theme.json" to json.toByteArray()) + entries).forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
        val key = "41dc174e5096b4ee93311cc892b132e21961012b14cf2179540f203ca72ab9c1"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val iv = ByteArray(12) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return "SKTH".toByteArray() + iv + cipher.doFinal(zip.toByteArray())
    }

    @Test fun plainFormatKeepsExtensionlessAssetsColorsAndUnknownFields() {
        val bytes = byteArrayOf(1, 3, 5, 7)
        val (raw, files) = ThemePackageReader.read(ByteArrayInputStream(fixture(
            """{"version":2,"themeMode":2,"miuixMonet":false,"keyColor":-123,"hasBackground":true,"hasCardBackground":true,"customBackgroundScrimPercent":25,"future":{"x":7}}""",
            "background" to bytes, "card_background" to bytes)), temporary.newFolder())
        val (j, _) = ThemeDocument.normalize(raw, files)
        assertEquals(-123, j.getInt("finasu_keyColor"))
        assertEquals(7, j.getJSONObject("sukisu.original").getJSONObject("future").getInt("x"))
        assertEquals(.25, j.getDouble("backgroundDim"), .001)
        assertEquals("background", ThemeResourcePolicy.select("background", files, j)?.name)
        assertEquals("card_background", ThemeResourcePolicy.select("grid_working_card_background", files, j)?.name)
        assertEquals("grid_working_card_background", ThemeHomeLayout.statusResource(j))
        assertArrayEquals(bytes, files.getValue("background").readBytes())
        assertEquals(2, ThemeDocument.preferenceValues(j, files.values.first().parentFile!!)["color_mode"])
    }

    @Test fun adaptiveVariantsSwitchCompletelyAndExplicitEditsSurviveBothModes() {
        val source = fixture("""{"version":3,"adaptive":true,"light":{"keyColor":11,"hasBackground":true,"customBackgroundScrimPercent":10},"dark":{"keyColor":22,"hasBackground":true,"customBackgroundScrimPercent":80}}""",
            "background_light" to byteArrayOf(1), "background_dark" to byteArrayOf(2))
        val (j, files) = ThemePackageReader.read(ByteArrayInputStream(source), temporary.newFolder())
        val light = SukisuThemeDocument.select(j, false)
        val dark = SukisuThemeDocument.select(j, true)
        assertEquals(11, light.getInt("finasu_keyColor")); assertEquals(22, dark.getInt("finasu_keyColor"))
        assertEquals("background_light", ThemeResourcePolicy.select("background", files, light)?.name)
        assertEquals("background_dark", ThemeResourcePolicy.select("background", files, dark)?.name)
        assertEquals(.8, dark.getDouble("backgroundDim"), .001)
        SukisuThemeDocument.recordEdits(j, mapOf("finasu_keyColor" to 33))
        assertEquals(33, SukisuThemeDocument.select(j, false).getInt("finasu_keyColor"))
        assertEquals(33, SukisuThemeDocument.select(j, true).getInt("finasu_keyColor"))
    }

    @Test fun semanticOverlayMergesSharedThenModeAndPreservesOriginalEffects() {
        val raw = JSONObject("""{"version":4,"appearance":{"schema":1,"colors":{"shared":{"accent":"#112233","textPrimary":"#ff445566"},"dark":{"accent":"#abcdef"}},"image":{"background":{"contrast":120}}}}""")
        val j = SukisuThemeDocument.convert(raw, emptyMap())
        assertEquals("#112233", SukisuThemeDocument.select(j, false).getJSONObject("sukisu.colors").getString("accent"))
        val dark = SukisuThemeDocument.select(j, true).getJSONObject("sukisu.colors")
        assertEquals("#abcdef", dark.getString("accent")); assertEquals("#ff445566", dark.getString("textPrimary"))
        assertTrue(ThemeDocument.normalize(j, emptyMap()).second.any { it.contains("contrast") })
    }

    @Test fun replacingAnAdaptiveBackgroundDoesNotFreezeTheCardArtworkToOneMode() {
        val assets = listOf("background_light", "background_dark", "card_background_light", "card_background_dark")
            .associateWith { temporary.newFile(it) }
        val source = JSONObject("""{"version":3,"adaptive":true,"light":{"hasBackground":true,"hasCardBackground":true},"dark":{"hasBackground":true,"hasCardBackground":true}}""")
        val config = SukisuThemeDocument.convert(source, assets)
        config.put("sukisu.resourceOverrides", JSONObject().put("background", "manual.png"))
        val day = SukisuThemeDocument.select(config, false).getJSONObject("sukisu.resources")
        val night = SukisuThemeDocument.select(config, true).getJSONObject("sukisu.resources")
        assertEquals("manual.png", day.getString("background")); assertEquals("manual.png", night.getString("background"))
        assertEquals("card_background_light", day.getString("grid_working_card_background"))
        assertEquals("card_background_dark", night.getString("grid_working_card_background"))
        config.getJSONObject("sukisu.resourceOverrides").put("background", JSONObject.NULL)
        assertFalse(SukisuThemeDocument.select(config, true).getJSONObject("sukisu.resources").has("background"))
    }

    @Test fun navigationSlotsMapByStableNameAndSurviveFptExport() {
        val source = fixture("""{"version":5,"bottomBarIcons":["module","setting"]}""",
            "bottom_bar_icon_module" to byteArrayOf(1), "bottom_bar_icon_setting" to byteArrayOf(2))
        val (j, files) = ThemePackageReader.read(ByteArrayInputStream(source), temporary.newFolder())
        assertEquals("bottom_bar_icon_module", j.getJSONObject("navIcons").getString("AModule"))
        assertEquals("bottom_bar_icon_setting", j.getJSONObject("navIcons").getString("Settings"))
        val exported = FptContainer.write(j, files)
        val (again, resources) = ThemePackageReader.read(ByteArrayInputStream(exported), temporary.newFolder())
        assertEquals(5, again.getJSONObject("sukisu.original").getInt("version"))
        assertArrayEquals(byteArrayOf(2), resources.getValue("bottom_bar_icon_setting").readBytes())
    }

    @Test fun damagedTagTruncationUnsafeEntryAndUnsupportedLayoutsFailPreparation() {
        val source = fixture("""{"version":2}""")
        val changed = source.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        listOf(changed, source.copyOf(source.size - 1), source.copyOf(8),
            fixture("{}", "../outside" to byteArrayOf(1)), fixture("""{"version":6}"""),
            fixture("""{"version":2,"adaptive":true,"light":{},"dark":{}}"""),
            fixture("""{"version":3,"adaptive":true,"light":{}}"""),
            fixture("""{"version":3,"appearance":{"schema":1}}""")).forEach { bytes ->
            assertThrows(Exception::class.java) { ThemePackageReader.read(ByteArrayInputStream(bytes), temporary.newFolder()) }
        }
    }

    @Test fun sukiCatalogUsesObjectSchemaAndTheActualMirrorOrigin() {
        val hash = "ab".repeat(32)
        val text = """{"schema":1,"themes":[{"id":"a","version":2,"formatVersion":5,"name":"Fixture","path":"themes/a.sukisu","previewPath":"previews/a.jpg","sha256":"$hash"}]}"""
        val theme = ThemeStore.parse(text, "https://themes.neko.shirkneko.top:8443/catalog.json").single()
        assertEquals("https://themes.neko.shirkneko.top:8443/themes/a.sukisu", theme.download)
        assertEquals("https://themes.neko.shirkneko.top:8443/previews/a.jpg", theme.preview)
        assertEquals(hash, theme.sha256)
        assertEquals(2, theme.mirrors.size)
        assertEquals("Fixture", theme.metadata().name)
        assertEquals(theme, StoreTheme.read(theme.json()))
        assertThrows(Exception::class.java) { ThemeStore.parse(text.replace("themes/a.sukisu", "https://foreign.invalid/a.sukisu"), "https://themes.oddnexus.top/catalog.json") }
        assertThrows(Exception::class.java) { ThemeStore.parse("""{"schema":1,"maintenance":true,"themes":[]}""", "https://themes.oddnexus.top/catalog.json") }
    }

    @Test fun sourceIsOptionalAndHistoricalSukisuAliasSelectsNova() {
        assertEquals(ThemeStoreSource.Fina, ThemeStoreSource.resolve(null, ""))
        assertEquals(ThemeStoreSource.Suki, ThemeStoreSource.resolve("sukisu_nova", ""))
        assertEquals(ThemeStoreSource.Suki, ThemeStoreSource.resolve("sukisu", ""))
        assertNull(SukisuThemeAccess.baseFor("https://themes.oddnexus.top.evil.invalid/themes/a.sukisu".toHttpUrl()))
    }

    private class MemoryAccessStore : SukisuAccessStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String): Boolean { values[key] = value; return true }
    }
    private fun credential(at: Long, token: Char = 'a') = JSONObject().put("accessToken", "sa_" + token.toString().repeat(64))
        .put("refreshToken", "sr_" + "b".repeat(64)).put("expiresAt", at + 300)
        .put("refreshAfter", at + 180).put("refreshExpiresAt", at + 6000).toString()

    @Test fun deviceAccessRegistersOnceCachesAndRotatesAtThePublishedTime() = runBlocking {
        var at = 1000L; var calls = 0
        val storage = MemoryAccessStore(); val access = SukisuThemeAccess(storage) { at }
        val exchange: suspend (okhttp3.Request) -> Pair<Int, String> = { request ->
            assertEquals("SukiSUThemeAdmin", request.header("X-Requested-With"))
            assertEquals("no-store", request.header("Cache-Control"))
            assertEquals(if (calls++ == 0) "/client/access" else "/client/refresh", request.url.encodedPath)
            200 to credential(at, if (calls == 1) 'a' else 'c')
        }
        val base = "https://themes.oddnexus.top/"
        assertEquals("sa_" + "a".repeat(64), access.token(base, exchange = exchange))
        access.token(base, exchange = exchange); assertEquals(1, calls)
        at += 190
        assertEquals("sa_" + "c".repeat(64), access.token(base, exchange = exchange))
        assertEquals(2, calls)
    }

    @Test fun deniedCredentialIsNotRegisteredRepeatedlyAndMirrorsHaveSeparateAccess() = runBlocking {
        val storage = MemoryAccessStore(); val access = SukisuThemeAccess(storage) { 1000 }
        var calls = 0
        val denied: suspend (okhttp3.Request) -> Pair<Int, String> = { calls++; 403 to "{}" }
        repeat(2) {
            try { access.token("https://themes.oddnexus.top/", exchange = denied); fail("Expected denied credential") }
            catch (_: IllegalStateException) { }
        }
        assertEquals(1, calls)
        access.token("https://themes.neko.shirkneko.top:8443/") { 200 to credential(1000) }
        assertEquals(2, storage.values.size)
    }

    @Test fun expiredRefreshCredentialGetsOneNewRegistrationAndForcedRefreshUsesTheExistingDevice() = runBlocking {
        var at = 1000L
        val access = SukisuThemeAccess(MemoryAccessStore()) { at }
        val paths = mutableListOf<String>()
        val exchange: suspend (okhttp3.Request) -> Pair<Int, String> = { request ->
            paths += request.url.encodedPath
            200 to credential(at)
        }
        val base = "https://themes.oddnexus.top/"
        access.token(base, exchange = exchange)
        access.token(base, rejectedToken = "sa_" + "a".repeat(64), exchange = exchange)
        at += 7000
        access.token(base, exchange = exchange)
        assertEquals(listOf("/client/access", "/client/refresh", "/client/access"), paths)
    }

    @Test fun malformedCredentialResponsesNeverEnterThePersistedStore() = runBlocking {
        val storage = MemoryAccessStore(); val access = SukisuThemeAccess(storage) { 1000 }
        try {
            access.token("https://themes.oddnexus.top/") { 200 to """{"accessToken":"invalid"}""" }
            fail("Expected invalid response")
        } catch (_: Exception) { }
        assertTrue(storage.values.isEmpty())
    }

    @Test fun concurrent401sFromTheSameRejectedTokenShareOneCredentialRotation() = runBlocking {
        val access = SukisuThemeAccess(MemoryAccessStore()) { 1000 }
        var exchanges = 0
        val exchange: suspend (okhttp3.Request) -> Pair<Int, String> = {
            exchanges++
            delay(10)
            200 to credential(1000, if (exchanges == 1) 'a' else 'c')
        }
        val base = "https://themes.oddnexus.top/"
        val rejected = access.token(base, exchange = exchange)
        val retryTokens = (1..24).map {
            async { access.token(base, rejectedToken = rejected, exchange = exchange) }
        }.awaitAll()
        assertEquals(2, exchanges)
        assertTrue(retryTokens.all { it == "sa_" + "c".repeat(64) })
        assertNotEquals(rejected, retryTokens.first())
    }

    @Test fun rejectedExpiredAndRevokedCredentialsAreNotReusedAsANewerToken() = runBlocking {
        var at = 1000L
        val access = SukisuThemeAccess(MemoryAccessStore()) { at }
        val base = "https://themes.oddnexus.top/"
        access.token(base) { 200 to credential(at) }
        at += 310
        var rotations = 0
        access.token(base, rejectedToken = "sa_" + "d".repeat(64)) {
            rotations++; 200 to credential(at, 'c')
        }
        assertEquals(1, rotations)
        try {
            access.token(base, rejectedToken = "sa_" + "c".repeat(64)) { rotations++; 403 to "{}" }
            fail("Expected revoked access")
        } catch (_: IllegalStateException) { }
        try {
            access.token(base, rejectedToken = "sa_" + "a".repeat(64)) { rotations++; 200 to credential(at) }
            fail("Expected revoked access")
        } catch (_: IllegalStateException) { }
        assertEquals(2, rotations)
    }

    @Test fun protectedCompatDownloadsStayOnTheAuthenticatedInAppRoute() {
        fun theme(url: String) = StoreTheme("a", "Fixture", "", "1", "", "phone", "", "", url)
        ThemeStoreSource.Suki.urls.forEach { catalog ->
            val value = theme(catalog.substringBeforeLast('/') + "/themes/a.sukisu")
            assertNull(ThemeStore.browserDownload(value, "compat"))
            assertNull(ThemeStore.browserDownload(value, "builtin"))
        }
        val public = theme("https://public.invalid/a.fpt")
        assertEquals(public.download, ThemeStore.browserDownload(public, "compat"))
        assertNull(ThemeStore.browserDownload(public, "builtin"))
    }

    @Test fun externalSukisuAndFptInputsReachTheSameMagicDetectedReader() {
        assertTrue(ThemeImportPolicy.accepts("theme.fpt", "application/octet-stream"))
        assertTrue(ThemeImportPolicy.accepts("theme.SUKISU", "application/octet-stream"))
        assertTrue(ThemeImportPolicy.accepts("content-without-extension", "application/x-sukisu-theme"))
        assertTrue(ThemeImportPolicy.accepts("", "Application/X-Sukisu-Theme; charset=binary"))
        assertFalse(ThemeImportPolicy.accepts("module.zip", "application/octet-stream"))
        assertFalse(ThemeImportPolicy.accepts("theme.sukisu.exe", null))
        val bytes = fixture("""{"version":2,"keyColor":42}""")
        val (json, _) = ThemePackageReader.read(ByteArrayInputStream(bytes), temporary.newFolder())
        assertEquals(42, json.getInt("finasu_keyColor"))
    }
}
