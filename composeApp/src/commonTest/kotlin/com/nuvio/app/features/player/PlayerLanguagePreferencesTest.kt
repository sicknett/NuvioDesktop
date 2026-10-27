package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PlayerLanguagePreferencesTest {
    @Test
    fun blankInputHasNoLanguage() {
        assertNull(normalizeLanguageCode(null))
        assertNull(normalizeLanguageCode(""))
        assertNull(normalizeLanguageCode("   "))
    }

    @Test
    fun normalizesCodesAndNames() {
        assertEquals("en", normalizeLanguageCode("eng"))
        assertEquals("en", normalizeLanguageCode("English"))
        assertEquals("pt-BR".lowercase(), normalizeLanguageCode("pt-BR")?.lowercase())
        assertEquals("pt-br", normalizeLanguageCode("Portuguese (Brazil)"))
        assertEquals("pt", normalizeLanguageCode("Portuguese"))
        assertEquals("es", normalizeLanguageCode("Spanish"))
        assertEquals("es-419", normalizeLanguageCode("Spanish Latin America"))
    }

    @Test
    fun repeatedCallsReturnTheSameResult() {
        val inputs = listOf("eng", "English", "pt_BR", "Spanish (Latin America)", "unknown-xx", "ja")
        val first = inputs.map(::normalizeLanguageCode)
        repeat(3) { assertEquals(first, inputs.map(::normalizeLanguageCode)) }
    }

    @Test
    fun matchesPrimaryLanguageSubtag() {
        assertEquals(true, languageMatchesPreference("eng", "en"))
        assertEquals(true, languageMatchesPreference("pt-BR", "pt"))
        assertEquals(false, languageMatchesPreference("fr", "en"))
    }

    @Test
    fun labelLookupUsesTheFirstMatchingOption() {
        val expected = AvailableLanguageOptions.first { normalizeLanguageCode(it.code) == "en" }.labelRes
        assertSame(expected, languageLabelResForCode("eng"))
        assertSame(expected, languageLabelResForCode("English"))
        assertNull(languageLabelResForCode(null))
    }
}
