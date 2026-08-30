// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.voice.AiProvider
import helium314.keyboard.latin.voice.ModelCatalog
import helium314.keyboard.latin.voice.SecretStore
import helium314.keyboard.latin.voice.apiKeyPrefKey
import helium314.keyboard.latin.voice.isValidCustomModelSlug
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.ModelListPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.settings.preferences.rememberBooleanPreferenceState
import helium314.keyboard.settings.preferences.rememberStringPreferenceState

@Composable
fun TextFixScreen(
    onClickBack: () -> Unit,
) {
    val enabled by rememberBooleanPreferenceState(
        Settings.PREF_TEXT_FIX_ENABLED,
        Defaults.PREF_TEXT_FIX_ENABLED
    )
    val secondEnabled by rememberBooleanPreferenceState(
        Settings.PREF_TEXT_FIX_2_ENABLED,
        Defaults.PREF_TEXT_FIX_2_ENABLED
    )
    val model by rememberStringPreferenceState(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL)
    val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
    val provider = AiProvider.fromPref(providerPref)

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_text_fix),
        settings = listOf(
            Settings.PREF_TEXT_FIX_ENABLED,
            if (enabled) Settings.PREF_AI_PROVIDER else null,
            if (enabled) provider.apiKeyPrefKey() else null,
            if (enabled && provider == AiProvider.OPENROUTER) Settings.PREF_OPENROUTER_ZDR_ENABLED else null,
            if (enabled) Settings.PREF_TEXT_FIX_MODEL else null,
            if (enabled && model == "custom") Settings.PREF_TEXT_FIX_MODEL_CUSTOM else null,
            if (enabled) Settings.PREF_TEXT_FIX_PROMPT else null,
            Settings.PREF_TEXT_FIX_2_ENABLED,
            if (secondEnabled) Settings.PREF_TEXT_FIX_2_PROMPT else null,
        )
    )
}

/**
 * The enable switch for a Text Fix button, gated on secure storage plus a one-time privacy
 * disclosure. Both buttons hit the same network path with the same API key, so both need the same
 * gate — the second one used to skip it entirely and could be turned on with neither check.
 */
@Composable
private fun TextFixEnableSwitch(setting: Setting, default: Boolean) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val secureStorageMessage = stringResource(R.string.voice_error_secure_storage_unavailable)
    val showPrivacyDialog = rememberSaveable { mutableStateOf(false) }
    if (showPrivacyDialog.value) {
        ConfirmationDialog(
            onDismissRequest = { showPrivacyDialog.value = false },
            onConfirmed = {
                showPrivacyDialog.value = false
                prefs.edit { putBoolean(setting.key, true) }
                // The flag is read when the keyboard is parsed and baked into the layout cache,
                // so without this the button stays absent until the next theme change.
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            title = { Text(stringResource(R.string.text_fix_enable_privacy_title)) },
            content = { Text(stringResource(R.string.text_fix_enable_privacy_message)) },
            confirmButtonText = stringResource(R.string.text_fix_enable_privacy_confirm),
        )
    }
    SwitchPreference(
        setting,
        default,
        allowCheckedChange = { enabling ->
            if (!enabling) true
            else if (!SecretStore.isSecureStorageAvailable(ctx)) {
                Toast.makeText(ctx, secureStorageMessage, Toast.LENGTH_SHORT).show()
                false
            } else if (prefs.getBoolean(Settings.PREF_TEXT_FIX_ENABLED, Defaults.PREF_TEXT_FIX_ENABLED)
                || prefs.getBoolean(Settings.PREF_TEXT_FIX_2_ENABLED, Defaults.PREF_TEXT_FIX_2_ENABLED)) {
                // The other button is already on, so the disclosure has been accepted. Don't make
                // the user re-read it to enable the second one.
                true
            } else {
                showPrivacyDialog.value = true
                false
            }
        },
        onCheckedChange = { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    )
}

fun createTextFixSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_TEXT_FIX_ENABLED, R.string.text_fix_enabled, R.string.text_fix_enabled_summary) { setting ->
        TextFixEnableSwitch(setting, Defaults.PREF_TEXT_FIX_ENABLED)
    },
    Setting(context, Settings.PREF_TEXT_FIX_MODEL, R.string.text_fix_model) { setting ->
        val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
        val entries = when (AiProvider.fromPref(providerPref)) {
            AiProvider.OPENROUTER -> ModelCatalog.OPENROUTER_TEXT_FIX
            AiProvider.PAYPERQ -> ModelCatalog.PAYPERQ_TEXT_FIX
        }
        ModelListPreference(setting, entries, Defaults.PREF_TEXT_FIX_MODEL)
    },
    Setting(context, Settings.PREF_TEXT_FIX_MODEL_CUSTOM, R.string.text_fix_model_custom, R.string.text_fix_model_custom_summary) {
        TextInputPreference(it, Defaults.PREF_TEXT_FIX_MODEL_CUSTOM, checkTextValid = ::isValidCustomModelSlug)
    },
    Setting(context, Settings.PREF_TEXT_FIX_PROMPT, R.string.text_fix_prompt, R.string.text_fix_prompt_summary) {
        val prefs = LocalContext.current.prefs()
        TextInputPreference(
            setting = it,
            default = Defaults.PREF_TEXT_FIX_PROMPT,
            singleLine = false,
            neutralButtonText = stringResource(R.string.button_default),
            onNeutral = { prefs.edit { remove(Settings.PREF_TEXT_FIX_PROMPT) } },
            checkTextValid = { text -> text.isNotBlank() }
        )
    },
    Setting(context, Settings.PREF_TEXT_FIX_2_ENABLED, R.string.text_fix_2_enabled, R.string.text_fix_2_enabled_summary) {
        TextFixEnableSwitch(it, Defaults.PREF_TEXT_FIX_2_ENABLED)
    },
    Setting(context, Settings.PREF_TEXT_FIX_2_PROMPT, R.string.text_fix_2_prompt, R.string.text_fix_2_prompt_summary) {
        val prefs = LocalContext.current.prefs()
        TextInputPreference(
            setting = it,
            default = Defaults.PREF_TEXT_FIX_2_PROMPT,
            singleLine = false,
            neutralButtonText = stringResource(R.string.button_default),
            onNeutral = { prefs.edit { remove(Settings.PREF_TEXT_FIX_2_PROMPT) } },
            checkTextValid = { text -> text.isNotBlank() }
        )
    },
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            TextFixScreen { }
        }
    }
}
