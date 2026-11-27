package com.maplesu.core.theme

import org.junit.Assert.*
import org.junit.Test

class ThemeStoreSourceTest {
    @Test fun builtinSourcesHaveIndependentEndpointsAndExistingDefaultIsPreserved() {
        assertNotEquals(ThemeStoreSource.Maple.url,ThemeStoreSource.Fina.url)
        assertEquals(2,ThemeStoreSource.Maple.urls.size)
        assertEquals(listOf(ThemeStoreSource.Fina.url),ThemeStoreSource.Fina.urls)
        assertEquals(ThemeStoreSource.Fina,ThemeStoreSource.resolve(null,""))
    }

    @Test fun customSourceNormalizesItsUrlAndRejectsUnusableEndpoints() {
        assertEquals("https://example.org/catalog.json",ThemeStoreSource.custom(" https://example.org/catalog.json ").url)
        listOf("", "file:///catalog.json", "http://example.org/catalog.json", "https://u:p@example.org/catalog.json",
            "https://example.org/catalog.json#fragment").forEach {
            assertThrows(IllegalArgumentException::class.java) { ThemeStoreSource.custom(it) }
        }
    }

    @Test fun relativeResourcesResolveAgainstTheActualCatalogOrigin() {
        val text = """[{"id":"one","name":"Fixture","download_url":"packages/one.fpt","preview_url":"previews/one.png","download_mirrors":["https://backup.invalid/one.fpt"]}]"""
        val entry = ThemeStore.parse(text,"https://example.org/themes/index.json").single()
        assertEquals("https://example.org/themes/packages/one.fpt",entry.download)
        assertEquals("https://example.org/themes/previews/one.png",entry.preview)
        assertEquals(listOf("https://backup.invalid/one.fpt"),entry.mirrors)
        assertEquals(entry,StoreTheme.read(entry.json()))
    }
}
