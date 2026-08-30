// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
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
import java.util.Locale

/** Placeholder in the translate prompt that is replaced with the language the user picked. */
private const val LANGUAGE_PLACEHOLDER = "\${language}"

internal const val TRANSLATE_MAX_INPUT_LENGTH = 10_000
private const val TRANSLATE_MAX_OUTPUT_LENGTH = 20_000

/**
 * Splits the saved target-language list into display entries. Commas, semicolons, newlines and
 * `|` all separate, so the pref survives whichever separator the user reaches for, and so a value
 * written by an older build (which used `|`) still parses.
 */
fun parseTranslateLanguages(raw: String): List<String> =
    raw.split(',', ';', '\n', '|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(TRANSLATE_MAX_LANGUAGES)

/** Upper bound on the language chips, so a pasted essay in the pref cannot fill the whole strip. */
const val TRANSLATE_MAX_LANGUAGES = 24

/**
 * Builds the system prompt for one translation. A custom prompt that dropped the
 * `${language}` placeholder still has to name a target language, so append it explicitly
 * rather than silently sending a prompt that translates into nothing in particular.
 */
internal fun resolveTranslatePrompt(savedPrompt: String, targetLanguage: String): String {
    val base = savedPrompt.trim().ifEmpty { Defaults.PREF_TRANSLATE_PROMPT }
    if (base.contains(LANGUAGE_PLACEHOLDER)) return base.replace(LANGUAGE_PLACEHOLDER, targetLanguage)
    return "$base\nTranslate into $targetLanguage."
}

/**
 * Orchestrates translation requests to the selected AI provider.
 *
 * Deliberately separate from [TextFixManager]: text fix proposes a replacement the user confirms,
 * while a translation is committed straight into the editor, and both surfaces (long-press Return
 * and the clipboard panel) drive this one with their own per-request callbacks.
 *
 * @param fieldGuard returns a string resource describing why the focused editor must not receive
 *   AI text (password field, incognito, …), or null when it may. Both entry points write into the
 *   same editor, so the guard is checked here rather than at each call site.
 */
class TranslateManager(
    private val context: Context,
    private val fieldGuard: () -> Int?,
) {
    companion object {
        private const val TAG = "TranslateManager"
    }

    enum class State { IDLE, WORKING }

    interface Callbacks {
        fun onWorking()
        /** Request settled — success, failure or cancellation. Always paired with [onWorking]. */
        fun onFinished()
        fun onResult(originalText: String, translatedText: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeClient: OpenRouterClient? = null
    @Volatile private var activeJob: Job? = null
    @Volatile private var activeToken = 0L
    @Volatile private var activeCallbacks: Callbacks? = null
    @Volatile private var state = State.IDLE

    fun getState() = state

    /** The target languages offered in the middle menu. */
    fun languages(): List<String> {
        val raw = context.prefs().getString(Settings.PREF_TRANSLATE_LANGUAGES, Defaults.PREF_TRANSLATE_LANGUAGES)
            ?: Defaults.PREF_TRANSLATE_LANGUAGES
        return parseTranslateLanguages(raw).ifEmpty { parseTranslateLanguages(Defaults.PREF_TRANSLATE_LANGUAGES) }
    }

    /**
     * Everything that can be checked before the user picks a language: feature switch, key store,
     * provider credentials, network, and the focused field. Returns a message to show instead of
     * the language menu, or null when a translation may be started.
     */
    fun unavailableReason(): String? {
        val resId = unavailableReasonResId() ?: return null
        return context.getString(resId)
    }

    @StringRes
    private fun unavailableReasonResId(): Int? {
        val prefs = context.prefs()
        if (!prefs.getBoolean(Settings.PREF_TRANSLATE_ENABLED, Defaults.PREF_TRANSLATE_ENABLED)) {
            return R.string.translate_error_not_enabled
        }
        fieldGuard()?.let { return it }
        if (!SecretStore.isSecureStorageAvailable(context)) {
            return R.string.voice_error_secure_storage_unavailable
        }
        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
        if (SecretStore.getApiKey(context, provider.apiKeyPrefKey(), provider.defaultApiKey()).isBlank()) {
            return R.string.voice_error_no_api_key
        }
        if (resolveModel() == null) {
            return R.string.voice_error_no_model
        }
        if (!isNetworkAvailable(context)) {
            return R.string.voice_error_no_network
        }
        return null
    }

    private fun resolveModel(): String? {
        val prefs = context.prefs()
        val selected = prefs.getString(Settings.PREF_TRANSLATE_MODEL, Defaults.PREF_TRANSLATE_MODEL)
            ?: Defaults.PREF_TRANSLATE_MODEL
        val custom = prefs.getString(Settings.PREF_TRANSLATE_MODEL_CUSTOM, Defaults.PREF_TRANSLATE_MODEL_CUSTOM) ?: ""
        return resolveProviderModel(selected, custom)
    }

    /**
     * Starts a translation of [input] into [targetLanguage]. Reports through [callbacks], which
     * are only ever invoked on the main thread and only while this request is the active one.
     */
    @Synchronized
    fun startTranslate(input: String, targetLanguage: String, callbacks: Callbacks) {
        if (state != State.IDLE) return
        val text = input.trim()
        if (text.isEmpty()) {
            callbacks.onError(context.getString(R.string.translate_error_no_text))
            return
        }
        if (text.length > TRANSLATE_MAX_INPUT_LENGTH) {
            callbacks.onError(context.getString(R.string.translate_error_too_long))
            return
        }
        val language = targetLanguage.trim()
        if (language.isEmpty()) {
            callbacks.onError(context.getString(R.string.translate_error_no_language))
            return
        }
        unavailableReasonResId()?.let {
            callbacks.onError(context.getString(it))
            return
        }
        val prefs = context.prefs()
        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
        val apiKey = SecretStore.getApiKey(context, provider.apiKeyPrefKey(), provider.defaultApiKey())
        val model = resolveModel() ?: return
        val savedPrompt = prefs.getString(Settings.PREF_TRANSLATE_PROMPT, Defaults.PREF_TRANSLATE_PROMPT)
            ?: Defaults.PREF_TRANSLATE_PROMPT
        val prompt = resolveTranslatePrompt(savedPrompt, language)
        val useZdr = provider == AiProvider.OPENROUTER &&
            prefs.getBoolean(Settings.PREF_OPENROUTER_ZDR_ENABLED, Defaults.PREF_OPENROUTER_ZDR_ENABLED)

        state = State.WORKING
        activeCallbacks = callbacks
        callbacks.onWorking()

        val client = OpenRouterClient(
            apiKey = apiKey,
            model = model,
            systemPrompt = prompt,
            runtimeInstruction = "Target language: $language.",
            provider = provider,
            useZeroDataRetention = useZdr,
        )
        val token = activeToken + 1
        activeToken = token
        activeClient = client

        activeJob = backgroundScope.launch(CoroutineName("TranslateRequest")) {
            try {
                val translated = sanitizeModelOutput(runInterruptible { client.fixText(text) }, TRANSLATE_MAX_OUTPUT_LENGTH)
                if (client.didFallbackFromZdr) {
                    mainHandler.post { warnAfterZdrFallback(context, model) }
                }
                if (translated.isBlank()) {
                    finish(token, error = context.getString(R.string.translate_error_empty))
                    return@launch
                }
                finish(token, original = text, result = translated)
            } catch (e: CancellationException) {
                finish(token)
            } catch (e: InterruptedException) {
                finish(token)
            } catch (e: Exception) {
                if (client.didFallbackFromZdr) {
                    mainHandler.post { warnAfterZdrFallback(context, model) }
                }
                Log.e(TAG, "Translation failed", e)
                finish(token, error = safeUserFacingError(context, e, R.string.translate_error_failed))
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
        val callbacks = activeCallbacks
        activeCallbacks = null
        callbacks?.onFinished()
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
            val callbacks = activeCallbacks
            activeCallbacks = null
            callbacks?.onFinished()
            if (original != null && !result.isNullOrEmpty()) {
                callbacks?.onResult(original, result)
            } else if (!error.isNullOrEmpty()) {
                callbacks?.onError(error)
            }
        }
    }
}
