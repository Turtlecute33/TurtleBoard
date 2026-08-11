// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.speech.SpeechRecognizer
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnDeviceRecognizerTest {

    @Test
    fun speechEngineFallsBackToCloudForUnknownOrMissingValues() {
        assertEquals(SpeechEngine.CLOUD, SpeechEngine.fromPref(null))
        assertEquals(SpeechEngine.CLOUD, SpeechEngine.fromPref(""))
        assertEquals(SpeechEngine.CLOUD, SpeechEngine.fromPref("whisper.cpp"))
        assertEquals(SpeechEngine.CLOUD, SpeechEngine.fromPref("cloud"))
        assertEquals(SpeechEngine.ON_DEVICE, SpeechEngine.fromPref("on_device"))
        // A stale default would silently move every existing user onto the other engine.
        assertEquals(SpeechEngine.CLOUD, SpeechEngine.fromPref(Defaults.PREF_VOICE_SPEECH_ENGINE))
    }

    @Test
    fun rmsMapsOntoTheMeterScaleAndStaysInRange() {
        // AmplitudeMeterView saturates at 6000, so anything louder must clamp rather than overshoot.
        assertEquals(0.0, amplitudeFromRms(-2f))
        assertEquals(0.0, amplitudeFromRms(-50f))
        assertEquals(6000.0, amplitudeFromRms(10f))
        assertEquals(6000.0, amplitudeFromRms(120f))
        assertEquals(3000.0, amplitudeFromRms(4f))
        // The platform is free to hand us garbage; a NaN would poison the meter's smoothing filter.
        assertEquals(0.0, amplitudeFromRms(Float.NaN))

        var previous = -1.0
        for (db in -5..15) {
            val amplitude = amplitudeFromRms(db.toFloat())
            assertTrue(amplitude in 0.0..6000.0, "amplitude out of range at ${db}dB: $amplitude")
            assertTrue(amplitude >= previous, "amplitude must be monotonic, broke at ${db}dB")
            previous = amplitude
        }
    }

    @Test
    fun recognitionErrorsMapToActionableMessages() {
        // Nothing was said — reuse the message the cloud path already shows for silence.
        assertEquals(R.string.voice_error_silent, onDeviceErrorMessageRes(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(R.string.voice_error_silent, onDeviceErrorMessageRes(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(
            R.string.voice_error_no_permission,
            onDeviceErrorMessageRes(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        )
        // 12/13 are ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE, hardcoded because the
        // constants are API 33+. The user can fix these by installing a language pack, so they get
        // their own message rather than the generic failure.
        assertEquals(R.string.voice_error_on_device_language, onDeviceErrorMessageRes(12))
        assertEquals(R.string.voice_error_on_device_language, onDeviceErrorMessageRes(13))
        assertEquals(R.string.voice_error_on_device_failed, onDeviceErrorMessageRes(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(R.string.voice_error_on_device_failed, onDeviceErrorMessageRes(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(
            R.string.voice_error_on_device_failed,
            onDeviceErrorMessageRes(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        )
        assertEquals(R.string.voice_error_on_device_failed, onDeviceErrorMessageRes(9999))
    }
}
