// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import androidx.core.net.toUri
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.common.Links
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
import helium314.keyboard.latin.voice.SpeechEngine
import helium314.keyboard.latin.voice.isOnDeviceRecognitionAvailable
import helium314.keyboard.latin.voice.parseVoiceDictionaryTerms
import helium314.keyboard.latin.voice.parseExpectedLanguages
import helium314.keyboard.latin.voice.resolveVoiceModel
import helium314.keyboard.latin.voice.SecretStore
import helium314.keyboard.latin.voice.apiKeyPrefKey
import helium314.keyboard.latin.voice.defaultApiKey
import helium314.keyboard.latin.voice.isValidCustomModelSlug
import helium314.keyboard.latin.voice.defaultSttModel
import helium314.keyboard.latin.voice.supportsSttSlug
import helium314.keyboard.latin.voice.supportsTextFixSlug
import helium314.keyboard.latin.voice.supportsVoiceSlug
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
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
    val speechEnginePref by rememberStringPreferenceState(
        Settings.PREF_VOICE_SPEECH_ENGINE,
        Defaults.PREF_VOICE_SPEECH_ENGINE
    )
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
            speechEngine = SpeechEngine.fromPref(speechEnginePref),
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
    speechEngine: SpeechEngine = SpeechEngine.CLOUD,
    traditionalEnabled: Boolean = Defaults.PREF_VOICE_TRADITIONAL_BUTTON_ENABLED,
    sttEnabled: Boolean = Defaults.PREF_VOICE_STT_ENABLED,
    voiceAutoStop: Boolean = Defaults.PREF_VOICE_AUTO_STOP_SILENCE,
    autoPolishEnabled: Boolean = Defaults.PREF_VOICE_AUTO_POLISH_ENABLED,
    polishModel: String = Defaults.PREF_VOICE_POLISH_MODEL,
): List<Any?> {
    // Everything from the provider key down to auto-polish describes a network request. The
    // on-device engine makes none, so showing those rows would offer settings that cannot apply.
    val cloud = voiceInputEnabled && speechEngine == SpeechEngine.CLOUD
    return listOf(
        Settings.PREF_VOICE_INPUT_ENABLED,
        if (voiceInputEnabled) Settings.PREF_VOICE_SPEECH_ENGINE else null,
        if (cloud) Settings.PREF_AI_PROVIDER else null,
        if (cloud) provider.apiKeyPrefKey() else null,
        if (cloud && provider == AiProvider.OPENROUTER) Settings.PREF_OPENROUTER_ZDR_ENABLED else null,
        if (cloud) Settings.PREF_VOICE_ACTION_TEST_KEY else null,
        // Traditional voice (chat-audio) subsection — independent of STT below.
        if (cloud) R.string.voice_traditional_category else null,
        if (cloud) Settings.PREF_VOICE_TRADITIONAL_BUTTON_ENABLED else null,
        if (cloud && traditionalEnabled) Settings.PREF_VOICE_MODEL else null,
        if (cloud && traditionalEnabled && voiceModel == "custom") Settings.PREF_VOICE_MODEL_CUSTOM else null,
        if (cloud && traditionalEnabled) Settings.PREF_VOICE_ACTION_PROMPT_PRESET else null,
        if (cloud && traditionalEnabled) Settings.PREF_VOICE_TRANSCRIPTION_PROMPT else null,
        if (cloud && traditionalEnabled) Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY else null,
        if (cloud && traditionalEnabled) Settings.PREF_VOICE_EXPECTED_LANGUAGES else null,
        // Dedicated STT subsection — fully independent toggle and settings. Both providers run a
        // transcription endpoint, and on both it is the faster of the two routes.
        if (cloud) R.string.voice_stt_category else null,
        if (cloud) Settings.PREF_VOICE_STT_ENABLED else null,
        if (cloud && sttEnabled) Settings.PREF_VOICE_STT_MODEL else null,
        // PayPerQ's transcription endpoint accepts the `model` field and ignores it, so a custom
        // slug there would change nothing.
        if (cloud && sttEnabled && sttModel == "custom" && provider == AiProvider.OPENROUTER) Settings.PREF_VOICE_STT_MODEL_CUSTOM else null,
        if (cloud && sttEnabled) Settings.PREF_VOICE_STT_PROMPT else null,
        if (cloud && sttEnabled) Settings.PREF_VOICE_STT_DICTIONARY else null,
        if (cloud && sttEnabled) Settings.PREF_VOICE_STT_EXPECTED_LANGUAGES else null,
        // Auto-polish: a second LLM pass that cleans up the raw transcription. Applies to both the
        // chat-audio and dedicated-STT flows, hence its placement above the shared section.
        if (cloud) R.string.voice_polish_category else null,
        if (cloud) Settings.PREF_VOICE_AUTO_POLISH_ENABLED else null,
        if (cloud && autoPolishEnabled) Settings.PREF_VOICE_POLISH_LEVEL else null,
        if (cloud && autoPolishEnabled) Settings.PREF_VOICE_POLISH_MODEL else null,
        if (cloud && autoPolishEnabled && polishModel == "custom") Settings.PREF_VOICE_POLISH_MODEL_CUSTOM else null,
        // Shared playback / capture options apply to both flows.
        if (voiceInputEnabled) R.string.voice_shared_category else null,
        if (voiceInputEnabled) Settings.PREF_VOICE_LANGUAGE_HINT else null,
        if (voiceInputEnabled) Settings.PREF_VOICE_SPACE_HEURISTIC else null,
        if (voiceInputEnabled) Settings.PREF_VOICE_HAPTIC_FEEDBACK else null,
        if (voiceInputEnabled) Settings.PREF_VOICE_MAX_DURATION_SECONDS else null,
        // The platform recogniser owns the microphone end to end: it applies no capture gain we can
        // set, and it decides for itself when the utterance ended.
        if (cloud) Settings.PREF_VOICE_MIC_SENSITIVITY else null,
        if (cloud) Settings.PREF_VOICE_AUTO_STOP_SILENCE else null,
        if (cloud && voiceAutoStop) Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS else null,
    )
}

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
    Setting(
        context,
        Settings.PREF_VOICE_SPEECH_ENGINE,
        R.string.voice_speech_engine,
        R.string.voice_speech_engine_summary
    ) {
        VoiceSpeechEnginePreference(it)
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
                if (!provider.supportsSttSlug(currentStt)) {
                    putString(Settings.PREF_VOICE_STT_MODEL, provider.defaultSttModel())
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
    Setting(context, Settings.PREF_VOICE_STT_ENABLED, R.string.voice_stt_enabled, R.string.voice_stt_enabled_summary) { setting ->
        val prefs = LocalContext.current.prefs()
        val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
        SwitchPreference(setting, Defaults.PREF_VOICE_STT_ENABLED) { enabled ->
            // The saved slug can belong to the other provider — the section is only reachable once
            // the toggle is on, so a user who never switched provider has never had the chance to
            // pick one. Point it at something this provider offers instead of opening the picker on
            // a model it will report as unavailable.
            if (enabled) {
                val provider = AiProvider.fromPref(providerPref)
                val current = prefs.getString(Settings.PREF_VOICE_STT_MODEL, Defaults.PREF_VOICE_STT_MODEL)
                    ?: Defaults.PREF_VOICE_STT_MODEL
                if (!provider.supportsSttSlug(current)) {
                    prefs.edit { putString(Settings.PREF_VOICE_STT_MODEL, provider.defaultSttModel()) }
                }
            }
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_VOICE_STT_MODEL, R.string.voice_stt_model) { setting ->
        val providerPref by rememberStringPreferenceState(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER)
        val provider = AiProvider.fromPref(providerPref)
        val entries = when (provider) {
            AiProvider.OPENROUTER -> ModelCatalog.OPENROUTER_STT
            AiProvider.PAYPERQ -> ModelCatalog.PAYPERQ_STT
        }
        ModelListPreference(setting, entries, provider.defaultSttModel())
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

/**
 * Whether [engine] may be selected. The on-device engine is the only one a device can fail to
 * support, and selecting it there would leave every dictation failing at the microphone.
 */
internal fun canSelectSpeechEngine(engine: SpeechEngine, onDeviceAvailable: Boolean): Boolean =
    engine != SpeechEngine.ON_DEVICE || onDeviceAvailable

/** Picker label for [engine], flagging the on-device entry when the device cannot run it. */
@StringRes
internal fun speechEngineLabelRes(engine: SpeechEngine, onDeviceAvailable: Boolean): Int = when {
    engine == SpeechEngine.CLOUD -> R.string.voice_speech_engine_cloud
    onDeviceAvailable -> R.string.voice_speech_engine_on_device
    else -> R.string.voice_speech_engine_on_device_unavailable
}

/**
 * Engine picker that refuses to select the on-device engine on a device that cannot run it.
 *
 * A plain [ListPreference] would save first and complain second, leaving the user on an engine that
 * fails at every dictation. Selecting it here is blocked instead, and the user gets the steps that
 * actually make it work.
 */
@Composable
private fun VoiceSpeechEnginePreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val selectedValue by rememberStringPreferenceState(setting.key, Defaults.PREF_VOICE_SPEECH_ENGINE)
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showSetupGuide by rememberSaveable { mutableStateOf(false) }
    // Probed on demand, not on every recomposition: this queries the PackageManager, and the
    // settings list recomposes far more often than a speech service gets installed. Re-probed when
    // the picker opens so returning from the system settings screen immediately unblocks the option.
    var availabilityProbe by remember { mutableIntStateOf(0) }
    val onDeviceAvailable = remember(availabilityProbe) { isOnDeviceRecognitionAvailable(ctx) }

    val items = SpeechEngine.entries.map {
        stringResource(speechEngineLabelRes(it, onDeviceAvailable)) to it.prefValue
    }
    val selectedItem = items.firstOrNull { it.second == selectedValue }

    Preference(
        name = setting.title,
        description = selectedItem?.first,
        onClick = {
            availabilityProbe++
            showPicker = true
        },
    )
    if (showPicker) {
        ListPickerDialog(
            onDismissRequest = { showPicker = false },
            items = items,
            onItemSelected = { item ->
                // Re-probed rather than reusing onDeviceAvailable: the user may have installed a
                // speech service since the picker opened.
                if (!canSelectSpeechEngine(SpeechEngine.fromPref(item.second), isOnDeviceRecognitionAvailable(ctx))) {
                    availabilityProbe++
                    showSetupGuide = true
                    return@ListPickerDialog
                }
                if (item.second == selectedValue) return@ListPickerDialog
                prefs.edit { putString(setting.key, item.second) }
                // The engine decides which voice buttons the long-press Return menu offers.
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            selectedItem = selectedItem,
            title = { Text(setting.title) },
            getItemName = { it.first },
        )
    }
    if (showSetupGuide) {
        val noSettingsScreenMessage = stringResource(R.string.voice_on_device_settings_unavailable)
        // ThreeButtonAlertDialog rather than ConfirmationDialog: the setup steps are long enough to
        // clip on a short screen without scrollContent. "Get the app" deliberately leaves the dialog
        // open, so the user can come straight back for the settings step.
        ThreeButtonAlertDialog(
            onDismissRequest = { showSetupGuide = false },
            title = { Text(stringResource(R.string.voice_on_device_unavailable_title)) },
            content = { Text(stringResource(R.string.voice_on_device_unavailable_message)) },
            scrollContent = true,
            confirmButtonText = stringResource(R.string.voice_on_device_open_settings),
            onConfirmed = {
                // Package visibility makes resolveActivity unreliable for another app's settings
                // screen, so just try it and report the miss.
                runCatching {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                }.onFailure {
                    Toast.makeText(ctx, noSettingsScreenMessage, Toast.LENGTH_LONG).show()
                }
            },
            neutralButtonText = stringResource(R.string.voice_on_device_get_app),
            onNeutral = {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Links.ON_DEVICE_SPEECH_SERVICE.toUri()))
                }.onFailure {
                    Toast.makeText(ctx, noSettingsScreenMessage, Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

@Composable
private fun VoiceApiKeyPreference(setting: Setting, provider: AiProvider) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var storedLength by remember {
        mutableIntStateOf(SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey()).length)
    }

    /**
     * Persists the key off the main thread. `SecretStore.setApiKey` does a synchronous
     * `commit()` into EncryptedSharedPreferences, which means a disk write plus an AndroidKeyStore
     * round trip — hundreds of milliseconds on some devices, and it was landing on the UI thread.
     * The optimistic length update keeps the masked row in sync immediately either way.
     */
    fun saveApiKey(key: String) {
        storedLength = key.length
        scope.launch {
            val failed = withContext(Dispatchers.IO) {
                runCatching { SecretStore.setApiKey(ctx, provider.apiKeyPrefKey(), key) }.isFailure
            }
            if (failed) {
                Toast.makeText(ctx, R.string.voice_error_secure_storage_unavailable, Toast.LENGTH_SHORT).show()
                storedLength = SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey()).length
            }
        }
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
        val hasStoredKey = storedLength > 0
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { saveApiKey(it.trim()) },
            // Deliberately not prefilled with the stored key. Loading the secret into an editable
            // field means anyone who can open Settings can read it back, and it puts the key in one
            // more place in memory for no benefit — you don't need to see a key to replace it.
            // Confirm stays disabled while the field is empty (checkTextValid defaults to
            // isNotBlank), so an untouched dialog can't wipe a working key; removing one is the
            // explicit neutral button below.
            initialText = "",
            description = if (hasStoredKey) {
                { Text(stringResource(R.string.voice_api_key_replace_hint)) }
            } else null,
            neutralButtonText = if (hasStoredKey) stringResource(R.string.voice_api_key_remove) else null,
            onNeutral = { saveApiKey("") },
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
    // The outcome used to be a toast only, which is easy to miss and gone a few seconds later —
    // leaving no way to tell whether the key was ever validated. Keep it under the row as well.
    var lastResult by remember { mutableStateOf<TestResult?>(null) }
    Preference(
        name = setting.title,
        description = when {
            busy -> stringResource(R.string.voice_test_key_testing)
            lastResult != null -> stringResource(lastResult!!.messageRes)
            else -> setting.description
        },
        // Announce the result to screen readers when it lands, rather than leaving them to
        // rediscover the row.
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
            busy = true
            lastResult = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { probeApiKey(provider, apiKey, model) }
                Toast.makeText(ctx, result.messageRes, Toast.LENGTH_SHORT).show()
                lastResult = result
                busy = false
            }
        }
    )
}

private enum class TestResult(@StringRes val messageRes: Int) {
    OK(R.string.voice_test_key_success),
    INVALID(R.string.voice_test_key_invalid),
    INVALID_MODEL(R.string.voice_test_key_invalid_model),
    NETWORK(R.string.voice_test_key_network_error),
}

private fun probeApiKey(provider: AiProvider, apiKey: String, model: String): TestResult {
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
            200 -> probeModel(apiKey, model)
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
    val modelsEndpoint = payPerQModelsEndpoint(model)
    val conn = (java.net.URL(modelsEndpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $apiKey")
        connectTimeout = OpenRouterClient.DEFAULT_CONNECT_TIMEOUT_MS
        readTimeout = 10_000
    }
    return try {
        when (conn.responseCode) {
            200 -> if (payPerQModelResponseContains(readProbeResponseCapped(conn.inputStream), model)) {
                TestResult.OK
            } else {
                TestResult.INVALID_MODEL
            }
            401, 403 -> TestResult.INVALID
            else -> TestResult.NETWORK
        }
    } catch (_: Exception) {
        TestResult.NETWORK
    } finally {
        conn.disconnect()
    }
}

private fun probeModel(apiKey: String, model: String): TestResult {
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
            200 -> TestResult.OK
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

internal fun payPerQModelResponseContains(body: String, model: String): Boolean {
    val models = JSONObject(body).optJSONArray("data") ?: return false
    for (i in 0 until models.length()) {
        if (models.optJSONObject(i)?.optString("id") == model) return true
    }
    return false
}

internal fun payPerQModelsEndpoint(model: String): String = if ("/" in model) {
    OpenRouterClient.PAYPERQ_MODELS_ENDPOINT
} else {
    OpenRouterClient.PAYPERQ_AUDIO_MODELS_ENDPOINT
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
