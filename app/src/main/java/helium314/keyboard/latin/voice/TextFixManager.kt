// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.annotation.StringRes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * Orchestrates text-fix requests to the selected AI provider. Reads the user's selected text via a callback,
 * validates preconditions, runs the HTTP call on a background thread, and posts the proposed
 * replacement back on the main thread. Stateless across requests — a new fix starts fresh.
 */
class TextFixManager(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    companion object {
        private const val TAG = "TextFixManager"
        private const val MAX_INPUT_LENGTH = 10_000
        private const val MAX_OUTPUT_LENGTH = 10_000

        @JvmStatic
        @StringRes
        fun getBlockedErrorResId(
            inputType: Int,
            isPasswordField: Boolean,
            noLearning: Boolean,
            incognitoModeEnabled: Boolean,
            imeOptions: Int,
        ): Int? {
            // Sensitive signals first — caller-supplied flags and IME-level hints.
            if (isPasswordField || noLearning || incognitoModeEnabled) {
                return R.string.text_fix_error_sensitive_field
            }
            if ((imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
                return R.string.text_fix_error_sensitive_field
            }
            // TYPE_TEXT_FLAG_NO_SUGGESTIONS is deliberately NOT treated as a privacy signal. Apps
            // set it on ordinary prose fields (chat composers, search boxes) purely to suppress the
            // suggestion strip, so blocking on it made Text Fix unusable across a lot of apps while
            // protecting nothing: the real signals are password fields, no-learning, incognito and
            // IME_FLAG_NO_PERSONALIZED_LEARNING, all checked above.
            when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> {
                    if (InputTypeUtils.isUriOrEmailType(inputType)) {
                        return R.string.text_fix_error_unsupported_field
                    }
                }
                InputType.TYPE_CLASS_NUMBER -> {
                    // Preventative: today we reject NUMBER as unsupported, but check password
                    // variation first so it registers as sensitive rather than unsupported.
                    if ((inputType and InputType.TYPE_MASK_VARIATION) ==
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                        return R.string.text_fix_error_sensitive_field
                    }
                    return R.string.text_fix_error_unsupported_field
                }
                else -> {
                    // DATETIME / PHONE / etc. — unsupported.
                    return R.string.text_fix_error_unsupported_field
                }
            }
            return null
        }
    }

    enum class State { IDLE, WORKING }

    enum class Variant(val enabledPref: String, val enabledDefault: Boolean, val promptPref: String, val promptDefault: String) {
        PRIMARY(Settings.PREF_TEXT_FIX_ENABLED, Defaults.PREF_TEXT_FIX_ENABLED, Settings.PREF_TEXT_FIX_PROMPT, Defaults.PREF_TEXT_FIX_PROMPT),
        SECONDARY(Settings.PREF_TEXT_FIX_2_ENABLED, Defaults.PREF_TEXT_FIX_2_ENABLED, Settings.PREF_TEXT_FIX_2_PROMPT, Defaults.PREF_TEXT_FIX_2_PROMPT),
    }

    interface Callbacks {
        @StringRes
        fun getBlockedErrorResId(): Int?
        /** Return the currently selected text, or null/empty if nothing is selected. */
        fun getSelectedText(): CharSequence?
        fun onWorking()
        fun onFinished()
        fun onResult(originalText: String, proposedText: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeClient: OpenRouterClient? = null
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeToken = 0L
    @Volatile private var state = State.IDLE

    fun getState() = state

    @Synchronized
    fun startTextFix(variant: Variant = Variant.PRIMARY) {
        if (state != State.IDLE) return
        val prefs = context.prefs()

        if (!prefs.getBoolean(variant.enabledPref, variant.enabledDefault)) {
            Toast.makeText(context, R.string.text_fix_error_not_enabled, Toast.LENGTH_SHORT).show()
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
        if (!isNetworkAvailable(context)) {
            Toast.makeText(context, R.string.voice_error_no_network, Toast.LENGTH_SHORT).show()
            return
        }

        val selected = callbacks.getSelectedText()?.toString().orEmpty()
        if (selected.isBlank()) {
            Toast.makeText(context, R.string.text_fix_error_no_selection, Toast.LENGTH_SHORT).show()
            return
        }
        if (selected.length > MAX_INPUT_LENGTH) {
            Toast.makeText(context, R.string.text_fix_error_too_long, Toast.LENGTH_SHORT).show()
            return
        }
        val input = selected

        val selectedModel = prefs.getString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL) ?: Defaults.PREF_TEXT_FIX_MODEL
        val customModel = prefs.getString(Settings.PREF_TEXT_FIX_MODEL_CUSTOM, Defaults.PREF_TEXT_FIX_MODEL_CUSTOM) ?: ""
        val model = resolveProviderModel(selectedModel, customModel)
        if (model == null) {
            Toast.makeText(context, R.string.voice_error_no_model, Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = (prefs.getString(variant.promptPref, variant.promptDefault) ?: variant.promptDefault)
            .trim().ifEmpty { variant.promptDefault }
        val useZdr = provider == AiProvider.OPENROUTER &&
            prefs.getBoolean(Settings.PREF_OPENROUTER_ZDR_ENABLED, Defaults.PREF_OPENROUTER_ZDR_ENABLED)
        warnIfZeroDataRetentionUnenforceable(context, provider, model, useZdr)

        state = State.WORKING
        callbacks.onWorking()

        val client = OpenRouterClient(
            apiKey = apiKey,
            model = model,
            systemPrompt = prompt,
            runtimeInstruction = null,
            provider = provider,
            useZeroDataRetention = useZdr,
        )
        val token = activeToken + 1
        activeToken = token
        activeClient = client

        activeJob = backgroundScope.launch(CoroutineName("TextFixRequest")) {
            try {
                val proposed = sanitize(runInterruptible { client.fixText(input) })
                if (proposed.isBlank()) {
                    finish(token, error = context.getString(R.string.text_fix_error_empty))
                    return@launch
                }
                finish(token, original = input, result = proposed)
            } catch (e: CancellationException) {
                finish(token)
            } catch (e: InterruptedException) {
                finish(token)
            } catch (e: Exception) {
                Log.e(TAG, "Text fix failed", e)
                finish(token, error = safeUserFacingError(context, e, R.string.text_fix_error_failed))
            }
        }
    }

    @Synchronized
    fun cancel() {
        if (state != State.WORKING) return
        activeToken += 1
        activeClient?.cancel()
        activeJob?.cancel()
        activeJob = null
        activeClient = null
        state = State.IDLE
        callbacks.onFinished()
    }

    /** Cancel any in-flight work and tear down the background scope. Call from IME onDestroy. */
    fun release() {
        cancel()
        backgroundScope.cancel()
    }

    private fun finish(
        token: Long,
        original: String? = null,
        result: String? = null,
        error: String? = null,
    ) {
        mainHandler.post {
            if (activeToken != token) return@post
            activeJob = null
            activeClient = null
            state = State.IDLE
            callbacks.onFinished()
            if (original != null && !result.isNullOrEmpty()) {
                callbacks.onResult(original, result)
            } else if (!error.isNullOrEmpty()) {
                callbacks.onError(error)
            }
        }
    }

    private fun sanitize(raw: String): String = sanitizeModelOutput(raw, MAX_OUTPUT_LENGTH)
}
