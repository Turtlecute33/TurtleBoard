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
import helium314.keyboard.latin.voice.parseTranslateLanguages
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ModelListPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.settings.preferences.rememberBooleanPreferenceState
import helium314.keyboard.settings.preferences.rememberStringPreferenceState

@Composable
fun TranslateScreen(
    onClickBack: () -> Unit,
) {
    val enabled by rememberBooleanPreferenceState(
        Settings.PREF_TRANSLATE_ENABLED,
        Defaults.PREF_TRANSLATE_ENABLED
    )
    val model by rememberStringPreferenceState(Settings.PREF_TRANSLATE_MODEL, Defaults.PREF_TRANSLATE_MODEL)
    val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
    val provider = AiProvider.fromPref(providerPref)

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_translate),
        settings = listOf(
            Settings.PREF_TRANSLATE_ENABLED,
            if (enabled) Settings.PREF_TRANSLATE_LANGUAGES else null,
            if (enabled) Settings.PREF_AI_PROVIDER else null,
            if (enabled) provider.apiKeyPrefKey() else null,
            if (enabled && provider == AiProvider.OPENROUTER) Settings.PREF_OPENROUTER_ZDR_ENABLED else null,
            if (enabled) Settings.PREF_TRANSLATE_MODEL else null,
            if (enabled && model == "custom") Settings.PREF_TRANSLATE_MODEL_CUSTOM else null,
            if (enabled) Settings.PREF_TRANSLATE_PROMPT else null,
        )
    )
}

/**
 * The enable switch, gated on secure storage plus a one-time privacy disclosure — the same gate
 * Text Fix uses, because it is the same network path with the same user-supplied API key.
 */
@Composable
private fun TranslateEnableSwitch(setting: Setting) {
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
                // so without this the Translate key stays absent until the next theme change.
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            title = { Text(stringResource(R.string.translate_enable_privacy_title)) },
            content = { Text(stringResource(R.string.translate_enable_privacy_message)) },
            confirmButtonText = stringResource(R.string.translate_enable_privacy_confirm),
        )
    }
    SwitchPreference(
        setting,
        Defaults.PREF_TRANSLATE_ENABLED,
        allowCheckedChange = { enabling ->
            if (!enabling) true
            else if (!SecretStore.isSecureStorageAvailable(ctx)) {
                Toast.makeText(ctx, secureStorageMessage, Toast.LENGTH_SHORT).show()
                false
            } else if (prefs.getBoolean(Settings.PREF_TEXT_FIX_ENABLED, Defaults.PREF_TEXT_FIX_ENABLED)
                || prefs.getBoolean(Settings.PREF_TEXT_FIX_2_ENABLED, Defaults.PREF_TEXT_FIX_2_ENABLED)) {
                // Text Fix is already on, so the identical disclosure has been accepted.
                true
            } else {
                showPrivacyDialog.value = true
                false
            }
        },
        onCheckedChange = { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    )
}

fun createTranslateSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_TRANSLATE_ENABLED, R.string.translate_enabled, R.string.translate_enabled_summary) {
        TranslateEnableSwitch(it)
    },
    Setting(context, Settings.PREF_TRANSLATE_LANGUAGES, R.string.translate_languages, R.string.translate_languages_summary) {
        val prefs = LocalContext.current.prefs()
        TextInputPreference(
            setting = it,
            default = Defaults.PREF_TRANSLATE_LANGUAGES,
            info = stringResource(R.string.translate_languages_hint),
            singleLine = false,
            neutralButtonText = stringResource(R.string.button_default),
            onNeutral = { prefs.edit { remove(Settings.PREF_TRANSLATE_LANGUAGES) } },
            // At least one usable language, or the middle menu would open empty.
            checkTextValid = { text -> parseTranslateLanguages(text).isNotEmpty() }
        )
    },
    Setting(context, Settings.PREF_TRANSLATE_MODEL, R.string.translate_model) { setting ->
        val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
        val entries = when (AiProvider.fromPref(providerPref)) {
            AiProvider.OPENROUTER -> ModelCatalog.OPENROUTER_TEXT_FIX
            AiProvider.PAYPERQ -> ModelCatalog.PAYPERQ_TEXT_FIX
        }
        ModelListPreference(setting, entries, Defaults.PREF_TRANSLATE_MODEL)
    },
    Setting(context, Settings.PREF_TRANSLATE_MODEL_CUSTOM, R.string.translate_model_custom, R.string.translate_model_custom_summary) {
        TextInputPreference(it, Defaults.PREF_TRANSLATE_MODEL_CUSTOM, checkTextValid = ::isValidCustomModelSlug)
    },
    Setting(context, Settings.PREF_TRANSLATE_PROMPT, R.string.translate_prompt, R.string.translate_prompt_summary) {
        val prefs = LocalContext.current.prefs()
        TextInputPreference(
            setting = it,
            default = Defaults.PREF_TRANSLATE_PROMPT,
            singleLine = false,
            neutralButtonText = stringResource(R.string.button_default),
            onNeutral = { prefs.edit { remove(Settings.PREF_TRANSLATE_PROMPT) } },
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
            TranslateScreen { }
        }
    }
}
