// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every settings row used to register its own [SharedPreferences.OnSharedPreferenceChangeListener].
 * They now share one per preferences file, so these cover the delivery guarantees that consolidation
 * has to preserve: the right rows wake up, unrelated rows stay asleep, and nothing keeps firing after
 * a row leaves the screen.
 */
@RunWith(RobolectricTestRunner::class)
class PreferenceStateTest {

    private lateinit var prefs: SharedPreferences

    private fun subscribeForTest(prefs: SharedPreferences, key: String, onChange: () -> Unit) =
        PreferenceWatchers.subscribe(prefs, key, onChange)

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("preference_state_test", Context.MODE_PRIVATE)
        prefs.edit(commit = true) { clear() }
    }

    @Test
    fun everyWatcherOfAKeyIsNotified() {
        var first = 0
        var second = 0
        subscribeForTest(prefs, "watched") { first++ }
        subscribeForTest(prefs, "watched") { second++ }

        prefs.edit(commit = true) { putString("watched", "value") }

        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun watchersOfOtherKeysAreNotWokenUp() {
        var watched = 0
        var other = 0
        subscribeForTest(prefs, "watched") { watched++ }
        subscribeForTest(prefs, "other") { other++ }

        prefs.edit(commit = true) { putString("watched", "value") }

        assertEquals(1, watched)
        assertEquals(0, other)
    }

    @Test
    fun unsubscribingStopsDeliveryForThatRowOnly() {
        var kept = 0
        var removed = 0
        subscribeForTest(prefs, "watched") { kept++ }
        val unsubscribe = subscribeForTest(prefs, "watched") { removed++ }

        unsubscribe()
        prefs.edit(commit = true) { putString("watched", "value") }

        assertEquals(1, kept, "the surviving row must still be notified")
        assertEquals(0, removed, "a disposed row must not be notified")
    }

    @Test
    fun theLastUnsubscribeDetachesFromSharedPreferencesAndReattachesLater() {
        var count = 0
        val unsubscribe = subscribeForTest(prefs, "watched") { count++ }
        unsubscribe()

        prefs.edit(commit = true) { putString("watched", "one") }
        assertEquals(0, count)

        // Re-subscribing after the shared listener was torn down has to work: a screen is disposed
        // and recomposed every time the user navigates away and back.
        subscribeForTest(prefs, "watched") { count++ }
        prefs.edit(commit = true) { putString("watched", "two") }
        assertEquals(1, count)
    }

    @Test
    fun unsubscribingTwiceIsHarmless() {
        var count = 0
        subscribeForTest(prefs, "watched") { count++ }
        val unsubscribe = subscribeForTest(prefs, "watched") { count++ }

        unsubscribe()
        unsubscribe()

        prefs.edit(commit = true) { putString("watched", "value") }
        assertEquals(1, count, "the double dispose must not have removed the other row too")
    }
}
