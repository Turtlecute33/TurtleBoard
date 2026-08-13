// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.voice.resolveVoicePrompt
import helium314.keyboard.latin.voice.resolveVoiceModel
import helium314.keyboard.latin.utils.ToolbarMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolbarScreenLogicTest {
    @Test
    fun toolbarItemsKeepExistingToolbarConditionals() {
        val hiddenToolbarItems = buildToolbarScreenItems(
            toolbarMode = ToolbarMode.HIDDEN,
            toolbarHidingGlobal = false,
        )

        assertTrue(Settings.PREF_TOOLBAR_HIDING_GLOBAL in hiddenToolbarItems)
        assertFalse(Settings.PREF_TOOLBAR_KEYS in hiddenToolbarItems)
        // the clipboard panel has no toolbar strip, so its key list is not offered any more
        assertFalse(Settings.PREF_CLIPBOARD_TOOLBAR_KEYS in hiddenToolbarItems)
        assertTrue(Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES in hiddenToolbarItems)
        assertFalse(R.string.settings_category_miscellaneous in hiddenToolbarItems)
        assertFalse(Settings.PREF_VOICE_INPUT_ENABLED in hiddenToolbarItems)
    }

    @Test
    fun resolveVoiceModelTrimsCustomModel() {
        assertEquals(
            "openai/whisper-large",
            resolveVoiceModel("custom", "  openai/whisper-large  ")
        )
    }

    @Test
    fun resolveVoiceModelRejectsBlankCustomModel() {
        assertNull(resolveVoiceModel("custom", "   "))
    }

    @Test
    fun resolveVoicePromptFallsBackToDefaultForBlankValue() {
        assertEquals(
            Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT,
            resolveVoicePrompt("   ").systemPrompt
        )
    }

    @Test
    fun resolveVoicePromptTrimsCustomPrompt() {
        assertEquals(
            "Return clean bullet points.",
            resolveVoicePrompt("  Return clean bullet points.  ").systemPrompt
        )
    }
}
