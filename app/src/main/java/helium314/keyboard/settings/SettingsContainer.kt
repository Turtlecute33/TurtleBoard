// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.settings.screens.createAboutSettings
import helium314.keyboard.settings.screens.createAdvancedSettings
import helium314.keyboard.settings.screens.createAppearanceSettings
import helium314.keyboard.settings.screens.createCorrectionSettings
import helium314.keyboard.settings.screens.createGestureTypingSettings
import helium314.keyboard.settings.screens.createLayoutSettings
import helium314.keyboard.settings.screens.createPreferencesSettings
import helium314.keyboard.settings.screens.createToolbarSettings
import helium314.keyboard.settings.screens.createTextFixSettings
import helium314.keyboard.settings.screens.createVoiceSettings

class SettingsContainer(context: Context) {
    private val list = createSettings(context)
    private val map: Map<String, Setting> = HashMap<String, Setting>(list.size).apply {
        list.forEach {
            if (put(it.key, it) != null)
                throw IllegalArgumentException("key $it added twice")
        }
    }

    operator fun get(key: Any): Setting? = map[key]

    // filtering could be more elaborate, but should be good enough for a start
    // always have all settings in search, because:
    //  don't show disabled settings -> users confused
    //  show as disabled (i.e. no interaction possible) -> users confused
    //  show, but change will not do anything because another setting needs to be enabled first -> probably best
    fun filter(searchTerm: String): List<Setting> {
        val term = searchTerm.lowercase()
        // sortedBy is stable, so settings sharing a rank keep their declaration order. Ranking is
        // done into a reused buckets array rather than a list of pairs: this runs on every
        // keystroke, and the old version allocated a Pair per setting and re-lowercased every
        // title and description each time.
        val buckets = Array(Setting.RANK_COUNT) { mutableListOf<Setting>() }
        for (setting in list) {
            val rank = setting.searchRank(term)
            if (rank != Setting.NO_MATCH) buckets[rank].add(setting)
        }
        return buckets.flatMap { it }
    }
}

@Immutable
class Setting(
    context: Context,
    val key: String,
    @StringRes titleId: Int,
    @StringRes descriptionId: Int? = null,
    private val content: @Composable (Setting) -> Unit
) {
    // The application context, never the Activity: SettingsContainer is held in a static, so
    // keeping the Activity alive here would leak it across every configuration change.
    private val appContext = context.applicationContext

    // Resolved on first use rather than in the constructor. SettingsContainer builds every Setting
    // of every screen before the first frame, so eager resolution meant opening Appearance paid for
    // reading all of Voice's and Advanced's strings too.
    val title: String by lazy(LazyThreadSafetyMode.NONE) { appContext.getString(titleId) }
    val description: String? by lazy(LazyThreadSafetyMode.NONE) {
        descriptionId?.let { appContext.getString(it) }
    }

    private val titleLowercase: String by lazy(LazyThreadSafetyMode.NONE) { title.lowercase() }
    private val descriptionLowercase: String? by lazy(LazyThreadSafetyMode.NONE) { description?.lowercase() }

    /**
     * Search rank for an already-lowercased [term], or [NO_MATCH]. Lower sorts first: whole-title
     * prefix, then any title word, then any description word, then anywhere in the title, then
     * anywhere in the description. Those last two are what make a term like "ZDR" findable at all —
     * it only ever occurs bracketed or hyphenated inside a longer token, so word-prefix matching
     * alone could never surface it.
     */
    fun searchRank(term: String): Int {
        val title = titleLowercase
        val description = descriptionLowercase
        return when {
            title.startsWith(term) -> 0
            title.hasWordStartingWith(term) -> 1
            description?.hasWordStartingWith(term) == true -> 2
            term in title -> 3
            description?.contains(term) == true -> 4
            else -> NO_MATCH
        }
    }

    @Composable
    fun Preference() {
        content(this)
    }

    companion object {
        const val RANK_COUNT = 5
        const val NO_MATCH = RANK_COUNT
    }
}

/** Word-prefix test that walks the string instead of allocating a split list per call. */
private fun String.hasWordStartingWith(term: String): Boolean {
    if (term.isEmpty()) return true
    var start = 0
    while (start <= length) {
        val end = indexOf(' ', start).let { if (it == -1) length else it }
        if (end - start >= term.length && regionMatches(start, term, 0, term.length)) return true
        start = end + 1
    }
    return false
}

// intentionally not putting individual debug settings in here so user knows the context
private fun createSettings(context: Context) = createAboutSettings(context) + createAppearanceSettings(context) +
        createCorrectionSettings(context) + createPreferencesSettings(context) + createToolbarSettings(context) +
        createVoiceSettings(context) + createTextFixSettings(context) +
        createLayoutSettings(context) + createAdvancedSettings(context) +
        if (JniUtils.sHaveGestureLib) createGestureTypingSettings(context) else emptyList()

object SettingsWithoutKey {
    const val EDIT_PERSONAL_DICTIONARY = "edit_personal_dictionary"
    const val APP = "app"
    const val VERSION = "version"
    const val LICENSE = "license"
    const val HIDDEN_FEATURES = "hidden_features"
    const val GITHUB = "github"
    const val GITHUB_WIKI = "github_wiki"
    const val SAVE_LOG = "save_log"
    const val BACKUP_RESTORE = "backup_restore"
    const val DEBUG_SETTINGS = "screen_debug"
    const val LOAD_GESTURE_LIB = "load_gesture_library"
    const val BACKGROUND_IMAGE = "background_image"
    const val BACKGROUND_IMAGE_LANDSCAPE = "background_image_landscape"
    const val CUSTOM_FONT = "custom_font"
    const val CUSTOM_EMOJI_FONT = "custom_emoji_font"
}
