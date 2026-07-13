// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.voice.AiProvider
import helium314.keyboard.latin.voice.ModelCatalog
import helium314.keyboard.latin.voice.OpenRouterClient
import helium314.keyboard.latin.voice.PolishLevel
import helium314.keyboard.latin.voice.parseVoiceDictionaryTerms
import helium314.keyboard.latin.voice.parseExpectedLanguages
import helium314.keyboard.latin.voice.resolveVoiceModel
import helium314.keyboard.latin.voice.SecretStore
import helium314.keyboard.latin.voice.apiKeyPrefKey
import helium314.keyboard.latin.voice.defaultApiKey
import helium314.keyboard.latin.voice.isValidCustomModelSlug
import helium314.keyboard.latin.voice.supportsOpenRouterSttSlug
import helium314.keyboard.latin.voice.supportsTextFixSlug
import helium314.keyboard.latin.voice.supportsVoiceSlug
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.ModelListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.settings.preferences.rememberBooleanPreferenceState
import helium314.keyboard.settings.preferences.rememberStringPreferenceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun VoiceScreen(
    onClickBack: () -> Unit,
) {
    val voiceInputEnabled by rememberBooleanPreferenceState(
        Settings.PREF_VOICE_INPUT_ENABLED,
        Defaults.PREF_VOICE_INPUT_ENABLED
    )
    val voiceModel by rememberStringPreferenceState(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
    val sttModel by rememberStringPreferenceState(Settings.PREF_VOICE_STT_MODEL, Defaults.PREF_VOICE_STT_MODEL)
    val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
    val traditionalEnabled by rememberBooleanPreferenceState(
        Settings.PREF_VOICE_TRADITIONAL_BUTTON_ENABLED,
        Defaults.PREF_VOICE_TRADITIONAL_BUTTON_ENABLED
    )
    val sttEnabled by rememberBooleanPreferenceState(Settings.PREF_VOICE_STT_ENABLED, Defaults.PREF_VOICE_STT_ENABLED)
    val voiceAutoStop by rememberBooleanPreferenceState(
        Settings.PREF_VOICE_AUTO_STOP_SILENCE,
        Defaults.PREF_VOICE_AUTO_STOP_SILENCE
    )
    val autoPolishEnabled by rememberBooleanPreferenceState(
        Settings.PREF_VOICE_AUTO_POLISH_ENABLED,
        Defaults.PREF_VOICE_AUTO_POLISH_ENABLED
    )
    val polishModel by rememberStringPreferenceState(
        Settings.PREF_VOICE_POLISH_MODEL,
        Defaults.PREF_VOICE_POLISH_MODEL
    )

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_voice),
        settings = buildVoiceScreenItems(
            voiceInputEnabled = voiceInputEnabled,
            voiceModel = voiceModel,
            sttModel = sttModel,
            provider = AiProvider.fromPref(providerPref),
            traditionalEnabled = traditionalEnabled,
            sttEnabled = sttEnabled,
            voiceAutoStop = voiceAutoStop,
            autoPolishEnabled = autoPolishEnabled,
            polishModel = polishModel,
        )
    )
}

internal fun buildVoiceScreenItems(
    voiceInputEnabled: Boolean,
    voiceModel: String,
    sttModel: String = Defaults.PREF_VOICE_STT_MODEL,
    provider: AiProvider = AiProvider.OPENROUTER,
    traditionalEnabled: Boolean = Defaults.PREF_VOICE_TRADITIONAL_BUTTON_ENABLED,
    sttEnabled: Boolean = Defaults.PREF_VOICE_STT_ENABLED,
    voiceAutoStop: Boolean = Defaults.PREF_VOICE_AUTO_STOP_SILENCE,
    autoPolishEnabled: Boolean = Defaults.PREF_VOICE_AUTO_POLISH_ENABLED,
    polishModel: String = Defaults.PREF_VOICE_POLISH_MODEL,
): List<Any?> = listOf(
    Settings.PREF_VOICE_INPUT_ENABLED,
    if (voiceInputEnabled) Settings.PREF_AI_PROVIDER else null,
    if (voiceInputEnabled) provider.apiKeyPrefKey() else null,
    if (voiceInputEnabled && provider == AiProvider.OPENROUTER) Settings.PREF_OPENROUTER_ZDR_ENABLED else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_ACTION_TEST_KEY else null,
    // Traditional voice (chat-audio) subsection — independent of STT below.
    if (voiceInputEnabled) R.string.voice_traditional_category else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_TRADITIONAL_BUTTON_ENABLED else null,
    if (voiceInputEnabled && traditionalEnabled) Settings.PREF_VOICE_MODEL else null,
    if (voiceInputEnabled && traditionalEnabled && voiceModel == "custom") Settings.PREF_VOICE_MODEL_CUSTOM else null,
    if (voiceInputEnabled && traditionalEnabled) Settings.PREF_VOICE_ACTION_PROMPT_PRESET else null,
    if (voiceInputEnabled && traditionalEnabled) Settings.PREF_VOICE_TRANSCRIPTION_PROMPT else null,
    if (voiceInputEnabled && traditionalEnabled) Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY else null,
    if (voiceInputEnabled && traditionalEnabled) Settings.PREF_VOICE_EXPECTED_LANGUAGES else null,
    // Dedicated STT subsection — fully independent toggle and settings.
    if (voiceInputEnabled && provider == AiProvider.OPENROUTER) R.string.voice_stt_category else null,
    if (voiceInputEnabled && provider == AiProvider.OPENROUTER) Settings.PREF_VOICE_STT_ENABLED else null,
    if (voiceInputEnabled && provider == AiProvider.OPENROUTER && sttEnabled) Settings.PREF_VOICE_STT_MODEL else null,
    if (voiceInputEnabled && provider == AiProvider.OPENROUTER && sttEnabled && sttModel == "custom") Settings.PREF_VOICE_STT_MODEL_CUSTOM else null,
    if (voiceInputEnabled && provider == AiProvider.OPENROUTER && sttEnabled) Settings.PREF_VOICE_STT_PROMPT else null,
    if (voiceInputEnabled && provider == AiProvider.OPENROUTER && sttEnabled) Settings.PREF_VOICE_STT_DICTIONARY else null,
    if (voiceInputEnabled && provider == AiProvider.OPENROUTER && sttEnabled) Settings.PREF_VOICE_STT_EXPECTED_LANGUAGES else null,
    // Auto-polish: a second LLM pass that cleans up the raw transcription. Applies to both the
    // chat-audio and dedicated-STT flows, hence its placement above the shared section.
    if (voiceInputEnabled) R.string.voice_polish_category else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_AUTO_POLISH_ENABLED else null,
    if (voiceInputEnabled && autoPolishEnabled) Settings.PREF_VOICE_POLISH_LEVEL else null,
    if (voiceInputEnabled && autoPolishEnabled) Settings.PREF_VOICE_POLISH_MODEL else null,
    if (voiceInputEnabled && autoPolishEnabled && polishModel == "custom") Settings.PREF_VOICE_POLISH_MODEL_CUSTOM else null,
    // Shared playback / capture options apply to both flows.
    if (voiceInputEnabled) R.string.voice_shared_category else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_LANGUAGE_HINT else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_SPACE_HEURISTIC else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_HAPTIC_FEEDBACK else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_MIC_SENSITIVITY else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_MAX_DURATION_SECONDS else null,
    if (voiceInputEnabled) Settings.PREF_VOICE_AUTO_STOP_SILENCE else null,
    if (voiceInputEnabled && voiceAutoStop) Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS else null,
)

fun createVoiceSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_VOICE_INPUT_ENABLED, R.string.voice_input_enabled, R.string.voice_input_enabled_summary) { setting ->
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        val permissionDeniedMessage = stringResource(R.string.voice_error_no_permission)
        val secureStorageMessage = stringResource(R.string.voice_error_secure_storage_unavailable)
        // rememberSaveable so the in-progress enable flow survives a rotation mid-dialog.
        var showRationale by rememberSaveable { mutableStateOf(false) }
        var showPrivacyDialog by rememberSaveable { mutableStateOf(false) }
        // Act on the grant result directly in the launcher callback. ActivityResult delivery survives
        // a rotation that recreates the activity while the system permission dialog is up; a separately
        // remembered callback lambda would be lost, silently dropping the enable.
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                prefs.edit { putBoolean(setting.key, true) }
            } else {
                Toast.makeText(ctx, permissionDeniedMessage, Toast.LENGTH_SHORT).show()
            }
        }

        fun enableAfterPrivacyConfirmation() {
            if (!SecretStore.isSecureStorageAvailable(ctx)) {
                Toast.makeText(ctx, secureStorageMessage, Toast.LENGTH_SHORT).show()
                return
            }
            if (PermissionsUtil.checkAllPermissionsGranted(ctx, Manifest.permission.RECORD_AUDIO)) {
                prefs.edit { putBoolean(setting.key, true) }
                return
            }
            showRationale = true
        }

        if (showPrivacyDialog) {
            ConfirmationDialog(
                onDismissRequest = { showPrivacyDialog = false },
                onConfirmed = {
                    showPrivacyDialog = false
                    enableAfterPrivacyConfirmation()
                },
                title = { Text(stringResource(R.string.voice_enable_privacy_title)) },
                content = { Text(stringResource(R.string.voice_enable_privacy_message)) },
                confirmButtonText = stringResource(R.string.voice_enable_privacy_confirm),
            )
        }

        if (showRationale) {
            ConfirmationDialog(
                onDismissRequest = { showRationale = false },
                onConfirmed = {
                    showRationale = false
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                title = { Text(stringResource(R.string.voice_mic_rationale_title)) },
                content = { Text(stringResource(R.string.voice_mic_rationale_message)) },
                confirmButtonText = stringResource(R.string.voice_mic_rationale_confirm),
            )
        }

        SwitchPreference(
            setting,
            Defaults.PREF_VOICE_INPUT_ENABLED,
            allowCheckedChange = { enabled ->
                if (!enabled) {
                    return@SwitchPreference true
                }
                if (!SecretStore.isSecureStorageAvailable(ctx)) {
                    Toast.makeText(ctx, secureStorageMessage, Toast.LENGTH_SHORT).show()
                    return@SwitchPreference false
                }
                showPrivacyDialog = true
                false
            }
        )
    },
    Setting(context, Settings.PREF_OPENROUTER_API_KEY, R.string.openrouter_api_key, R.string.openrouter_api_key_summary) {
        VoiceApiKeyPreference(it, AiProvider.OPENROUTER)
    },
    Setting(context, Settings.PREF_PAYPERQ_API_KEY, R.string.payperq_api_key, R.string.payperq_api_key_summary) {
        VoiceApiKeyPreference(it, AiProvider.PAYPERQ)
    },
    Setting(context, Settings.PREF_AI_PROVIDER, R.string.ai_provider) { setting ->
        val ctx = LocalContext.current
        val prefs = ctx.prefs()
        val items = listOf(
            ctx.getString(R.string.ai_provider_openrouter) to AiProvider.OPENROUTER.prefValue,
            ctx.getString(R.string.ai_provider_payperq) to AiProvider.PAYPERQ.prefValue,
        )
        ListPreference(setting, items, Defaults.PREF_AI_PROVIDER) { value ->
            val provider = AiProvider.fromPref(value)
            // Only swap the saved model when the previous one isn't valid for the new provider:
            // we don't want to wipe a deliberate user selection just because they re-picked the
            // same provider, or switched away and back. The defaults are slugs supported by
            // both providers, so a single fallback works either way.
            val currentVoice = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
                ?: Defaults.PREF_VOICE_MODEL
            val currentStt = prefs.getString(Settings.PREF_VOICE_STT_MODEL, Defaults.PREF_VOICE_STT_MODEL)
                ?: Defaults.PREF_VOICE_STT_MODEL
            val currentTextFix = prefs.getString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL)
                ?: Defaults.PREF_TEXT_FIX_MODEL
            val currentPolish = prefs.getString(Settings.PREF_VOICE_POLISH_MODEL, Defaults.PREF_VOICE_POLISH_MODEL)
                ?: Defaults.PREF_VOICE_POLISH_MODEL
            prefs.edit {
                if (!provider.supportsVoiceSlug(currentVoice)) {
                    putString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
                }
                if (provider != AiProvider.OPENROUTER || !supportsOpenRouterSttSlug(currentStt)) {
                    putString(Settings.PREF_VOICE_STT_MODEL, Defaults.PREF_VOICE_STT_MODEL)
                }
                if (!provider.supportsTextFixSlug(currentTextFix)) {
                    putString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL)
                }
                if (!provider.supportsTextFixSlug(currentPolish)) {
                    putString(Settings.PREF_VOICE_POLISH_MODEL, Defaults.PREF_VOICE_POLISH_MODEL)
                }
            }
        }
    },
    Setting(context, Settings.PREF_OPENROUTER_ZDR_ENABLED, R.string.openrouter_zdr_enabled, R.string.openrouter_zdr_enabled_summary) {
        SwitchPreference(it, Defaults.PREF_OPENROUTER_ZDR_ENABLED)
    },
    Setting(context, Settings.PREF_VOICE_MODEL, R.string.voice_model) { setting ->
        val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
        val entries = when (AiProvider.fromPref(providerPref)) {
            AiProvider.OPENROUTER -> ModelCatalog.OPENROUTER_VOICE
            AiProvider.PAYPERQ -> ModelCatalog.PAYPERQ_VOICE
        }
        ModelListPreference(setting, entries, Defaults.PREF_VOICE_MODEL)
    },
    Setting(context, Settings.PREF_VOICE_MODEL_CUSTOM, R.string.voice_model_custom, R.string.voice_model_custom_summary) {
        TextInputPreference(it, Defaults.PREF_VOICE_MODEL_CUSTOM, checkTextValid = ::isValidCustomModelSlug)
    },
    Setting(
        context,
        Settings.PREF_VOICE_TRADITIONAL_BUTTON_ENABLED,
        R.string.voice_traditional_button_enabled,
        R.string.voice_traditional_button_enabled_summary
    ) {
        SwitchPreference(it, Defaults.PREF_VOICE_TRADITIONAL_BUTTON_ENABLED) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_VOICE_STT_ENABLED, R.string.voice_stt_enabled, R.string.voice_stt_enabled_summary) {
        SwitchPreference(it, Defaults.PREF_VOICE_STT_ENABLED) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_VOICE_STT_MODEL, R.string.voice_stt_model) { setting ->
        ModelListPreference(setting, ModelCatalog.OPENROUTER_STT, Defaults.PREF_VOICE_STT_MODEL)
    },
    Setting(context, Settings.PREF_VOICE_STT_MODEL_CUSTOM, R.string.voice_stt_model_custom, R.string.voice_stt_model_custom_summary) {
        TextInputPreference(it, Defaults.PREF_VOICE_STT_MODEL_CUSTOM, checkTextValid = ::isValidCustomModelSlug)
    },
    Setting(
        context,
        Settings.PREF_VOICE_STT_PROMPT,
        R.string.voice_stt_prompt,
        R.string.voice_stt_prompt_summary
    ) {
        val prefs = LocalContext.current.prefs()
        TextInputPreference(
            setting = it,
            default = Defaults.PREF_VOICE_STT_PROMPT,
            singleLine = false,
            neutralButtonText = stringResource(R.string.button_default),
            onNeutral = { prefs.edit { remove(Settings.PREF_VOICE_STT_PROMPT) } },
            checkTextValid = { text -> text.isNotBlank() }
        )
    },
    Setting(
        context,
        Settings.PREF_VOICE_STT_DICTIONARY,
        R.string.voice_stt_dictionary,
        R.string.voice_stt_dictionary_summary
    ) {
        VoiceSttDictionaryPreference(it)
    },
    Setting(
        context,
        Settings.PREF_VOICE_STT_EXPECTED_LANGUAGES,
        R.string.voice_stt_expected_languages,
        R.string.voice_stt_expected_languages_summary
    ) {
        VoiceSttExpectedLanguagesPreference(it)
    },
    Setting(
        context,
        Settings.PREF_VOICE_TRANSCRIPTION_PROMPT,
        R.string.voice_transcription_prompt,
        R.string.voice_transcription_prompt_summary
    ) {
        val prefs = LocalContext.current.prefs()
        TextInputPreference(
            setting = it,
            default = Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT,
            singleLine = false,
            neutralButtonText = stringResource(R.string.button_default),
            onNeutral = { prefs.edit { remove(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT) } },
            checkTextValid = { text -> text.isNotBlank() }
        )
    },
    Setting(
        context,
        Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY,
        R.string.voice_transcription_dictionary,
        R.string.voice_transcription_dictionary_summary
    ) {
        VoiceDictionaryPreference(it)
    },
    Setting(
        context,
        Settings.PREF_VOICE_EXPECTED_LANGUAGES,
        R.string.voice_expected_languages,
        R.string.voice_expected_languages_summary
    ) {
        VoiceExpectedLanguagesPreference(it)
    },
    Setting(context, Settings.PREF_VOICE_ACTION_PROMPT_PRESET, R.string.voice_prompt_preset) {
        VoicePromptPresetPreference(it)
    },
    Setting(context, Settings.PREF_VOICE_ACTION_TEST_KEY, R.string.voice_validate_key) {
        VoiceTestKeyPreference(it)
    },
    Setting(context, Settings.PREF_VOICE_LANGUAGE_HINT, R.string.voice_language_hint, R.string.voice_language_hint_summary) {
        SwitchPreference(it, Defaults.PREF_VOICE_LANGUAGE_HINT)
    },
    Setting(context, Settings.PREF_VOICE_SPACE_HEURISTIC, R.string.voice_space_heuristic, R.string.voice_space_heuristic_summary) {
        SwitchPreference(it, Defaults.PREF_VOICE_SPACE_HEURISTIC)
    },
    Setting(context, Settings.PREF_VOICE_HAPTIC_FEEDBACK, R.string.voice_haptic_feedback, R.string.voice_haptic_feedback_summary) {
        SwitchPreference(it, Defaults.PREF_VOICE_HAPTIC_FEEDBACK)
    },
    Setting(
        context,
        Settings.PREF_VOICE_AUTO_POLISH_ENABLED,
        R.string.voice_auto_polish_enabled,
        R.string.voice_auto_polish_enabled_summary,
    ) {
        SwitchPreference(it, Defaults.PREF_VOICE_AUTO_POLISH_ENABLED)
    },
    Setting(context, Settings.PREF_VOICE_POLISH_LEVEL, R.string.voice_polish_level, R.string.voice_polish_level_summary) { setting ->
        val ctx = LocalContext.current
        // Mirror the PolishLevel enum order so the picker reads as a graded scale from
        // "do nothing" to "rewrite aggressively". Labels are translation-friendly resources.
        val items = listOf(
            ctx.getString(R.string.voice_polish_level_natural) to PolishLevel.NATURAL.prefValue,
            ctx.getString(R.string.voice_polish_level_light) to PolishLevel.LIGHT.prefValue,
            ctx.getString(R.string.voice_polish_level_fixed) to PolishLevel.FIXED.prefValue,
            ctx.getString(R.string.voice_polish_level_rephrased) to PolishLevel.REPHRASED.prefValue,
            ctx.getString(R.string.voice_polish_level_corrected) to PolishLevel.CORRECTED.prefValue,
            ctx.getString(R.string.voice_polish_level_polished) to PolishLevel.POLISHED.prefValue,
        )
        ListPreference(setting, items, Defaults.PREF_VOICE_POLISH_LEVEL)
    },
    Setting(context, Settings.PREF_VOICE_POLISH_MODEL, R.string.voice_polish_model) { setting ->
        val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
        // Polish is a chat-completion call against text, so the text-fix catalog is the right
        // model list for both providers.
        val entries = when (AiProvider.fromPref(providerPref)) {
            AiProvider.OPENROUTER -> ModelCatalog.OPENROUTER_TEXT_FIX
            AiProvider.PAYPERQ -> ModelCatalog.PAYPERQ_TEXT_FIX
        }
        ModelListPreference(setting, entries, Defaults.PREF_VOICE_POLISH_MODEL)
    },
    Setting(
        context,
        Settings.PREF_VOICE_POLISH_MODEL_CUSTOM,
        R.string.voice_polish_model_custom,
        R.string.voice_polish_model_custom_summary,
    ) {
        TextInputPreference(it, Defaults.PREF_VOICE_POLISH_MODEL_CUSTOM, checkTextValid = ::isValidCustomModelSlug)
    },
    Setting(context, Settings.PREF_VOICE_MIC_SENSITIVITY, R.string.voice_mic_sensitivity, R.string.voice_mic_sensitivity_summary) { setting ->
        val ctx = LocalContext.current
        val items = listOf(
            ctx.getString(R.string.voice_mic_sensitivity_normal) to "normal",
            ctx.getString(R.string.voice_mic_sensitivity_high) to "high",
            ctx.getString(R.string.voice_mic_sensitivity_max) to "max",
        )
        ListPreference(setting, items, Defaults.PREF_VOICE_MIC_SENSITIVITY)
    },
    Setting(context, Settings.PREF_VOICE_MAX_DURATION_SECONDS, R.string.voice_max_duration, R.string.voice_max_duration_summary) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_VOICE_MAX_DURATION_SECONDS,
            description = { stringResource(R.string.voice_max_duration_seconds, it) },
            range = 15f..300f,
        )
    },
    Setting(context, Settings.PREF_VOICE_AUTO_STOP_SILENCE, R.string.voice_auto_stop_silence, R.string.voice_auto_stop_silence_summary) {
        SwitchPreference(it, Defaults.PREF_VOICE_AUTO_STOP_SILENCE)
    },
    Setting(context, Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS, R.string.voice_auto_stop_silence_seconds) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS,
            description = { stringResource(R.string.voice_max_duration_seconds, it) },
            range = 1f..10f,
        )
    },
)

@Composable
private fun VoiceApiKeyPreference(setting: Setting, provider: AiProvider) {
    val ctx = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var storedLength by remember {
        mutableIntStateOf(SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey()).length)
    }
    Preference(
        name = setting.title,
        onClick = {
            if (!SecretStore.isSecureStorageAvailable(ctx)) {
                Toast.makeText(ctx, R.string.voice_error_secure_storage_unavailable, Toast.LENGTH_SHORT).show()
                return@Preference
            }
            showDialog = true
        },
        // Mask the key but reflect its length so the user can spot accidental truncation
        // ("did I paste the whole thing?") without ever exposing the value itself. Capped to
        // keep the row layout stable.
        description = if (storedLength > 0) "•".repeat(storedLength.coerceIn(8, 24)) else setting.description,
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                SecretStore.setApiKey(ctx, provider.apiKeyPrefKey(), it.trim())
                storedLength = it.trim().length
            },
            initialText = SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey()),
            title = { Text(setting.title) },
            singleLine = true,
            isPassword = true,
        )
    }
}

@Composable
private fun VoiceDictionaryPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val rawValue by rememberStringPreferenceState(
        Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY,
        Defaults.PREF_VOICE_TRANSCRIPTION_DICTIONARY
    )
    val displayValue = parseVoiceDictionaryTerms(rawValue).joinToString(", ")
    Preference(
        name = setting.title,
        description = displayValue.ifEmpty { setting.description },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { value ->
                prefs.edit {
                    putString(
                        Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY,
                        parseVoiceDictionaryTerms(value).joinToString(", ")
                    )
                }
            },
            initialText = rawValue,
            title = { Text(setting.title) },
            description = { Text(setting.description ?: "") },
            singleLine = false,
            checkTextValid = { true },
        )
    }
}

@Composable
private fun VoiceSttDictionaryPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val rawValue by rememberStringPreferenceState(
        Settings.PREF_VOICE_STT_DICTIONARY,
        Defaults.PREF_VOICE_STT_DICTIONARY
    )
    val displayValue = parseVoiceDictionaryTerms(rawValue).joinToString(", ")
    Preference(
        name = setting.title,
        description = displayValue.ifEmpty { setting.description },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { value ->
                prefs.edit {
                    putString(
                        Settings.PREF_VOICE_STT_DICTIONARY,
                        parseVoiceDictionaryTerms(value).joinToString(", ")
                    )
                }
            },
            initialText = rawValue,
            title = { Text(setting.title) },
            description = { Text(setting.description ?: "") },
            singleLine = false,
            checkTextValid = { true },
        )
    }
}

@Composable
private fun VoiceSttExpectedLanguagesPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val rawValue by rememberStringPreferenceState(
        Settings.PREF_VOICE_STT_EXPECTED_LANGUAGES,
        Defaults.PREF_VOICE_STT_EXPECTED_LANGUAGES
    )
    val displayValue = parseExpectedLanguages(rawValue).joinToString(", ")
    Preference(
        name = setting.title,
        description = displayValue.ifEmpty { setting.description },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { value ->
                prefs.edit {
                    putString(
                        Settings.PREF_VOICE_STT_EXPECTED_LANGUAGES,
                        parseExpectedLanguages(value).joinToString(", ")
                    )
                }
            },
            initialText = rawValue,
            title = { Text(setting.title) },
            description = { Text(setting.description ?: "") },
            singleLine = false,
            checkTextValid = { true },
        )
    }
}

@Composable
private fun VoicePromptPresetPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var pendingPresetTextRes by rememberSaveable { mutableStateOf<Int?>(null) }
    Preference(name = setting.title, onClick = { showDialog = true })
    if (showDialog) {
        // Keep items as primitive Ints so LazyColumn's key is Saveable — wrapping them in a
        // local data class crashes the dialog on selection.
        val labelToText = mapOf(
            R.string.voice_prompt_preset_verbatim to R.string.voice_prompt_preset_verbatim_text,
            R.string.voice_prompt_preset_clean to R.string.voice_prompt_preset_clean_text,
            R.string.voice_prompt_preset_punctuated to R.string.voice_prompt_preset_punctuated_text,
            R.string.voice_prompt_preset_translate_en to R.string.voice_prompt_preset_translate_en_text,
        )
        ListPickerDialog(
            onDismissRequest = { showDialog = false },
            items = labelToText.keys.toList(),
            onItemSelected = { labelRes ->
                val textRes = labelToText[labelRes] ?: return@ListPickerDialog
                val newText = ctx.getString(textRes)
                val current = prefs.getString(
                    Settings.PREF_VOICE_TRANSCRIPTION_PROMPT,
                    Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
                ) ?: Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
                if (current.isBlank() || current == Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT) {
                    prefs.edit {
                        putString(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT, newText)
                    }
                } else {
                    pendingPresetTextRes = textRes
                }
                showDialog = false
            },
            title = { Text(ctx.getString(R.string.voice_prompt_preset)) },
            getItemName = { ctx.getString(it) },
        )
    }
    pendingPresetTextRes?.let { textRes ->
        ConfirmationDialog(
            onDismissRequest = { pendingPresetTextRes = null },
            onConfirmed = {
                prefs.edit {
                    putString(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT, ctx.getString(textRes))
                }
                pendingPresetTextRes = null
            },
            title = { Text(stringResource(R.string.voice_prompt_preset_overwrite_title)) },
            content = { Text(stringResource(R.string.voice_prompt_preset_overwrite_message)) },
        )
    }
}

@Composable
private fun VoiceExpectedLanguagesPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val rawValue by rememberStringPreferenceState(
        Settings.PREF_VOICE_EXPECTED_LANGUAGES,
        Defaults.PREF_VOICE_EXPECTED_LANGUAGES
    )
    val displayValue = parseExpectedLanguages(rawValue).joinToString(", ")
    Preference(
        name = setting.title,
        description = displayValue.ifEmpty { setting.description },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { value ->
                prefs.edit {
                    putString(
                        Settings.PREF_VOICE_EXPECTED_LANGUAGES,
                        parseExpectedLanguages(value).joinToString(", ")
                    )
                }
            },
            initialText = rawValue,
            title = { Text(setting.title) },
            description = { Text(setting.description ?: "") },
            singleLine = false,
            checkTextValid = { true },
        )
    }
}

@Composable
private fun VoiceTestKeyPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val scope = rememberCoroutineScope()
    // Deliberately not rememberSaveable: the probe can't survive process death, so restoring
    // busy=true would leave the UI stuck. rememberCoroutineScope() cancels on dispose, which
    // is enough to abandon the in-flight request on navigation.
    var busy by remember { mutableStateOf(false) }
    Preference(
        name = setting.title,
        description = if (busy) stringResource(R.string.voice_test_key_testing) else null,
        onClick = {
            if (busy) return@Preference
            if (!SecretStore.isSecureStorageAvailable(ctx)) {
                Toast.makeText(ctx, R.string.voice_error_secure_storage_unavailable, Toast.LENGTH_SHORT).show()
                return@Preference
            }
            val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
            val apiKey = SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey())
            if (apiKey.isBlank()) {
                Toast.makeText(ctx, R.string.voice_error_no_api_key, Toast.LENGTH_SHORT).show()
                return@Preference
            }
            val selectedModel = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL) ?: Defaults.PREF_VOICE_MODEL
            val customModel = prefs.getString(Settings.PREF_VOICE_MODEL_CUSTOM, Defaults.PREF_VOICE_MODEL_CUSTOM) ?: ""
            val model = resolveVoiceModel(selectedModel, customModel)
            if (model == null) {
                Toast.makeText(ctx, R.string.voice_error_no_model, Toast.LENGTH_SHORT).show()
                return@Preference
            }
            val useZdr = provider == AiProvider.OPENROUTER &&
                prefs.getBoolean(Settings.PREF_OPENROUTER_ZDR_ENABLED, Defaults.PREF_OPENROUTER_ZDR_ENABLED)
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { probeApiKey(provider, apiKey, model, useZdr) }
                val msgRes = when (result) {
                    TestResult.OK -> R.string.voice_test_key_success
                    TestResult.OK_ZDR_UNAVAILABLE -> R.string.voice_test_key_success_zdr_unavailable
                    TestResult.INVALID -> R.string.voice_test_key_invalid
                    TestResult.INVALID_MODEL -> R.string.voice_test_key_invalid_model
                    TestResult.NETWORK -> R.string.voice_test_key_network_error
                }
                Toast.makeText(ctx, msgRes, Toast.LENGTH_SHORT).show()
                busy = false
            }
        }
    )
}

private enum class TestResult { OK, OK_ZDR_UNAVAILABLE, INVALID, INVALID_MODEL, NETWORK }

private fun probeApiKey(provider: AiProvider, apiKey: String, model: String, useZdr: Boolean): TestResult {
    if (provider == AiProvider.PAYPERQ) return probePayPerQApiKey(apiKey, model)
    val keyConn = (java.net.URL(OpenRouterClient.KEY_ENDPOINT).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $apiKey")
        OpenRouterClient.applyOpenRouterAttributionHeaders(this)
        connectTimeout = OpenRouterClient.DEFAULT_CONNECT_TIMEOUT_MS
        readTimeout = 10_000
    }
    return try {
        when (keyConn.responseCode) {
            200 -> probeModel(apiKey, model, useZdr)
            401, 403 -> TestResult.INVALID
            else -> TestResult.NETWORK
        }
    } catch (_: Exception) {
        TestResult.NETWORK
    } finally {
        keyConn.disconnect()
    }
}

private fun probePayPerQApiKey(apiKey: String, model: String): TestResult {
    if (model.isBlank()) return TestResult.INVALID_MODEL
    val conn = (java.net.URL(OpenRouterClient.PAYPERQ_AUDIO_MODELS_ENDPOINT).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $apiKey")
        connectTimeout = OpenRouterClient.DEFAULT_CONNECT_TIMEOUT_MS
        readTimeout = 10_000
    }
    return try {
        when (conn.responseCode) {
            200 -> TestResult.OK
            401, 403 -> TestResult.INVALID
            else -> TestResult.NETWORK
        }
    } catch (_: Exception) {
        TestResult.NETWORK
    } finally {
        conn.disconnect()
    }
}

private fun probeModel(apiKey: String, model: String, useZdr: Boolean): TestResult {
    val parts = model.trim().split("/", limit = 2)
    if (parts.size != 2 || parts.any { it.isBlank() }) {
        return TestResult.INVALID_MODEL
    }
    val author = URLEncoder.encode(parts[0], StandardCharsets.UTF_8.name())
    val slug = URLEncoder.encode(parts[1], StandardCharsets.UTF_8.name())
    val conn = (java.net.URL(OpenRouterClient.modelEndpointUrl(author, slug)).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $apiKey")
        OpenRouterClient.applyOpenRouterAttributionHeaders(this)
        connectTimeout = OpenRouterClient.DEFAULT_CONNECT_TIMEOUT_MS
        readTimeout = 10_000
    }
    return try {
        when (conn.responseCode) {
            200 -> if (useZdr && !probeZdrModelSupport(apiKey, model)) TestResult.OK_ZDR_UNAVAILABLE else TestResult.OK
            401, 403 -> TestResult.INVALID
            404 -> TestResult.INVALID_MODEL
            else -> TestResult.NETWORK
        }
    } catch (_: Exception) {
        TestResult.NETWORK
    } finally {
        conn.disconnect()
    }
}

private fun probeZdrModelSupport(apiKey: String, model: String): Boolean {
    // The catalog is the authoritative source for known slugs — its `zdr` flags are verified
    // against OpenRouter's ZDR endpoint list, and they're what the request path actually keys
    // off when deciding to send `provider.zdr: true`. Hitting `/endpoints/zdr` here used to
    // false-negative for every `~author/...-latest` alias because OpenRouter returns canonical
    // model IDs without the leading tilde, so the exact-string match never landed.
    if (ModelCatalog.openRouterSupportsZdr(model)) return true
    val conn = (java.net.URL(OpenRouterClient.ZDR_ENDPOINT).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $apiKey")
        OpenRouterClient.applyOpenRouterAttributionHeaders(this)
        connectTimeout = OpenRouterClient.DEFAULT_CONNECT_TIMEOUT_MS
        readTimeout = 10_000
    }
    return try {
        if (conn.responseCode != 200) return true
        val body = readProbeResponseCapped(conn.inputStream)
        val endpoints = JSONObject(body).optJSONArray("data") ?: return true
        // Belt-and-suspenders for custom slugs: also accept a match against the tilde-stripped
        // form, since the user can paste either shape into the Custom Model ID field.
        val tildeStripped = model.removePrefix("~")
        for (i in 0 until endpoints.length()) {
            val id = endpoints.optJSONObject(i)?.optString("model_id") ?: continue
            if (id == model || id == tildeStripped) return true
        }
        false
    } catch (_: Exception) {
        true
    } finally {
        conn.disconnect()
    }
}

private fun readProbeResponseCapped(input: java.io.InputStream): String {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    input.use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_PROBE_RESPONSE_BYTES) throw IllegalArgumentException("Probe response too large")
            out.write(buffer, 0, read)
        }
    }
    return out.toString(Charsets.UTF_8.name())
}

private const val MAX_PROBE_RESPONSE_BYTES = 512 * 1024

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            VoiceScreen { }
        }
    }
}
