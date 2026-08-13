// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.media.AudioManager
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.ReorderSwitchPreference
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.rememberBooleanPreferenceState
import helium314.keyboard.settings.preferences.SwitchPreferenceWithEmojiDictWarning
import helium314.keyboard.latin.utils.previewDark

@Composable
fun PreferencesScreen(
    onClickBack: () -> Unit,
) {
    val showHints by rememberBooleanPreferenceState(Settings.PREF_SHOW_HINTS, Defaults.PREF_SHOW_HINTS)
    val vibrateOn by rememberBooleanPreferenceState(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON)
    val soundOn by rememberBooleanPreferenceState(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON)
    val showNumberRow by rememberBooleanPreferenceState(Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW)
    val clipboardHistoryEnabled by rememberBooleanPreferenceState(
        Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        Defaults.PREF_ENABLE_CLIPBOARD_HISTORY
    )
    val items = listOf(
        R.string.settings_category_input,
        Settings.PREF_SHOW_HINTS,
        if (showHints)
            Settings.PREF_POPUP_KEYS_LABELS_ORDER else null,
        Settings.PREF_POPUP_KEYS_ORDER,
        Settings.PREF_ACTION_POPUP_ORDER,
        Settings.PREF_SHOW_POPUP_HINTS,
        Settings.PREF_SHOW_TLD_POPUP_KEYS,
        Settings.PREF_POPUP_ON,
        Settings.PREF_KEY_PRESS_RIPPLE,
        if (AudioAndHapticFeedbackManager.getInstance().hasVibrator())
            Settings.PREF_VIBRATE_ON else null,
        if (vibrateOn)
            Settings.PREF_VIBRATION_DURATION_SETTINGS else null,
        if (vibrateOn)
            Settings.PREF_VIBRATION_INTENSITY else null,
        if (vibrateOn)
            Settings.PREF_VIBRATE_IN_DND_MODE else null,
        if (AudioAndHapticFeedbackManager.getInstance().hasVibrator())
            Settings.PREF_POPUP_DRAG_HAPTIC else null,
        Settings.PREF_SOUND_ON,
        if (soundOn)
            Settings.PREF_KEYPRESS_SOUND_VOLUME else null,
        Settings.PREF_SAVE_SUBTYPE_PER_APP,
        Settings.PREF_SHOW_EMOJI_DESCRIPTIONS,
        R.string.settings_category_additional_keys,
        Settings.PREF_SHOW_NUMBER_ROW,
        if (SubtypeSettings.getEnabledSubtypes(true).any { it.locale().language in localesWithLocalizedNumberRow })
            Settings.PREF_LOCALIZED_NUMBER_ROW else null,
        if (showHints && showNumberRow)
            Settings.PREF_SHOW_NUMBER_ROW_HINTS else null,
        if (!showNumberRow)
            Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS else null,
        Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY,
        Settings.PREF_LANGUAGE_SWITCH_KEY,
        Settings.PREF_SHOW_EMOJI_KEY,
        Settings.PREF_REMOVE_REDUNDANT_POPUPS,
        R.string.settings_category_clipboard_history,
        Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME else null,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST else null
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_preferences),
        settings = items
    )
}

fun createPreferencesSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_SAVE_SUBTYPE_PER_APP, R.string.save_subtype_per_app) {
        SwitchPreference(it, Defaults.PREF_SAVE_SUBTYPE_PER_APP)
    },
    Setting(context, Settings.PREF_SHOW_HINTS, R.string.show_hints, R.string.show_hints_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_HINTS) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_POPUP_KEYS_LABELS_ORDER, R.string.hint_source) {
        ReorderSwitchPreference(it, Defaults.PREF_POPUP_KEYS_LABELS_ORDER)
    },
    Setting(context, Settings.PREF_POPUP_KEYS_ORDER, R.string.popup_order) {
        ReorderSwitchPreference(it, Defaults.PREF_POPUP_KEYS_ORDER)
    },
    Setting(context, Settings.PREF_ACTION_POPUP_ORDER, R.string.action_popup_order, R.string.action_popup_order_summary) {
        ReorderSwitchPreference(it, Defaults.PREF_ACTION_POPUP_ORDER, stringResource(R.string.action_popup_order_dialog_help))
    },
    Setting(
        context, Settings.PREF_SHOW_TLD_POPUP_KEYS, R.string.show_tld_popup_keys,
        R.string.show_tld_popup_keys_summary
    ) {
        SwitchPreference(it, Defaults.PREF_SHOW_TLD_POPUP_KEYS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_POPUP_HINTS, R.string.show_popup_hints, R.string.show_popup_hints_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_POPUP_HINTS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_POPUP_ON, R.string.popup_on_keypress) {
        SwitchPreference(it, Defaults.PREF_POPUP_ON) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_KEY_PRESS_RIPPLE, R.string.key_press_ripple) {
        SwitchPreference(it, Defaults.PREF_KEY_PRESS_RIPPLE) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_VIBRATE_ON, R.string.vibrate_on_keypress) {
        SwitchPreference(it, Defaults.PREF_VIBRATE_ON)
    },
    Setting(context, Settings.PREF_VIBRATE_IN_DND_MODE, R.string.vibrate_in_dnd_mode) {
        SwitchPreference(it, Defaults.PREF_VIBRATE_IN_DND_MODE)
    },
    Setting(context, Settings.PREF_POPUP_DRAG_HAPTIC, R.string.popup_drag_haptic, R.string.popup_drag_haptic_summary) {
        SwitchPreference(it, Defaults.PREF_POPUP_DRAG_HAPTIC)
    },
    Setting(context, Settings.PREF_SOUND_ON, R.string.sound_on_keypress) {
        SwitchPreference(it, Defaults.PREF_SOUND_ON)
    },
    Setting(context, Settings.PREF_SHOW_EMOJI_DESCRIPTIONS, R.string.show_emoji_descriptions) {
        SwitchPreferenceWithEmojiDictWarning(it, Defaults.PREF_SHOW_EMOJI_DESCRIPTIONS)
    },
    Setting(context, Settings.PREF_SHOW_NUMBER_ROW, R.string.number_row, R.string.number_row_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_NUMBER_ROW) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS, R.string.number_row_in_symbols) {
        SwitchPreference(it, Defaults.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_LOCALIZED_NUMBER_ROW, R.string.localized_number_row, R.string.localized_number_row_summary) {
        SwitchPreference(it, Defaults.PREF_LOCALIZED_NUMBER_ROW) {
            KeyboardLayoutSet.onSystemLocaleChanged()
            KeyboardSwitcher.getInstance().reloadKeyboard()
        }
    },
    Setting(context, Settings.PREF_SHOW_NUMBER_ROW_HINTS, R.string.number_row_hints) {
        SwitchPreference(it, Defaults.PREF_SHOW_NUMBER_ROW_HINTS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, R.string.show_language_switch_key) {
        SwitchPreference(it, Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_LANGUAGE_SWITCH_KEY, R.string.language_switch_key_behavior) {
        ListPreference(
            it,
            listOf(
                stringResource(R.string.switch_language) to "internal",
                stringResource(R.string.language_switch_key_switch_input_method) to "input_method",
                stringResource(R.string.language_switch_key_switch_both) to "both"
            ),
            Defaults.PREF_LANGUAGE_SWITCH_KEY
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_EMOJI_KEY, R.string.show_emoji_key, R.string.show_emoji_key_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_EMOJI_KEY) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_REMOVE_REDUNDANT_POPUPS,
        R.string.remove_redundant_popups, R.string.remove_redundant_popups_summary)
    {
        SwitchPreference(it, Defaults.PREF_REMOVE_REDUNDANT_POPUPS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        R.string.enable_clipboard_history, R.string.enable_clipboard_history_summary)
    {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY) { ClipboardDao.getInstance(ctx)?.clear() }
    },
    Setting(context, Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, R.string.clipboard_history_retention_time) { setting ->
        val ctx = LocalContext.current
        val items = Settings.CLIPBOARD_RETENTION_PRESETS.map { retentionLabel(it) to it } +
                (stringResource(R.string.settings_no_limit) to Settings.CLIPBOARD_RETENTION_UNLIMITED)
        ListPreference(setting, items, Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME) {
            ClipboardDao.getInstance(ctx)?.clearOldClips(true)
        }
    },
    Setting(context, Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST, R.string.clipboard_history_pinned_first) {
        SwitchPreference(it, Defaults.PREF_CLIPBOARD_HISTORY_PINNED_FIRST)
    },
    Setting(context, Settings.PREF_VIBRATION_DURATION_SETTINGS, R.string.prefs_keypress_vibration_duration_settings) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_VIBRATION_DURATION_SETTINGS,
            description = {
                if (it < 0) stringResource(R.string.settings_system_default)
                else stringResource(R.string.abbreviation_unit_milliseconds, it.toString())
            },
            range = -1f..100f,
            // preview through the same path as real keypresses, honoring the current intensity
            onValueChanged = { it?.let { v ->
                val intensity = ctx.prefs().getInt(Settings.PREF_VIBRATION_INTENSITY, Defaults.PREF_VIBRATION_INTENSITY)
                AudioAndHapticFeedbackManager.getInstance().vibratePreview(v.toInt(), intensity)
            } }
        )
    },
    Setting(context, Settings.PREF_VIBRATION_INTENSITY, R.string.prefs_keypress_vibration_intensity_settings) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_VIBRATION_INTENSITY,
            description = {
                if (it < 0) stringResource(R.string.settings_system_default)
                else if (it == 0) stringResource(R.string.vibration_intensity_off)
                else "$it%"
            },
            range = -1f..100f,
            // preview through the same path as real keypresses, honoring the current duration
            onValueChanged = { it?.let { v ->
                val duration = ctx.prefs().getInt(Settings.PREF_VIBRATION_DURATION_SETTINGS, Defaults.PREF_VIBRATION_DURATION_SETTINGS)
                AudioAndHapticFeedbackManager.getInstance().vibratePreview(duration, v.toInt())
            } }
        )
    },
    Setting(context, Settings.PREF_KEYPRESS_SOUND_VOLUME, R.string.prefs_keypress_sound_volume_settings) { setting ->
        val audioManager = LocalContext.current.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_KEYPRESS_SOUND_VOLUME,
            description = {
                if (it < 0) stringResource(R.string.settings_system_default)
                else (it * 100).toInt().toString()
            },
            range = -0.01f..1f,
            onValueChanged = { it?.let { audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, it) } }
        )
    },
)

// todo (later): not good to have it hardcoded, but reading a bunch of files may be noticeably slow
private val localesWithLocalizedNumberRow = listOf("ar", "bn", "fa", "gu", "hi", "kn", "mr", "ne", "ur")

@Composable
private fun retentionLabel(minutes: Int) = when {
    minutes < 60 -> stringResource(R.string.abbreviation_unit_minutes, minutes.toString())
    minutes < 24 * 60 -> stringResource(R.string.abbreviation_unit_hours, (minutes / 60).toString())
    else -> stringResource(R.string.abbreviation_unit_days, (minutes / (24 * 60)).toString())
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            PreferencesScreen { }
        }
    }
}
