// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Orchestrates voice recording, transcription via OpenRouter, and text insertion.
 * All state transitions happen on the main thread.
 */
class VoiceInputManager(
    private val context: Context,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "VoiceInputManager"
    }

    enum class State { IDLE, RECORDING, TRANSCRIBING }

    interface Callbacks {
        fun onRecordingStarted()
        fun onTranscribing()
        fun onFinished()
        fun onTranscriptionResult(text: String)
        fun onError(message: String)
        fun onMaxDurationReached()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioRecorder = AudioRecorder()
    private var state = State.IDLE

    fun getState() = state

    fun startRecording() {
        if (state != State.IDLE) return

        val prefs = context.prefs()

        if (!prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED)) {
            Toast.makeText(context, R.string.voice_error_not_enabled, Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = prefs.getString(Settings.PREF_OPENROUTER_API_KEY, Defaults.PREF_OPENROUTER_API_KEY) ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(context, R.string.voice_error_no_api_key, Toast.LENGTH_SHORT).show()
            return
        }

        if (!PermissionsUtil.checkAllPermissionsGranted(context, Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(context, R.string.voice_error_no_permission, Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNetworkAvailable()) {
            Toast.makeText(context, R.string.voice_error_no_network, Toast.LENGTH_SHORT).show()
            return
        }

        audioRecorder.onMaxDurationReached = {
            mainHandler.post {
                callbacks.onMaxDurationReached()
                stopRecording()
            }
        }

        if (!audioRecorder.start()) {
            Toast.makeText(context, R.string.voice_error_transcription_failed, Toast.LENGTH_SHORT).show()
            return
        }

        state = State.RECORDING
        callbacks.onRecordingStarted()
    }

    fun stopRecording() {
        if (state != State.RECORDING) return

        val wavData = audioRecorder.stop()
        state = State.TRANSCRIBING
        callbacks.onTranscribing()

        val prefs = context.prefs()
        val apiKey = prefs.getString(Settings.PREF_OPENROUTER_API_KEY, Defaults.PREF_OPENROUTER_API_KEY) ?: ""
        var model = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL) ?: Defaults.PREF_VOICE_MODEL
        if (model == "custom") {
            model = prefs.getString(Settings.PREF_VOICE_MODEL_CUSTOM, Defaults.PREF_VOICE_MODEL_CUSTOM) ?: ""
        }

        val client = OpenRouterClient(apiKey, model)

        Thread("VoiceTranscription") {
            try {
                val transcription = client.transcribe(wavData)
                mainHandler.post {
                    state = State.IDLE
                    callbacks.onFinished()
                    if (transcription.isNotBlank()) {
                        callbacks.onTranscriptionResult(transcription)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                mainHandler.post {
                    state = State.IDLE
                    callbacks.onFinished()
                    callbacks.onError(e.message ?: context.getString(R.string.voice_error_transcription_failed))
                }
            }
        }.start()
    }

    fun cancelRecording() {
        if (state == State.RECORDING) {
            audioRecorder.cancel()
            state = State.IDLE
            callbacks.onFinished()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
