// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.R
import helium314.keyboard.settings.preferences.PreferenceGroupPosition
import helium314.keyboard.settings.preferences.PreferenceGroupPosition.FIRST
import helium314.keyboard.settings.preferences.PreferenceGroupPosition.LAST
import helium314.keyboard.settings.preferences.PreferenceGroupPosition.MIDDLE
import helium314.keyboard.settings.preferences.PreferenceGroupPosition.SINGLE
import helium314.keyboard.settings.preferences.preferenceGroupPositions
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SettingsUiLogicTest {

    /** Category headings are Ints on a settings list; anything else is a preference row. */
    private fun positions(vararg rows: Any): List<PreferenceGroupPosition> =
        preferenceGroupPositions(rows.toList()) { it is Int }

    @Test
    fun consecutivePreferencesFormOneRoundedGroup() {
        // Only the outer edges of a run get large corners, so the rows read as a single card.
        assertEquals(listOf(FIRST, MIDDLE, MIDDLE, LAST), positions("a", "b", "c", "d"))
    }

    @Test
    fun aCategoryHeadingBreaksTheGroup() {
        //         1       a      b     2       c       3       d      e
        assertEquals(
            listOf(SINGLE, FIRST, LAST, SINGLE, SINGLE, SINGLE, FIRST, LAST),
            positions(1, "a", "b", 2, "c", 3, "d", "e")
        )
    }

    @Test
    fun aLonePreferenceIsItsOwnGroup() {
        assertEquals(listOf(SINGLE), positions("only"))
        assertEquals(listOf(SINGLE, SINGLE), positions(1, "only"))
        // Back-to-back headings never leave a stray card between them.
        assertEquals(listOf(SINGLE, SINGLE, SINGLE), positions(1, 2, "only"))
    }

    @Test
    fun anEmptyListDoesNotThrow() {
        assertEquals(emptyList(), positions())
    }

    @Test
    fun searchRanksTitlePrefixAboveDescriptionMatch() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Title "Enable Voice Input" — "voice" is a title word, "enable" is the whole-title prefix.
        val titleWordMatch = Setting(ctx, "a", R.string.voice_input_enabled) {}
        // Title "About" — "voice" only occurs in the description.
        val descriptionOnlyMatch =
            Setting(ctx, "b", R.string.settings_screen_about, R.string.voice_traditional_button_enabled_summary) {}

        assertEquals(0, titleWordMatch.searchRank("enable"))
        assertEquals(1, titleWordMatch.searchRank("voice"))
        assertEquals(2, descriptionOnlyMatch.searchRank("voice"))
        assertTrue(titleWordMatch.searchRank("voice") < descriptionOnlyMatch.searchRank("voice"))
        assertEquals(Setting.NO_MATCH, titleWordMatch.searchRank("zzzznotpresent"))
        // Every rank a match can produce must index into the caller's bucket array.
        assertTrue(descriptionOnlyMatch.searchRank("voice") < Setting.RANK_COUNT)
    }

    @Test
    fun searchFindsTermsThatOnlyOccurInsideALongerToken() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // "ZDR" only ever appears bracketed or hyphenated, so word-prefix matching alone misses it.
        // This is the regression the substring ranks exist for.
        val zdr = Setting(ctx, "zdr", R.string.openrouter_zdr_enabled, R.string.openrouter_zdr_enabled_summary) {}

        assertTrue(zdr.searchRank("zdr") != Setting.NO_MATCH)
    }
}
