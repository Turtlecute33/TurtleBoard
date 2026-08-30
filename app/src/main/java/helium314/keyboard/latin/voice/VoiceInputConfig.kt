// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import java.text.BreakIterator
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val SANITIZE_OUTPUT_REGEX =
    Regex("[[\\p{Cc}&&[^\\n\\r\\t]]\\u200B\\u200C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

/**
 * Strip control characters (Cc) and a narrow set of bidi/format marks that corrupt host editors,
 * but intentionally preserve U+200D (ZWJ) so emoji sequences survive, and newline/carriage-return/tab
 * so the model's requested line breaks and spacing survive (otherwise "end.\nNext" collapses to
 * "end.Next", losing both the line break and the space after punctuation).
 */
internal fun sanitizeModelOutput(raw: String, maxLength: Int): String {
    val cleaned = raw.replace(SANITIZE_OUTPUT_REGEX, "").trim()
    if (cleaned.length <= maxLength) return cleaned
    // Truncate at a grapheme cluster boundary so we never split a surrogate pair, a ZWJ emoji
    // sequence, or a base+combining-mark cluster - any of which would render as broken glyphs.
    // BreakIterator handles surrogate/combining boundaries, but Java/Android implementations can
    // still stop between emoji that are joined by U+200D, so we repair that boundary explicitly.
    // Locale.ROOT so grapheme boundaries (and thus truncation points) do not depend on the
    // device locale.
    val breaker = BreakIterator.getCharacterInstance(Locale.ROOT)
    breaker.setText(cleaned)
    val boundary = breaker.preceding(maxLength + 1)
    val end = avoidPartialZwjSequence(cleaned, if (boundary == BreakIterator.DONE) 0 else boundary)
    return cleaned.substring(0, end.coerceAtMost(maxLength))
}

private fun avoidPartialZwjSequence(text: String, end: Int): Int {
    if (end <= 0 || end >= text.length) return end
    return when {
        text[end] == '\u200D' -> startOfZwjSequenceBefore(text, end)
        text[end - 1] == '\u200D' -> startOfZwjSequenceBefore(text, end - 1)
        else -> end
    }
}

private fun startOfZwjSequenceBefore(text: String, zwjIndex: Int): Int {
    var start = Character.offsetByCodePoints(text, zwjIndex, -1)
    while (start >= 2 && text[start - 1] == '\u200D') {
        start = Character.offsetByCodePoints(text, start - 1, -1)
    }
    return start
}

/**
 * Prepends a space when the cursor sits immediately after a letter/digit, and appends one
 * when the next char is a letter/digit. Keeps `"hello".world` from becoming `"hello"world`.
 * Accepts a [VoiceInputManager.SpacingContext] whose fields are Unicode code points (surrogate-pair safe),
 * or null to leave the text unchanged.
 */
internal fun applySpacing(text: String, ctx: VoiceInputManager.SpacingContext?): String {
    if (text.isEmpty() || ctx == null) return text
    val leadingCp = Character.codePointAt(text, 0)
    val trailingCp = Character.codePointBefore(text, text.length)
    val leadingIsWhitespace = Character.isWhitespace(leadingCp)
    val trailingIsWhitespace = Character.isWhitespace(trailingCp)
    val needsLeading = ctx.charBefore?.let { Character.isLetterOrDigit(it) && !leadingIsWhitespace } == true
    val needsTrailing = ctx.charAfter?.let { Character.isLetterOrDigit(it) && !trailingIsWhitespace } == true
    val prefix = if (needsLeading) " " else ""
    val suffix = if (needsTrailing) " " else ""
    return prefix + text + suffix
}

private val SENSITIVE_USER_FACING_PATTERNS: List<Pair<Regex, String>> = listOf(
    Regex("(?i)Bearer\\s+\\S+") to "Bearer ***",
    Regex("(?i)(\"?api[_-]?key\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+") to "$1***",
)

/**
 * Resolves a user-facing error message from a transcription/text-fix exception. If the throwable
 * is one of our own [OpenRouterException]s we surface its message after scrubbing tokens; for
 * everything else we fall back to [fallbackResId] so unrelated stack traces never leak.
 */
internal fun safeUserFacingError(context: Context, e: Throwable, @StringRes fallbackResId: Int): String {
    if (e is OpenRouterException) {
        if (e.statusCode == 429 || e.statusCode == 503) return context.getString(R.string.voice_error_rate_limited)
        // An empty transcript describes the recording, not the service. Saying so keeps the user
        // from re-recording to work around what reads like a provider outage.
        if (e.statusCode == OpenRouterClient.STATUS_NO_SPEECH) return context.getString(R.string.voice_error_silent)
        val raw = e.message?.takeIf { it.isNotBlank() }
        if (raw != null) {
            return SENSITIVE_USER_FACING_PATTERNS.fold(raw) { acc, (regex, replacement) ->
                acc.replace(regex, replacement)
            }
        }
    }
    return context.getString(fallbackResId)
}

/** Models already disclosed as having fallen back from ZDR, once per process. */
private val zdrWarnedModels: MutableSet<String> =
    Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

internal fun warnAfterZdrFallback(context: Context, model: String) {
    if (!zdrWarnedModels.add(model)) return
    Toast.makeText(
        context,
        context.getString(R.string.voice_warning_zdr_unenforceable, model),
        Toast.LENGTH_LONG,
    ).show()
}

internal fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    @Suppress("DEPRECATION")
    val activeNetwork = cm.activeNetworkInfo ?: return false
    @Suppress("DEPRECATION")
    return activeNetwork.isConnected
}

internal data class ResolvedVoicePrompt(
    val systemPrompt: String,
    val runtimeInstruction: String?,
)

enum class VoiceTranscriptionMode {
    CHAT_AUDIO,

    /**
     * The provider's dedicated speech-to-text endpoint rather than chat completions. Both providers
     * expose one, and both are markedly faster than routing audio through a chat model: measured
     * against PayPerQ, a 30-second clip comes back in about 1.7 s this way versus 10–15 s through
     * a chat model.
     */
    DEDICATED_STT,
}

/**
 * Which engine turns speech into text. [CLOUD] uploads a WAV to the configured AI provider;
 * [ON_DEVICE] hands the microphone to Android's on-device recognizer and never touches the network.
 *
 * The engine covers speech recognition only. Text Fix and auto-polish are LLM features with no
 * on-device equivalent here, so they stay on the cloud provider regardless of this setting.
 */
enum class SpeechEngine(val prefValue: String) {
    CLOUD("cloud"),
    ON_DEVICE("on_device");

    companion object {
        fun fromPref(value: String?): SpeechEngine =
            entries.firstOrNull { it.prefValue == value } ?: CLOUD
    }
}

/**
 * Levels of post-transcription polishing. NATURAL bypasses the polish pass entirely; the rest map
 * to a graded system prompt that the polish LLM uses to clean up the raw transcription.
 */
enum class PolishLevel(val prefValue: String) {
    NATURAL("natural"),
    LIGHT("light"),
    FIXED("fixed"),
    REPHRASED("rephrased"),
    CORRECTED("corrected"),
    POLISHED("polished");

    companion object {
        fun fromPref(value: String?): PolishLevel =
            entries.firstOrNull { it.prefValue == value } ?: FIXED
    }
}

/** Returns the system prompt for [level], or null when no polish pass should run. */
internal fun polishPromptForLevel(level: PolishLevel): String? = when (level) {
    PolishLevel.NATURAL -> null
    PolishLevel.LIGHT -> Defaults.PREF_VOICE_POLISH_PROMPT_LIGHT
    PolishLevel.FIXED -> Defaults.PREF_VOICE_POLISH_PROMPT_FIXED
    PolishLevel.REPHRASED -> Defaults.PREF_VOICE_POLISH_PROMPT_REPHRASED
    PolishLevel.CORRECTED -> Defaults.PREF_VOICE_POLISH_PROMPT_CORRECTED
    PolishLevel.POLISHED -> Defaults.PREF_VOICE_POLISH_PROMPT_POLISHED
}

internal fun resolveVoiceModel(selectedModel: String, customModel: String): String? {
    if (selectedModel != "custom") {
        return selectedModel.trim().takeIf { it.isNotEmpty() }
    }
    return customModel.trim().takeIf { it.isNotEmpty() }
}

internal fun resolveVoiceSttModel(selectedModel: String, customModel: String): String? =
    resolveVoiceModel(selectedModel, customModel)

internal fun Locale.toOpenRouterSttLanguage(): String? =
    language.takeIf { it.length == 2 && it.all { ch -> ch.isLetter() } }?.lowercase(Locale.ROOT)

internal fun resolveVoicePrompt(
    savedPrompt: String,
    localeHint: Locale? = null,
    transcriptionDictionaryRaw: String = "",
    expectedLanguagesRaw: String = "",
): ResolvedVoicePrompt {
    val base = savedPrompt.trim().takeIf { it.isNotEmpty() } ?: Defaults.PREF_VOICE_TRANSCRIPTION_PROMPT
    val dictionaryTerms = parseVoiceDictionaryTerms(transcriptionDictionaryRaw)
    val expectedLanguages = parseExpectedLanguages(expectedLanguagesRaw)
    val systemPrompt = listOfNotNull(
        base,
        dictionaryInstruction(dictionaryTerms),
        expectedLanguagesInstruction(expectedLanguages),
    )
        .joinToString("\n")
        .trim()
    return ResolvedVoicePrompt(
        systemPrompt = systemPrompt,
        runtimeInstruction = localeHintInstruction(systemPrompt, localeHint),
    )
}

internal fun parseExpectedLanguages(raw: String): List<String> =
    raw.split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

internal fun parseVoiceDictionaryTerms(raw: String): List<String> =
    raw.split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }

private fun expectedLanguagesInstruction(languages: List<String>): String? {
    if (languages.isEmpty()) return null
    val joined = languages.joinToString(", ")
    return if (languages.size == 1) {
        "The speaker is expected to speak $joined. Transcribe in the spoken language only; do not translate and do not output multiple versions."
    } else {
        "The speaker may use any of these languages: $joined. Detect which one is actually spoken and transcribe in that language only. Do not translate and do not output the transcript in more than one language."
    }
}

private fun dictionaryInstruction(terms: List<String>): String? {
    if (terms.isEmpty()) return null
    return buildString {
        append("Strict dictionary — these are the canonical spellings for names, brands, acronyms, and technical terms in this transcript. ")
        append("Whenever the audio matches one of these terms phonetically, even loosely, you MUST output the exact spelling, casing, and punctuation shown here, without exception: ")
        append(terms.joinToString(", "))
        append(". Apply this to every occurrence in the audio, not just the first one. Never substitute a homophone, common spelling, or translation. ")
        append("Only ignore an entry when the audio unambiguously says a completely different word.")
    }
}

private fun localeHintInstruction(systemPrompt: String, localeHint: Locale?): String? {
    if (localeHint == null) return null
    val tag = localeHint.toLanguageTag()
    if (tag.isBlank() || tag.equals("und", ignoreCase = true)) return null
    if (systemPrompt.contains(tag, ignoreCase = true)) return null
    val display = localeHint.getDisplayName(Locale.ENGLISH).ifBlank { tag }
    return "Expected spoken language: $display [$tag]."
}
