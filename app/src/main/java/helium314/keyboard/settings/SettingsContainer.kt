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

        // Lower rank sorts first: whole-title prefix, then any title word, then any description
        // word, then anywhere in the title, then anywhere in the description. Those last two are
        // what make a term like "ZDR" findable at all — it only ever occurs bracketed or
        // hyphenated inside a longer token, so word-prefix matching alone could never surface it.
        fun rank(setting: Setting): Int {
            val title = setting.title.lowercase()
            val description = setting.description?.lowercase()
            return when {
                title.startsWith(term) -> 0
                title.split(' ').any { it.startsWith(term) } -> 1
                description?.split(' ')?.any { it.startsWith(term) } == true -> 2
                term in title -> 3
                description?.contains(term) == true -> 4
                else -> NO_MATCH
            }
        }

        // sortedBy is stable, so settings sharing a rank keep their declaration order.
        return list.map { it to rank(it) }
            .filter { it.second != NO_MATCH }
            .sortedBy { it.second }
            .map { it.first }
    }

    companion object {
        private const val NO_MATCH = Int.MAX_VALUE
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
    val title = context.getString(titleId)
    val description = descriptionId?.let { context.getString(it) }

    @Composable
    fun Preference() {
        content(this)
    }
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
