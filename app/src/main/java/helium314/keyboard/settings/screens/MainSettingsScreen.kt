// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceGroupSurface
import helium314.keyboard.settings.preferences.PreferenceListVerticalPadding
import helium314.keyboard.settings.preferences.preferenceGroupPositions
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

/** One row on the main settings screen: a screen to open, its icon, and an optional summary. */
private class MainEntry(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val onClick: () -> Unit,
    val description: String? = null,
)

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickVoice: () -> Unit,
    onClickTextFix: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = SubtypeSettings.getEnabledSubtypes(true)
        // Built as data first so the rows can be grouped into one rounded card. Two of them are
        // conditional, so the positions have to come from the list that is actually shown — a
        // hardcoded "last row is the bottom corner" would round the wrong row on a build without
        // the gesture library.
        val entries = buildList {
            add(MainEntry(R.string.language_and_layouts_title, R.drawable.ic_settings_languages, onClickLanguage,
                enabledSubtypes.joinToString(", ") { it.displayName() }))
            add(MainEntry(R.string.settings_screen_preferences, R.drawable.ic_settings_preferences, onClickPreferences))
            add(MainEntry(R.string.settings_screen_appearance, R.drawable.ic_settings_appearance, onClickAppearance))
            add(MainEntry(R.string.settings_screen_toolbar, R.drawable.ic_settings_toolbar, onClickToolbar))
            add(MainEntry(R.string.settings_screen_voice, R.drawable.sym_keyboard_voice_rounded, onClickVoice))
            add(MainEntry(R.string.settings_screen_text_fix, R.drawable.ic_text_fix, onClickTextFix))
            if (JniUtils.sHaveGestureLib)
                add(MainEntry(R.string.settings_screen_gesture, R.drawable.ic_settings_gesture, onClickGestureTyping))
            // we don't even show the menu if data gathering phase ended more than 2 weeks ago
            if (BuildConfig.ENABLE_GESTURE_DATA_GATHERING
                && JniUtils.sHaveGestureLib
                && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS
            )
                add(MainEntry(R.string.gesture_data_screen, R.drawable.ic_settings_gesture, onClickDataGathering))
            add(MainEntry(R.string.settings_screen_correction, R.drawable.ic_settings_correction, onClickTextCorrection))
            add(MainEntry(R.string.settings_screen_secondary_layouts, R.drawable.ic_ime_switcher, onClickLayouts))
            add(MainEntry(R.string.dictionary_settings_category, R.drawable.ic_dictionary, onClickDictionaries))
            add(MainEntry(R.string.settings_screen_advanced, R.drawable.ic_settings_advanced, onClickAdvanced))
            add(MainEntry(R.string.settings_screen_about, R.drawable.ic_settings_about, onClickAbout))
        }
        val positions = preferenceGroupPositions(entries) { false }
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .then(Modifier.padding(innerPadding))
                    // Clears the app bar above and the bottom edge below, matching the padding the
                    // other settings screens get from their LazyColumn.
                    .padding(vertical = PreferenceListVerticalPadding)
            ) {
                entries.forEachIndexed { index, entry ->
                    PreferenceGroupSurface(positions[index]) {
                        Preference(
                            name = stringResource(entry.title),
                            description = entry.description,
                            onClick = entry.onClick,
                            icon = entry.icon,
                        ) { NextScreenIcon() }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
