// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.voice.AiProvider
import helium314.keyboard.latin.voice.OpenRouterClient
import helium314.keyboard.latin.voice.SpeechEngine
import helium314.keyboard.latin.voice.supportsOpenRouterSttSlug
import helium314.keyboard.latin.voice.supportsTextFixSlug
import helium314.keyboard.latin.voice.supportsVoiceSlug
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class VoiceScreenLogicTest {
    @Test
    fun payPerQModelValidationRequiresAnExactCatalogMatch() {
        val response = """{"data":[{"id":"nova-3"},{"id":"~google/gemini-flash-latest"}]}"""

        assertTrue(payPerQModelResponseContains(response, "nova-3"))
        assertTrue(payPerQModelResponseContains(response, "~google/gemini-flash-latest"))
        assertFalse(payPerQModelResponseContains(response, "nova"))
        assertFalse(payPerQModelResponseContains("""{"unexpected":[]}""", "nova-3"))
        assertTrue(payPerQModelsEndpoint("provider/chat-model") == OpenRouterClient.PAYPERQ_MODELS_ENDPOINT)
        assertTrue(payPerQModelsEndpoint("nova-3") == OpenRouterClient.PAYPERQ_AUDIO_MODELS_ENDPOINT)
    }

    @Test
    fun voiceItemsHideConfigurationWhenVoiceInputIsDisabled() {
        val items = buildVoiceScreenItems(
            voiceInputEnabled = false,
            voiceModel = "mistralai/voxtral-small-24b-2507",
        )

        assertTrue(Settings.PREF_VOICE_INPUT_ENABLED in items)
        assertFalse(Settings.PREF_OPENROUTER_API_KEY in items)
        assertFalse(Settings.PREF_VOICE_MODEL in items)
        assertFalse(Settings.PREF_VOICE_MODEL_CUSTOM in items)
        assertFalse(Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY in items)
        assertFalse(Settings.PREF_VOICE_EXPECTED_LANGUAGES in items)
    }

    @Test
    fun voiceItemsShowCustomModelOnlyWhenSelected() {
        val regularModelItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
        )
        val customModelItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "custom",
        )

        assertTrue(Settings.PREF_OPENROUTER_API_KEY in regularModelItems)
        assertTrue(Settings.PREF_OPENROUTER_ZDR_ENABLED in regularModelItems)
        assertTrue(Settings.PREF_VOICE_MODEL in regularModelItems)
        assertTrue(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT in regularModelItems)
        assertTrue(Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY in regularModelItems)
        assertTrue(Settings.PREF_VOICE_EXPECTED_LANGUAGES in regularModelItems)
        assertFalse(Settings.PREF_VOICE_MODEL_CUSTOM in regularModelItems)
        assertTrue(Settings.PREF_VOICE_MODEL_CUSTOM in customModelItems)
    }

    @Test
    fun voiceItemsShowSttModelOnlyWhenDedicatedSttIsEnabled() {
        val sttOffItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
            provider = AiProvider.OPENROUTER,
            sttEnabled = false,
        )
        val sttOnItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
            provider = AiProvider.OPENROUTER,
            sttEnabled = true,
        )
        val customSttItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
            sttModel = "custom",
            provider = AiProvider.OPENROUTER,
            sttEnabled = true,
        )

        assertTrue(Settings.PREF_VOICE_STT_ENABLED in sttOffItems)
        assertFalse(Settings.PREF_VOICE_STT_MODEL in sttOffItems)
        assertFalse(Settings.PREF_VOICE_STT_PROMPT in sttOffItems)
        assertFalse(Settings.PREF_VOICE_STT_DICTIONARY in sttOffItems)
        assertFalse(Settings.PREF_VOICE_STT_EXPECTED_LANGUAGES in sttOffItems)
        assertTrue(Settings.PREF_VOICE_STT_MODEL in sttOnItems)
        assertTrue(Settings.PREF_VOICE_STT_PROMPT in sttOnItems)
        assertTrue(Settings.PREF_VOICE_STT_DICTIONARY in sttOnItems)
        assertTrue(Settings.PREF_VOICE_STT_EXPECTED_LANGUAGES in sttOnItems)
        assertFalse(Settings.PREF_VOICE_STT_MODEL_CUSTOM in sttOnItems)
        assertTrue(Settings.PREF_VOICE_STT_MODEL_CUSTOM in customSttItems)
    }

    @Test
    fun voiceItemsHideTraditionalSettingsWhenTraditionalButtonDisabled() {
        // Disabling the chat-audio button should hide its dependent rows (model, prompt,
        // dictionary, expected languages) so the screen stops asking the user to configure a
        // path they explicitly turned off, while leaving STT settings untouched.
        val traditionalOffItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
            provider = AiProvider.OPENROUTER,
            traditionalEnabled = false,
            sttEnabled = true,
        )

        assertTrue(Settings.PREF_VOICE_TRADITIONAL_BUTTON_ENABLED in traditionalOffItems)
        assertFalse(Settings.PREF_VOICE_MODEL in traditionalOffItems)
        assertFalse(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT in traditionalOffItems)
        assertFalse(Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY in traditionalOffItems)
        assertFalse(Settings.PREF_VOICE_EXPECTED_LANGUAGES in traditionalOffItems)
        assertTrue(Settings.PREF_VOICE_STT_ENABLED in traditionalOffItems)
        assertTrue(Settings.PREF_VOICE_STT_MODEL in traditionalOffItems)
    }

    @Test
    fun voiceItemsUsePayPerQKeyAndHideOpenRouterZdrWhenPayPerQSelected() {
        val items = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "nova-3",
            provider = AiProvider.PAYPERQ,
        )

        assertTrue(Settings.PREF_AI_PROVIDER in items)
        assertTrue(Settings.PREF_PAYPERQ_API_KEY in items)
        assertFalse(Settings.PREF_OPENROUTER_API_KEY in items)
        assertFalse(Settings.PREF_OPENROUTER_ZDR_ENABLED in items)
    }

    @Test
    fun defaultModelsAreValidSlugsForBothProviders() {
        // Guard against the regression where Defaults.PREF_VOICE_MODEL was a text-fix slug
        // that the voice picker didn't actually offer, leaving fresh installs and the
        // provider-switch fallback writing a value the rest of the app considered unsupported.
        for (provider in AiProvider.values()) {
            assertTrue(
                provider.supportsVoiceSlug(Defaults.PREF_VOICE_MODEL),
                "Defaults.PREF_VOICE_MODEL must be a voice slug supported by $provider"
            )
            assertTrue(
                provider.supportsTextFixSlug(Defaults.PREF_TEXT_FIX_MODEL),
                "Defaults.PREF_TEXT_FIX_MODEL must be a text-fix slug supported by $provider"
            )
        }
        assertTrue(
            supportsOpenRouterSttSlug(Defaults.PREF_VOICE_STT_MODEL),
            "Defaults.PREF_VOICE_STT_MODEL must be an OpenRouter STT slug"
        )
    }

    @Test
    fun voiceItemsShowAutoStopDurationOnlyWhenEnabled() {
        val autoStopOffItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
            voiceAutoStop = false,
        )
        val autoStopOnItems = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
            voiceAutoStop = true,
        )

        assertFalse(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS in autoStopOffItems)
        assertTrue(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS in autoStopOnItems)
    }

    @Test
    fun onDeviceEngineCannotBeSelectedWithoutDeviceSupport() {
        // Saving it anyway would leave every dictation failing at the microphone, with the settings
        // screen insisting the engine is active. Cloud must stay selectable no matter what.
        assertFalse(canSelectSpeechEngine(SpeechEngine.ON_DEVICE, onDeviceAvailable = false))
        assertTrue(canSelectSpeechEngine(SpeechEngine.ON_DEVICE, onDeviceAvailable = true))
        assertTrue(canSelectSpeechEngine(SpeechEngine.CLOUD, onDeviceAvailable = false))
        assertTrue(canSelectSpeechEngine(SpeechEngine.CLOUD, onDeviceAvailable = true))
    }

    @Test
    fun unsupportedOnDeviceEngineIsLabelledAsNotInstalled() {
        assertEquals(
            R.string.voice_speech_engine_on_device_unavailable,
            speechEngineLabelRes(SpeechEngine.ON_DEVICE, onDeviceAvailable = false)
        )
        assertEquals(
            R.string.voice_speech_engine_on_device,
            speechEngineLabelRes(SpeechEngine.ON_DEVICE, onDeviceAvailable = true)
        )
        // The cloud label never changes with a capability it does not depend on.
        assertEquals(
            R.string.voice_speech_engine_cloud,
            speechEngineLabelRes(SpeechEngine.CLOUD, onDeviceAvailable = false)
        )
        assertEquals(
            R.string.voice_speech_engine_cloud,
            speechEngineLabelRes(SpeechEngine.CLOUD, onDeviceAvailable = true)
        )
    }

    @Test
    fun onDeviceEngineHidesEveryCloudProviderSetting() {
        // The on-device engine issues no network request at all, so every row that configures one
        // must disappear — otherwise the screen implies a key, model or polish pass still applies.
        val items = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "custom",
            sttModel = "custom",
            provider = AiProvider.OPENROUTER,
            speechEngine = SpeechEngine.ON_DEVICE,
            traditionalEnabled = true,
            sttEnabled = true,
            voiceAutoStop = true,
            autoPolishEnabled = true,
            polishModel = "custom",
        )

        assertTrue(Settings.PREF_VOICE_INPUT_ENABLED in items)
        assertTrue(Settings.PREF_VOICE_SPEECH_ENGINE in items)
        assertFalse(Settings.PREF_AI_PROVIDER in items)
        assertFalse(Settings.PREF_OPENROUTER_API_KEY in items)
        assertFalse(Settings.PREF_PAYPERQ_API_KEY in items)
        assertFalse(Settings.PREF_OPENROUTER_ZDR_ENABLED in items)
        assertFalse(Settings.PREF_VOICE_ACTION_TEST_KEY in items)
        assertFalse(Settings.PREF_VOICE_MODEL in items)
        assertFalse(Settings.PREF_VOICE_MODEL_CUSTOM in items)
        assertFalse(Settings.PREF_VOICE_TRADITIONAL_BUTTON_ENABLED in items)
        assertFalse(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT in items)
        assertFalse(Settings.PREF_VOICE_STT_ENABLED in items)
        assertFalse(Settings.PREF_VOICE_STT_MODEL in items)
        assertFalse(Settings.PREF_VOICE_AUTO_POLISH_ENABLED in items)
        assertFalse(Settings.PREF_VOICE_POLISH_MODEL in items)
        assertFalse(Settings.PREF_VOICE_POLISH_MODEL_CUSTOM in items)
    }

    @Test
    fun onDeviceEngineKeepsOnlySettingsItCanHonour() {
        val items = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "mistralai/voxtral-small-24b-2507",
            speechEngine = SpeechEngine.ON_DEVICE,
            voiceAutoStop = true,
        )

        // Honoured: the locale hint, the spacing heuristic, haptics and our own duration cap.
        assertTrue(Settings.PREF_VOICE_LANGUAGE_HINT in items)
        assertTrue(Settings.PREF_VOICE_SPACE_HEURISTIC in items)
        assertTrue(Settings.PREF_VOICE_HAPTIC_FEEDBACK in items)
        assertTrue(Settings.PREF_VOICE_MAX_DURATION_SECONDS in items)
        // Not honoured: the platform recogniser owns capture gain and utterance endpointing.
        assertFalse(Settings.PREF_VOICE_MIC_SENSITIVITY in items)
        assertFalse(Settings.PREF_VOICE_AUTO_STOP_SILENCE in items)
        assertFalse(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS in items)
    }

    @Test
    fun cloudIsTheDefaultEngineAndLeavesTheScreenUnchanged() {
        val defaulted = buildVoiceScreenItems(voiceInputEnabled = true, voiceModel = "custom")
        val explicitCloud = buildVoiceScreenItems(
            voiceInputEnabled = true,
            voiceModel = "custom",
            speechEngine = SpeechEngine.CLOUD,
        )

        assertTrue(SpeechEngine.fromPref(Defaults.PREF_VOICE_SPEECH_ENGINE) == SpeechEngine.CLOUD)
        assertTrue(defaulted == explicitCloud)
        assertTrue(Settings.PREF_OPENROUTER_API_KEY in defaulted)
    }
}
