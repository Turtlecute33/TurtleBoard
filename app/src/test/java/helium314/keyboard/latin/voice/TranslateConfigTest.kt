// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.settings.Defaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateConfigTest {

    // --- parseTranslateLanguages ---

    @Test
    fun defaultLanguageListParsesToTheAdvertisedLanguages() {
        assertEquals(
            listOf("English", "Italian", "Spanish", "French", "German"),
            parseTranslateLanguages(Defaults.PREF_TRANSLATE_LANGUAGES)
        )
    }

    @Test
    fun allSupportedSeparatorsSplitTheList() {
        assertEquals(
            listOf("English", "Italian", "Spanish", "French"),
            parseTranslateLanguages("English, Italian; Spanish\nFrench")
        )
    }

    @Test
    fun legacyPipeSeparatedValueStillParses() {
        assertEquals(listOf("English", "Italian"), parseTranslateLanguages("English|Italian"))
    }

    @Test
    fun blankAndDuplicateEntriesAreDropped() {
        assertEquals(
            listOf("English", "italian"),
            parseTranslateLanguages("  English , , italian ,ENGLISH,\n")
        )
    }

    @Test
    fun firstSpellingOfADuplicateWins() {
        assertEquals(listOf("italian"), parseTranslateLanguages("italian, Italian"))
    }

    @Test
    fun emptyValueYieldsNoLanguages() {
        assertEquals(emptyList<String>(), parseTranslateLanguages("   ,  ; \n "))
    }

    @Test
    fun theListIsCappedSoTheMiddleMenuStaysUsable() {
        val many = (1..100).joinToString(",") { "Lang$it" }
        assertEquals(TRANSLATE_MAX_LANGUAGES, parseTranslateLanguages(many).size)
    }

    // --- resolveTranslatePrompt ---

    @Test
    fun placeholderIsReplacedEverywhereInTheDefaultPrompt() {
        val prompt = resolveTranslatePrompt(Defaults.PREF_TRANSLATE_PROMPT, "Italian")
        assertTrue("target language must reach the model", prompt.contains("into Italian"))
        assertTrue("no placeholder may survive", !prompt.contains("\${language}"))
    }

    @Test
    fun customPromptWithoutPlaceholderStillNamesTheTargetLanguage() {
        val prompt = resolveTranslatePrompt("Be terse.", "German")
        assertEquals("Be terse.\nTranslate into German.", prompt)
    }

    @Test
    fun blankPromptFallsBackToTheDefault() {
        val prompt = resolveTranslatePrompt("   ", "French")
        assertEquals(resolveTranslatePrompt(Defaults.PREF_TRANSLATE_PROMPT, "French"), prompt)
    }
}
