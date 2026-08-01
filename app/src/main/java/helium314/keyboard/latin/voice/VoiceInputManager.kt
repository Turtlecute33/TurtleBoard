// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.annotation.StringRes
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Orchestrates voice recording, transcription via the selected AI provider, and text insertion.
 * All state transitions happen on the main thread.
 */
class VoiceInputManager(
    private val context: Context,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "VoiceInputManager"
        private const val MAX_TRANSCRIPTION_LENGTH = 10_000
        private const val AUDIO_CACHE_SUBDIR = "voice_audio"
        private const val MIN_RECORDING_DURATION_MS = 500L
        private const val MIN_SPEECH_MEAN_AMPLITUDE = 80.0
        // Only sweep recordings old enough that they cannot belong to an in-flight session — a
        // rapid stop→record could otherwise delete the previous recording's file mid-finalize.
        private const val ORPHAN_RECORDING_MAX_AGE_MS = 60_000L

        /**
         * Apps do not expose a reliable credit-card field flag, so network-backed voice input is
         * restricted to ordinary prose fields and fails closed for editor privacy signals.
         */
        @JvmStatic
        @StringRes
        fun getBlockedErrorResId(
            inputType: Int,
            isPasswordField: Boolean,
            noLearning: Boolean,
            incognitoModeEnabled: Boolean,
            imeOptions: Int,
        ): Int? {
            // TYPE_TEXT_FLAG_NO_SUGGESTIONS is deliberately NOT treated as a privacy signal. Apps
            // set it on ordinary prose fields (chat composers, search boxes) purely to suppress the
            // suggestion strip, so blocking on it made voice input unusable across a lot of apps
            // while protecting nothing: the real signals are the ones checked here.
            if (isPasswordField || noLearning || incognitoModeEnabled ||
                (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            ) {
                return R.string.voice_error_sensitive_field
            }
            if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT ||
                InputTypeUtils.isUriOrEmailType(inputType)
            ) {
                return R.string.voice_error_unsupported_field
            }
            return null
        }
    }

    enum class State { IDLE, RECORDING, TRANSCRIBING }

    /**
     * Snapshot of text immediately adjacent to the cursor, used for spacing heuristics.
     * Values are Unicode code points (surrogate-pair safe), or null if no text on that side.
     */
    data class SpacingContext(val charBefore: Int?, val charAfter: Int?)

    interface Callbacks {
        @StringRes
        fun getBlockedErrorResId(): Int? = null
        fun onRecordingStarted()
        fun onTranscribing()
        fun onFinished()
        fun onTranscriptionResult(text: String)
        fun onError(message: String)
        fun onMaxDurationReached()
        /** Optional IME subtype locale; used as a hint to the transcription model. */
        fun getLocaleHint(): Locale? = null
        /** Optional surrounding-text snapshot; used to decide whether to insert spaces. */
        fun getSpacingContext(): SpacingContext? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioRecorder: AudioRecorder = AudioRecorder(outputFile = File(cacheAudioDir(), "rec_placeholder.wav"))
    @Volatile private var state = State.IDLE
    private var currentAudioFile: File? = null
    @Volatile private var transcriptionJob: Job? = null
    @Volatile private var transcriptionClient: OpenRouterClient? = null
    private val activeTranscriptionToken = AtomicLong(0L)
    @Volatile private var stopFinalizeJob: Job? = null
    @Volatile private var isStopFinalizing = false
    @Volatile private var currentUseDedicatedStt = false

    fun getState() = state

    /**
     * True only while the microphone is actually open and a stop hasn't been requested yet.
     *
     * Deliberately false during the post-stop WAV finalize window. [state] stays [State.RECORDING]
     * until the recorder drains, but [stopRecording] is already a no-op there, so callers that
     * "stop recording instead of typing" would swallow the keystroke and achieve nothing.
     */
    fun isCapturing(): Boolean = state == State.RECORDING && !isStopFinalizing

    /** Exposed so UI can render a live amplitude meter. */
    fun getCurrentAmplitude(): Double = audioRecorder.currentAmplitude

    /** Exposed so UI can render an elapsed-time counter. */
    fun getCurrentDurationMs(): Long = audioRecorder.currentDurationMs

    /** Maps the mic-sensitivity preference to a linear capture gain. "normal" leaves audio untouched. */
    private fun micSensitivityGain(value: String?): Float = when (value) {
        "high" -> 2f
        "max" -> 4f
        else -> 1f
    }

    @Synchronized
    fun startRecording(useDedicatedStt: Boolean = false) {
        if (state != State.IDLE) return
        currentUseDedicatedStt = useDedicatedStt

        val prefs = context.prefs()

        if (!prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED)) {
            Toast.makeText(context, R.string.voice_error_not_enabled, Toast.LENGTH_SHORT).show()
            return
        }

        callbacks.getBlockedErrorResId()?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            return
        }

        if (!SecretStore.isSecureStorageAvailable(context)) {
            Toast.makeText(context, R.string.voice_error_secure_storage_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
        val apiKey = SecretStore.getApiKey(context, provider.apiKeyPrefKey(), provider.defaultApiKey())
        if (apiKey.isBlank()) {
            Toast.makeText(context, R.string.voice_error_no_api_key, Toast.LENGTH_SHORT).show()
            return
        }

        if (!PermissionsUtil.checkAllPermissionsGranted(context, Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(context, R.string.voice_error_no_permission, Toast.LENGTH_SHORT).show()
            return
        }

        if (!isNetworkAvailable(context)) {
            Toast.makeText(context, R.string.voice_error_no_network, Toast.LENGTH_SHORT).show()
            return
        }

        if (currentUseDedicatedStt && provider != AiProvider.OPENROUTER) {
            currentUseDedicatedStt = false
            Toast.makeText(context, R.string.voice_error_stt_openrouter_only, Toast.LENGTH_SHORT).show()
            return
        }

        val maxDurationSec = prefs.getInt(Settings.PREF_VOICE_MAX_DURATION_SECONDS, Defaults.PREF_VOICE_MAX_DURATION_SECONDS)
            .coerceIn(15, 300)
        val autoStopEnabled = prefs.getBoolean(Settings.PREF_VOICE_AUTO_STOP_SILENCE, Defaults.PREF_VOICE_AUTO_STOP_SILENCE)
        val autoStopSec = prefs.getInt(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS, Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS)
            .coerceIn(1, 10)
        val micGain = micSensitivityGain(
            prefs.getString(Settings.PREF_VOICE_MIC_SENSITIVITY, Defaults.PREF_VOICE_MIC_SENSITIVITY)
        )

        // Fresh cache file per recording; older ones are swept on every start so a process
        // killed mid-recording can't leak audio across sessions.
        sweepOrphanRecordings()
        val audioFile = File(cacheAudioDir(), "rec_${System.currentTimeMillis()}.wav")
        currentAudioFile = audioFile
        // Tear down the previous recorder (including the placeholder created at construction) so its
        // coroutine scope doesn't leak for the lifetime of the IME process.
        audioRecorder.release()
        audioRecorder = AudioRecorder(
            outputFile = audioFile,
            maxDurationMs = maxDurationSec * 1000L,
            autoStopSilenceMs = if (autoStopEnabled) autoStopSec * 1000L else 0L,
            inputGain = micGain,
        )
        audioRecorder.onMaxDurationReached = {
            mainHandler.post {
                callbacks.onMaxDurationReached()
                stopRecording()
            }
        }
        audioRecorder.onAutoStopSilence = {
            mainHandler.post { stopRecording() }
        }

        if (!audioRecorder.start()) {
            Toast.makeText(context, R.string.voice_error_transcription_failed, Toast.LENGTH_SHORT).show()
            return
        }

        state = State.RECORDING
        callbacks.onRecordingStarted()
    }

    @Synchronized
    fun stopRecording() {
        if (state != State.RECORDING || isStopFinalizing) return
        isStopFinalizing = true

        // Kicking the WAV finalization off the main thread: AudioRecorder.stop() returns a
        // Deferred that completes once the recording loop drains and the file header is
        // written. Awaiting it here on the IME main thread used to ANR for up to 2s.
        val deferred = audioRecorder.stop()
        stopFinalizeJob = backgroundScope.launch(CoroutineName("VoiceFinalize")) {
            val wavFile = deferred.await()
            withContext(Dispatchers.Main.immediate) { onRecordingFinalized(wavFile) }
        }
    }

    @Synchronized
    private fun onRecordingFinalized(wavFile: File?) {
        isStopFinalizing = false
        stopFinalizeJob = null
        // The user may have cancelled while we were waiting for the recorder to drain.
        if (state != State.RECORDING) {
            wavFile?.takeIf { it.exists() }?.delete()
            return
        }

        if (wavFile == null || !wavFile.exists() || wavFile.length() <= 44L) {
            wavFile?.delete()
            currentAudioFile = null
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_no_audio))
            return
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "Uploading voice clip: durationMs=${audioRecorder.lastDurationMs}, meanAmplitude=${audioRecorder.lastMeanAmplitude}, bytes=${wavFile.length()}"
            )
        }
        if (audioRecorder.lastDurationMs < MIN_RECORDING_DURATION_MS) {
            wavFile.delete()
            currentAudioFile = null
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_too_short))
            return
        }
        if (audioRecorder.lastMeanAmplitude < MIN_SPEECH_MEAN_AMPLITUDE) {
            wavFile.delete()
            currentAudioFile = null
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_silent))
            return
        }

        state = State.TRANSCRIBING
        callbacks.onTranscribing()

        val prefs = context.prefs()
        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
        val apiKey = SecretStore.getApiKey(context, provider.apiKeyPrefKey(), provider.defaultApiKey())
        val selectedModel = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL) ?: Defaults.PREF_VOICE_MODEL
        val customModel = prefs.getString(Settings.PREF_VOICE_MODEL_CUSTOM, Defaults.PREF_VOICE_MODEL_CUSTOM) ?: ""
        val selectedSttModel = prefs.getString(Settings.PREF_VOICE_STT_MODEL, Defaults.PREF_VOICE_STT_MODEL) ?: Defaults.PREF_VOICE_STT_MODEL
        val customSttModel = prefs.getString(Settings.PREF_VOICE_STT_MODEL_CUSTOM, Defaults.PREF_VOICE_STT_MODEL_CUSTOM) ?: ""
        val useDedicatedStt = currentUseDedicatedStt
        // STT has its own prompt, dictionary, and expected-languages prefs so users can tune
        // the dedicated transcription endpoint independently of the chat-audio path. Falling
        // back to the chat-audio defaults would re-couple the two flows, so we read each set
        // from its own keys.
        val savedPrompt = if (useDedicatedStt) {
            prefs.getString(Settings.PREF_VOICE_STT_PROMPT, Defaults.PREF_VOICE_STT_PROMPT)
                ?: Defaults.PREF_VOICE_STT_PROMPT
        } else {
            prefs.getString(Settings.PREF_VOICE_TRANSCRIPTION_PROMPT, Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT)
                ?: Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
        }
        val transcriptionDictionary = if (useDedicatedStt) {
            prefs.getString(Settings.PREF_VOICE_STT_DICTIONARY, Defaults.PREF_VOICE_STT_DICTIONARY)
                ?: Defaults.PREF_VOICE_STT_DICTIONARY
        } else {
            prefs.getString(Settings.PREF_VOICE_TRANSCRIPTION_DICTIONARY, Defaults.PREF_VOICE_TRANSCRIPTION_DICTIONARY)
                ?: Defaults.PREF_VOICE_TRANSCRIPTION_DICTIONARY
        }
        val expectedLanguages = if (useDedicatedStt) {
            prefs.getString(Settings.PREF_VOICE_STT_EXPECTED_LANGUAGES, Defaults.PREF_VOICE_STT_EXPECTED_LANGUAGES)
                ?: Defaults.PREF_VOICE_STT_EXPECTED_LANGUAGES
        } else {
            prefs.getString(Settings.PREF_VOICE_EXPECTED_LANGUAGES, Defaults.PREF_VOICE_EXPECTED_LANGUAGES)
                ?: Defaults.PREF_VOICE_EXPECTED_LANGUAGES
        }
        val languageHintEnabled = prefs.getBoolean(Settings.PREF_VOICE_LANGUAGE_HINT, Defaults.PREF_VOICE_LANGUAGE_HINT)
        val spaceHeuristicEnabled = prefs.getBoolean(Settings.PREF_VOICE_SPACE_HEURISTIC, Defaults.PREF_VOICE_SPACE_HEURISTIC)
        val useZdr = provider == AiProvider.OPENROUTER &&
            prefs.getBoolean(Settings.PREF_OPENROUTER_ZDR_ENABLED, Defaults.PREF_OPENROUTER_ZDR_ENABLED)

        // Wispr-Flow-style auto-polish: after the raw transcription comes back, optionally pipe it
        // through a second, text-only LLM call that cleans it up to the chosen level. Resolved
        // here on the main thread so the background job receives plain values.
        val polishEnabled = prefs.getBoolean(Settings.PREF_VOICE_AUTO_POLISH_ENABLED, Defaults.PREF_VOICE_AUTO_POLISH_ENABLED)
        val polishLevel = PolishLevel.fromPref(prefs.getString(Settings.PREF_VOICE_POLISH_LEVEL, Defaults.PREF_VOICE_POLISH_LEVEL))
        val polishSystemPrompt = polishPromptForLevel(polishLevel)
        val polishModelSelected = prefs.getString(Settings.PREF_VOICE_POLISH_MODEL, Defaults.PREF_VOICE_POLISH_MODEL) ?: Defaults.PREF_VOICE_POLISH_MODEL
        val polishModelCustom = prefs.getString(Settings.PREF_VOICE_POLISH_MODEL_CUSTOM, Defaults.PREF_VOICE_POLISH_MODEL_CUSTOM) ?: ""
        val polishModel = if (polishEnabled && polishSystemPrompt != null) {
            resolveProviderModel(polishModelSelected, polishModelCustom)
        } else null

        val model = if (useDedicatedStt) {
            resolveVoiceSttModel(selectedSttModel, customSttModel)
        } else {
            resolveProviderModel(selectedModel, customModel)
        }
        if (model == null) {
            wavFile.delete()
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            callbacks.onError(context.getString(R.string.voice_error_no_model))
            return
        }
        warnIfZeroDataRetentionUnenforceable(context, provider, model, useZdr)
        if (polishModel != null) warnIfZeroDataRetentionUnenforceable(context, provider, polishModel, useZdr)
        val localeHint = if (languageHintEnabled) callbacks.getLocaleHint() else null
        val prompt = resolveVoicePrompt(savedPrompt, localeHint, transcriptionDictionary, expectedLanguages)
        val spacingContext = if (spaceHeuristicEnabled) callbacks.getSpacingContext() else null

        val client = OpenRouterClient(
            apiKey = apiKey,
            model = model,
            systemPrompt = prompt.systemPrompt,
            runtimeInstruction = prompt.runtimeInstruction,
            provider = provider,
            useZeroDataRetention = useZdr,
            transcriptionMode = if (useDedicatedStt) VoiceTranscriptionMode.OPENROUTER_STT else VoiceTranscriptionMode.CHAT_AUDIO,
            transcriptionLanguage = localeHint?.toOpenRouterSttLanguage(),
        )
        val requestToken = activeTranscriptionToken.incrementAndGet()
        transcriptionClient = client

        transcriptionJob = backgroundScope.launch(CoroutineName("VoiceTranscription")) {
            try {
                val transcription = sanitizeTranscription(runInterruptible { client.transcribe(wavFile) })
                if (transcription.isBlank()) {
                    finishTranscription(
                        requestToken = requestToken,
                        error = context.getString(R.string.voice_error_transcription_failed),
                    )
                    return@launch
                }
                // Auto-polish stage. We swap transcriptionClient over so the manager-wide cancel
                // path tears down the polish connection if the user backs out. Any failure here
                // is non-fatal: we keep the raw transcription rather than dropping the user's
                // recording on the floor.
                val polished = if (polishEnabled && polishSystemPrompt != null && polishModel != null) {
                    val polishClient = OpenRouterClient(
                        apiKey = apiKey,
                        model = polishModel,
                        systemPrompt = polishSystemPrompt,
                        runtimeInstruction = null,
                        provider = provider,
                        useZeroDataRetention = useZdr,
                    )
                    transcriptionClient = polishClient
                    try {
                        val raw = runInterruptible { polishClient.fixText(transcription) }
                        sanitizeTranscription(raw).takeIf { it.isNotBlank() } ?: transcription
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ie: InterruptedException) {
                        throw ie
                    } catch (pe: Exception) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Polish failed; falling back to raw transcription", pe)
                        transcription
                    } finally {
                        transcriptionClient = client
                    }
                } else transcription
                val finalText = applySpacing(polished, spacingContext)
                finishTranscription(requestToken = requestToken, result = finalText)
            } catch (e: CancellationException) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Transcription cancelled")
                finishTranscription(requestToken = requestToken)
            } catch (e: InterruptedException) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Transcription cancelled")
                finishTranscription(requestToken = requestToken)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                finishTranscription(
                    requestToken = requestToken,
                    error = safeUserFacingError(context, e, R.string.voice_error_transcription_failed),
                )
            } finally {
                // Best-effort: delete the audio after the request, whether it succeeded or not.
                if (wavFile.exists()) wavFile.delete()
            }
        }
    }

    /** Cancel either a live recording or an in-flight upload. */
    @Synchronized
    fun cancelRecording() {
        when (state) {
            State.RECORDING -> {
                audioRecorder.cancel()
                // If a stop() was already in flight, its finalize callback will see state==IDLE
                // and discard the resulting file. Otherwise, the loop's finally deletes it.
                stopFinalizeJob?.cancel()
                stopFinalizeJob = null
                isStopFinalizing = false
                currentUseDedicatedStt = false
                currentAudioFile = null
                state = State.IDLE
                callbacks.onFinished()
            }
            State.TRANSCRIBING -> {
                activeTranscriptionToken.incrementAndGet()
                transcriptionClient?.cancel()
                transcriptionJob?.cancel()
                transcriptionJob = null
                transcriptionClient = null
                currentUseDedicatedStt = false
                // The transcription thread's finally block will handle file deletion; only
                // reach in here if it couldn't start.
                currentAudioFile?.takeIf { it.exists() }?.delete()
                currentAudioFile = null
                state = State.IDLE
                callbacks.onFinished()
            }
            State.IDLE -> Unit
        }
    }

    /** Cancel any in-flight work and tear down the background scope. Call from IME onDestroy. */
    fun release() {
        cancelRecording()
        audioRecorder.release()
        backgroundScope.cancel()
    }

    private fun cacheAudioDir(): File {
        val dir = File(context.cacheDir, AUDIO_CACHE_SUBDIR)
        dir.mkdirs()
        return dir
    }

    private fun sweepOrphanRecordings() {
        runCatching {
            val cutoff = System.currentTimeMillis() - ORPHAN_RECORDING_MAX_AGE_MS
            cacheAudioDir().listFiles()?.forEach { file ->
                if (file.name.startsWith("rec_") && file.extension.equals("wav", ignoreCase = true)
                    && file.lastModified() < cutoff
                ) {
                    file.delete()
                }
            }
        }
    }

    private fun sanitizeTranscription(raw: String): String =
        sanitizeModelOutput(raw, MAX_TRANSCRIPTION_LENGTH)

    private fun finishTranscription(
        requestToken: Long,
        result: String? = null,
        error: String? = null,
    ) {
        mainHandler.post {
            if (activeTranscriptionToken.get() != requestToken) {
                return@post
            }
            transcriptionJob = null
            transcriptionClient = null
            currentUseDedicatedStt = false
            state = State.IDLE
            callbacks.onFinished()
            if (!result.isNullOrEmpty()) {
                callbacks.onTranscriptionResult(result)
            } else if (!error.isNullOrEmpty()) {
                callbacks.onError(error)
            }
        }
    }

}
