// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.util.Base64
import androidx.annotation.VisibleForTesting
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Sends AI requests to the selected provider.
 * Must be called from a background thread.
 */
class OpenRouterClient(
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val runtimeInstruction: String?,
    private val provider: AiProvider = AiProvider.OPENROUTER,
    private val useZeroDataRetention: Boolean = false,
    private val transcriptionMode: VoiceTranscriptionMode = VoiceTranscriptionMode.CHAT_AUDIO,
    private val transcriptionLanguage: String? = null,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    @Volatile private var activeConnection: HttpURLConnection? = null

    /**
     * Set by [cancel] before the connection is torn down. Disconnecting an in-flight request
     * surfaces as a plain [java.io.IOException], which the retry policy would otherwise treat as a
     * transient network fault and answer by re-uploading the whole recording. Checked on every
     * retry decision and inside the upload loops so a cancelled request stops sending audio.
     */
    @Volatile private var cancelled = false
    @Volatile var didFallbackFromZdr: Boolean = false
        private set

    companion object {
        private const val TAG = "OpenRouterClient"
        const val API_BASE = "https://openrouter.ai/api/v1"
        private const val ENDPOINT = "$API_BASE/chat/completions"
        private const val OPENROUTER_TRANSCRIPTION_ENDPOINT = "$API_BASE/audio/transcriptions"
        const val KEY_ENDPOINT = "$API_BASE/key"
        const val PAYPERQ_API_BASE = "https://api.ppq.ai"
        const val PAYPERQ_CHAT_ENDPOINT = "$PAYPERQ_API_BASE/chat/completions"
        const val PAYPERQ_MODELS_ENDPOINT = "$PAYPERQ_API_BASE/v1/models"
        const val PAYPERQ_TRANSCRIPTION_ENDPOINT = "$PAYPERQ_API_BASE/v1/audio/transcriptions"
        const val PAYPERQ_AUDIO_MODELS_ENDPOINT = "$PAYPERQ_API_BASE/v1/audio/models"
        /** Template: call [modelEndpointUrl] to fill in the model id safely. */
        fun modelEndpointUrl(author: String, slug: String): String = "$API_BASE/models/$author/$slug/endpoints"
        private const val STABLE_AUDIO_INSTRUCTION = "Process the attached audio input according to the system instructions. Return only the final answer."
        private const val APP_REFERER = "https://github.com/Turtlecute33/WisprBoard"
        private const val APP_TITLE = "WisprBoard"
        private const val APP_CATEGORIES = "writing-assistant"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 90_000
        private const val MAX_ATTEMPTS = 3
        /**
         * Sentinel status for "HTTP 200 with a body we cannot use" — a JSON body we can't parse, a
         * `{"error": …}` envelope served with a 200 (PayPerQ does this when its upstream fails), no
         * `choices`, or an assistant message whose content is empty because the model spent its
         * whole completion on reasoning tokens. Observed on PayPerQ: rare, transient, and cleared by
         * repeating the same request. It is retryable for that reason, but it is deliberately NOT a
         * real HTTP status so nothing can collide with it.
         */
        const val STATUS_UNUSABLE_RESPONSE = -2
        /** Sentinel for a transcription that came back empty because the clip held no speech. */
        const val STATUS_NO_SPEECH = -3
        private val RETRYABLE_STATUSES = setOf(408, 425, 429, 500, 502, 503, 504, STATUS_UNUSABLE_RESPONSE)
        // Sanity cap to prevent pathological responses from consuming unbounded memory.
        // Real transcription responses are kilobytes; 1 MB is ~3 orders of magnitude of headroom.
        private const val MAX_RESPONSE_BYTES = 1_000_000L
        // Error bodies are retained only for narrow response classification (for example, detecting
        // an unavailable ZDR route); cap them tightly so a misbehaving server cannot consume memory.
        private const val MAX_ERROR_BYTES = 64 * 1024L
        // Upper bound on how long we'll honor a server-supplied Retry-After, to stay responsive.
        private const val MAX_RETRY_AFTER_MS = 30_000L
        // Plain ASCII sentinel: org.json escapes control characters (e.g. U+0000 -> literal "\u0000"),
        // which used to make the placeholder un-findable in the serialized body. Unlikely to collide
        // with real content.
        private const val AUDIO_PLACEHOLDER = "__WISPRBOARD_AUDIO_B64_PLACEHOLDER__"
        // Must be a multiple of 3 so chunked base64 encoding is padding-free until the final chunk.
        private const val AUDIO_READ_CHUNK = 48 * 1024
        private val HTTP_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }
        }

        @VisibleForTesting
        internal fun isRetryableStatus(statusCode: Int): Boolean = statusCode in RETRYABLE_STATUSES

        fun applyOpenRouterAttributionHeaders(connection: HttpURLConnection) {
            connection.setRequestProperty("HTTP-Referer", APP_REFERER)
            connection.setRequestProperty("X-OpenRouter-Title", APP_TITLE)
            connection.setRequestProperty("X-OpenRouter-Categories", APP_CATEGORIES)
        }
    }

    /**
     * Performs one transcription request, retrying transient failures with exponential backoff.
     * Streams [audioFile] to the server via chunked transfer encoding so peak heap is bounded
     * regardless of recording length. Throws [OpenRouterException] on non-retryable failures
     * or after retries are exhausted. The caller owns the file and must delete it.
     */
    fun transcribe(audioFile: File): String = withOptionalZdr("Transcription") { enforceZdr ->
        val dedicatedStt = transcriptionMode == VoiceTranscriptionMode.DEDICATED_STT
        when {
            provider == AiProvider.OPENROUTER && dedicatedStt ->
                performOpenRouterSttTranscription(audioFile, enforceZdr)
            // PayPerQ rejects a transcription model on chat/completions with a 400 that names the
            // right endpoint, so the choice of endpoint has to follow the mode, not the shape of
            // the slug. The legacy slash test stays as a safety net for a bare slug like `nova-3`
            // saved before the mode existed.
            provider == AiProvider.PAYPERQ && (dedicatedStt || "/" !in model) ->
                performPayPerQTranscription(audioFile)
            else -> performRequest(audioFile, enforceZdr)
        }
    }

    /**
     * Aborts the in-flight request, if any. Safe to call from any thread. Once cancelled, this
     * client will not start another attempt; the request thread observes [InterruptedException].
     */
    fun cancel() {
        // Order matters: the flag must be visible before the disconnect wakes the request thread
        // up with an IOException, otherwise that thread can decide to retry before it sees it.
        cancelled = true
        activeConnection?.disconnect()
    }

    /**
     * Sends [userText] as a chat completion request (no audio) and returns the assistant's
     * reply. Uses [systemPrompt] as the system message. Retries transient failures with the
     * same policy as [transcribe].
     */
    fun fixText(userText: String): String = withOptionalZdr("Request") { enforceZdr ->
        performTextRequest(userText, enforceZdr)
    }

    private inline fun <T> withOptionalZdr(label: String, request: (Boolean) -> T): T {
        val requestZdr = provider == AiProvider.OPENROUTER
            && useZeroDataRetention
        return try {
            withRetries(label, requestZdr) { request(requestZdr) }
        } catch (e: OpenRouterException) {
            if (!requestZdr || !e.isZdrRouteUnavailable()) throw e
            // ZDR is a preference, not a hard requirement. Retry once through normal routing so
            // unsupported, custom, or temporarily unavailable ZDR routes do not break the feature.
            didFallbackFromZdr = true
            withRetries(label, false) { request(false) }
        }
    }

    /**
     * Runs [request] up to [MAX_ATTEMPTS] times with exponential backoff (or server-supplied
     * Retry-After), translating retryable HTTP statuses, socket timeouts, and IOExceptions into
     * sleeps. Throws on the final attempt or non-retryable failures. The [label] is used only
     * for the terminal error message ("$label failed [after retries]").
     */
    private inline fun <T> withRetries(label: String, enforceZdr: Boolean, request: () -> T): T {
        var lastError: Exception? = null
        var nextDelayOverrideMs: Long = -1L
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            if (cancelled) throw InterruptedException()
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                return request()
            } catch (e: OpenRouterException) {
                if (cancelled) throw InterruptedException()
                if (provider == AiProvider.OPENROUTER && enforceZdr && e.isZdrRouteUnavailable()) {
                    throw OpenRouterException(
                        "No zero data retention route is available for this model",
                        e.statusCode,
                        e.retryAfterMs,
                        e.errorBody,
                    )
                }
                if (e.statusCode !in RETRYABLE_STATUSES || attempt == MAX_ATTEMPTS - 1) throw e
                lastError = e
                nextDelayOverrideMs = if ((e.statusCode == 429 || e.statusCode == 503) && e.retryAfterMs > 0) e.retryAfterMs else -1L
            } catch (e: SocketTimeoutException) {
                if (cancelled) throw InterruptedException()
                if (attempt == MAX_ATTEMPTS - 1) throw OpenRouterException("Request timed out")
                lastError = e
                nextDelayOverrideMs = -1L
            } catch (e: InterruptedIOException) {
                throw InterruptedException()
            } catch (e: java.io.IOException) {
                // A user-initiated cancel lands here (disconnect closes the socket). Retrying would
                // re-upload the audio the user just asked us to drop.
                if (cancelled) throw InterruptedException()
                // Never propagate the underlying IOException — on some Android stacks its message
                // includes the full request URL plus headers (Authorization: Bearer …). We throw a
                // bare OpenRouterException with no cause attached on purpose; do NOT pass `cause = e`
                // here, or a logged stack trace could leak the API key.
                if (attempt == MAX_ATTEMPTS - 1) throw OpenRouterException("Network error")
                lastError = e
                nextDelayOverrideMs = -1L
            }
            val delayMs = if (nextDelayOverrideMs > 0) nextDelayOverrideMs else (500L shl attempt).coerceAtMost(4_000L)
            if (BuildConfig.DEBUG) Log.i(TAG, "Retrying after ${delayMs}ms (attempt ${attempt + 1})")
            try { Thread.sleep(delayMs) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            attempt++
        }
        throw OpenRouterException(if (lastError == null) "$label failed" else "$label failed after retries")
    }

    private fun performTextRequest(userText: String, enforceZdr: Boolean): String {
        val messages = JSONArray().apply {
            put(buildSystemMessage())
            put(buildTextMessage(userText))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            putProviderPreferences(this, enforceZdr)
        }.toString().toByteArray(Charsets.UTF_8)

        val connection = (URL(chatEndpoint()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            if (provider == AiProvider.OPENROUTER) applyOpenRouterAttributionHeaders(this)
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setFixedLengthStreamingMode(body.size)
        }
        activeConnection = connection
        try {
            connection.outputStream.use { it.write(body) }
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = readErrorBodyCapped(connection.errorStream)
                if (BuildConfig.DEBUG) Log.e(TAG, "API error $responseCode")
                val retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                throw OpenRouterException("API error: $responseCode", responseCode, retryAfterMs, errorBody)
            }
            val responseBody = readCappedString(connection.inputStream, MAX_RESPONSE_BYTES)
            return parseContent(responseBody)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    /**
     * Builds the full JSON request body except for the base64 audio payload, which is
     * streamed separately. The placeholder sentinel is split in half so both halves can be
     * emitted verbatim around the live base64 stream.
     */
    private fun buildRequestEnvelope(enforceZdr: Boolean): Pair<String, String> {
        val messages = JSONArray().apply {
            put(buildSystemMessage())
            put(buildTextMessage(STABLE_AUDIO_INSTRUCTION))
            runtimeInstruction?.takeIf { it.isNotBlank() }?.let { put(buildTextMessage(it)) }
            put(buildAudioMessage(AUDIO_PLACEHOLDER))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            putProviderPreferences(this, enforceZdr)
        }.toString()
        val placeholderIndex = body.indexOf(AUDIO_PLACEHOLDER)
        check(placeholderIndex >= 0) { "Audio placeholder not found in request body" }
        return body.substring(0, placeholderIndex) to body.substring(placeholderIndex + AUDIO_PLACEHOLDER.length)
    }

    internal fun putProviderPreferences(body: JSONObject, enforceZdr: Boolean) {
        if (!enforceZdr || provider != AiProvider.OPENROUTER) return
        // ZDR is a best-effort preference: every enabled OpenRouter request asks for it first.
        // If routing cannot satisfy the constraint, withOptionalZdr retries without it.
        body.put("provider", JSONObject().apply { put("zdr", true) })
    }

    private fun buildSystemMessage(): JSONObject {
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", systemPrompt)
            // Attach a prompt-cache breakpoint on the (stable) system prompt for every request.
            // Providers that need an explicit breakpoint (Anthropic, legacy Gemini) get one;
            // providers that cache implicitly (OpenAI, Gemini 2.5+, Grok, DeepSeek) ignore it
            // harmlessly. Caching only actually engages once the cached prefix clears the provider's
            // minimum-token floor (~1K for Gemini Flash), so short prompts still won't cache.
            // PayPerQ accepts the breakpoint and reports cache hits back in
            // `usage.prompt_tokens_details.cached_tokens`, so it is sent there too.
            put("cache_control", JSONObject().apply { put("type", "ephemeral") })
        }
        return JSONObject().apply {
            put("role", "system")
            put("content", JSONArray().apply { put(textContent) })
        }
    }

    private fun buildTextMessage(text: String): JSONObject {
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", text)
        }
        return JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply { put(textContent) })
        }
    }

    private fun buildAudioMessage(base64Audio: String): JSONObject {
        val audioContent = JSONObject().apply {
            put("type", "input_audio")
            put("input_audio", JSONObject().apply {
                put("data", base64Audio)
                put("format", "wav")
            })
        }
        return JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply { put(audioContent) })
        }
    }

    private fun performRequest(audioFile: File, enforceZdr: Boolean): String {
        val (prefix, suffix) = buildRequestEnvelope(enforceZdr)
        val connection = (URL(chatEndpoint()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            if (provider == AiProvider.OPENROUTER) applyOpenRouterAttributionHeaders(this)
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            // Chunked streaming keeps the upload out of HttpURLConnection's internal buffer;
            // without it the entire body (including audio) would be buffered in memory before
            // the request is sent.
            setChunkedStreamingMode(0)
        }
        activeConnection = connection
        try {
            connection.outputStream.use { out ->
                val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
                out.write(prefixBytes)
                streamBase64Audio(audioFile, out)
                out.write(suffix.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = readErrorBodyCapped(connection.errorStream)
                if (BuildConfig.DEBUG) Log.e(TAG, "API error $responseCode")
                val retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                throw OpenRouterException("API error: $responseCode", responseCode, retryAfterMs, errorBody)
            }

            val responseBody = readCappedString(connection.inputStream, MAX_RESPONSE_BYTES)
            return parseContent(responseBody)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun chatEndpoint(): String = when (provider) {
        AiProvider.OPENROUTER -> ENDPOINT
        AiProvider.PAYPERQ -> PAYPERQ_CHAT_ENDPOINT
    }

    private fun buildOpenRouterSttEnvelope(enforceZdr: Boolean): Pair<String, String> {
        val prompt = listOfNotNull(
            systemPrompt.takeIf { it.isNotBlank() },
            runtimeInstruction?.takeIf { it.isNotBlank() },
        ).joinToString("\n").take(1_000)
        val body = JSONObject().apply {
            put("model", model)
            put("input_audio", JSONObject().apply {
                put("data", AUDIO_PLACEHOLDER)
                put("format", "wav")
            })
            transcriptionLanguage?.takeIf { it.isNotBlank() }?.let { put("language", it) }
            put("temperature", 0)
            if (prompt.isNotBlank()) put("prompt", prompt)
            putProviderPreferences(this, enforceZdr)
            if (prompt.isNotBlank()) {
                val providerObject = optJSONObject("provider") ?: JSONObject().also { put("provider", it) }
                providerObject.put("options", JSONObject().apply {
                    put("groq", JSONObject().apply { put("prompt", prompt) })
                    put("openai", JSONObject().apply { put("prompt", prompt) })
                })
            }
        }.toString()
        val placeholderIndex = body.indexOf(AUDIO_PLACEHOLDER)
        check(placeholderIndex >= 0) { "Audio placeholder not found in STT request body" }
        return body.substring(0, placeholderIndex) to body.substring(placeholderIndex + AUDIO_PLACEHOLDER.length)
    }

    private fun performOpenRouterSttTranscription(audioFile: File, enforceZdr: Boolean): String {
        val (prefix, suffix) = buildOpenRouterSttEnvelope(enforceZdr)
        val connection = (URL(OPENROUTER_TRANSCRIPTION_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            applyOpenRouterAttributionHeaders(this)
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setChunkedStreamingMode(0)
        }
        activeConnection = connection
        try {
            connection.outputStream.use { out ->
                out.write(prefix.toByteArray(Charsets.UTF_8))
                streamBase64Audio(audioFile, out)
                out.write(suffix.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = readErrorBodyCapped(connection.errorStream)
                if (BuildConfig.DEBUG) Log.e(TAG, "API error $responseCode")
                val retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                throw OpenRouterException("API error: $responseCode", responseCode, retryAfterMs, errorBody)
            }

            val responseBody = readCappedString(connection.inputStream, MAX_RESPONSE_BYTES)
            return parseTranscriptionContent(responseBody)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun performPayPerQTranscription(audioFile: File): String {
        // A neutral boundary: PayPerQ gets no attribution headers, so naming the app here would be
        // the one thing in the request that identifies which client sent it.
        val boundary = "----${UUID.randomUUID()}"
        val connection = (URL(PAYPERQ_TRANSCRIPTION_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setChunkedStreamingMode(0)
        }
        activeConnection = connection
        try {
            DataOutputStream(connection.outputStream).use { out ->
                writeMultipartField(out, boundary, "model", model)
                writeMultipartField(out, boundary, "response_format", "json")
                val prompt = listOfNotNull(
                    systemPrompt.takeIf { it.isNotBlank() },
                    runtimeInstruction?.takeIf { it.isNotBlank() },
                ).joinToString("\n")
                if (prompt.isNotBlank()) writeMultipartField(out, boundary, "prompt", prompt)
                writeMultipartFile(out, boundary, "file", audioFile, "audio/wav")
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = readErrorBodyCapped(connection.errorStream)
                if (BuildConfig.DEBUG) Log.e(TAG, "API error $responseCode")
                val retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                throw OpenRouterException("API error: $responseCode", responseCode, retryAfterMs, errorBody)
            }

            val responseBody = readCappedString(connection.inputStream, MAX_RESPONSE_BYTES)
            return parseTranscriptionContent(responseBody)
        } finally {
            activeConnection = null
            connection.disconnect()
        }
    }

    private fun writeMultipartField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }

    private fun writeMultipartFile(out: DataOutputStream, boundary: String, name: String, file: File, contentType: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"audio.wav\"\r\n")
        out.writeBytes("Content-Type: $contentType\r\n\r\n")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                if (cancelled) throw InterruptedException()
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val read = input.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
            }
        }
        out.writeBytes("\r\n")
    }

    @VisibleForTesting
    internal fun parseContent(responseBody: String): String {
        val json = try {
            JSONObject(responseBody)
        } catch (e: JSONException) {
            throw unusableResponse("Malformed API response")
        }
        logCacheUsage(json)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            // PayPerQ serves `{"error": {...}}` with a 200 when its upstream fails, so an absent
            // `choices` is an upstream fault rather than a malformed reply. Either way it is the
            // same transient, retryable condition.
            throw unusableResponse("API response missing choices")
        }
        val message = choices.optJSONObject(0)?.optJSONObject("message")
        val content = extractMessageText(message)
        if (content.isEmpty()) {
            // Reasoning models sometimes answer with `content: null` and everything they produced
            // in `reasoning` instead. Repeating the request clears it. We do not read `reasoning`
            // as a substitute: the same model fills that field with its scratchpad ("First, the
            // user has provided a system instruction…") just as often as with the finished answer,
            // and typing a scratchpad into the user's text field is worse than one retry.
            throw unusableResponse("API response missing content")
        }
        return content
    }

    private fun unusableResponse(message: String) =
        OpenRouterException(message, STATUS_UNUSABLE_RESPONSE)

    @VisibleForTesting
    internal fun extractMessageText(message: JSONObject?): String {
        if (message == null) return ""
        message.optJSONObject("audio")?.optString("transcript")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        // `"content": null` arrives as JSONObject.NULL, which is not a Kotlin null. Left to the
        // toString fallback below it stringifies to the word "null" — a reply that reads as a
        // successful transcription and types "null" into the user's text field.
        val rawContent = message.opt("content")?.takeIf { it != JSONObject.NULL } ?: return ""
        if (rawContent is String) return rawContent.trim()
        if (rawContent is JSONArray) {
            val parts = mutableListOf<String>()
            for (i in 0 until rawContent.length()) {
                val item = rawContent.optJSONObject(i) ?: continue
                item.optString("text").trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
                item.optJSONObject("audio")?.optString("transcript")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    parts.add(it)
                }
            }
            return parts.joinToString("\n").trim()
        }
        return rawContent.toString().trim()
    }

    @VisibleForTesting
    internal fun parseTranscriptionContent(responseBody: String): String {
        val json = try {
            JSONObject(responseBody)
        } catch (e: JSONException) {
            return responseBody.trim().takeIf { it.isNotEmpty() }
                ?: throw unusableResponse("Malformed API response")
        }
        // OpenRouter answers with `text`; PayPerQ mirrors the same value under `transcription`.
        val text = json.optString("text").trim().takeIf { it.isNotEmpty() }
            ?: json.optString("transcription").trim()
        if (text.isEmpty()) {
            // A transcription endpoint that returns an empty string heard no speech — silence, or
            // a clip that is only background noise. That is a property of the recording, so
            // re-uploading it would fail identically and only cost the user another request.
            throw OpenRouterException("No speech detected", STATUS_NO_SPEECH)
        }
        return text
    }

    private fun logCacheUsage(json: JSONObject) {
        if (!BuildConfig.DEBUG) return
        val details = json.optJSONObject("usage")
            ?.optJSONObject("prompt_tokens_details")
            ?: return
        val cachedTokens = details.optInt("cached_tokens", 0)
        val cacheWriteTokens = details.optInt("cache_write_tokens", 0)
        if (cachedTokens <= 0 && cacheWriteTokens <= 0) return
        Log.i(
            TAG,
            "Prompt cache stats for $model: cached_tokens=$cachedTokens, cache_write_tokens=$cacheWriteTokens"
        )
    }

    /**
     * Reads [audioFile] in 48 KiB blocks (multiple of 3 for padding-free base64) and
     * writes the encoded bytes straight to [out], avoiding any full-body buffer.
     */
    private fun streamBase64Audio(audioFile: File, out: OutputStream) {
        FileInputStream(audioFile).use { fis -> encodeBase64Stream(fis, out) }
    }

    /**
     * Base64-encodes [input] into [out] in a streaming fashion.
     *
     * Only complete [AUDIO_READ_CHUNK]-sized blocks are encoded mid-stream. `InputStream.read` is
     * allowed to return fewer bytes than requested, and encoding a chunk whose length is not a
     * multiple of 3 emits `=` padding — mid-stream that padding lands inside the JSON string and
     * corrupts the request body (intermittent 400s). So we accumulate short reads until the block
     * is full and only let the trailing partial block pad, which is exactly where padding belongs.
     */
    @VisibleForTesting
    internal fun encodeBase64Stream(input: java.io.InputStream, out: OutputStream) {
        val buf = ByteArray(AUDIO_READ_CHUNK)
        var filled = 0
        while (true) {
            if (cancelled) throw InterruptedException()
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val n = input.read(buf, filled, buf.size - filled)
            if (n == -1) break
            filled += n
            if (filled == buf.size) {
                out.write(Base64.encode(buf, 0, filled, Base64.NO_WRAP))
                filled = 0
            }
        }
        if (filled > 0) out.write(Base64.encode(buf, 0, filled, Base64.NO_WRAP))
    }

    private fun readCappedString(input: java.io.InputStream, maxBytes: Long): String {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0L
        input.use { stream ->
            while (true) {
                val n = stream.read(chunk)
                if (n == -1) break
                total += n
                if (total > maxBytes) throw OpenRouterException("Response too large")
                buf.write(chunk, 0, n)
            }
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    /**
     * Reads up to [MAX_ERROR_BYTES] from [stream] for internal error classification. Unlike the
     * success-path reader, hitting the cap is not an error. The returned content must never be
     * logged or shown to users because providers may echo request data.
     */
    private fun readErrorBodyCapped(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4 * 1024)
        var total = 0L
        stream.use { s ->
            while (true) {
                val n = s.read(chunk)
                if (n == -1) break
                val writable = kotlin.math.min(n.toLong(), MAX_ERROR_BYTES - total).toInt()
                if (writable > 0) { buf.write(chunk, 0, writable); total += writable }
                if (total >= MAX_ERROR_BYTES) break
            }
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    /**
     * Parses an HTTP `Retry-After` header value. Accepts either a non-negative integer number of
     * seconds (RFC 7231 delta-seconds) or an HTTP-date. Returns -1 when absent/unparseable,
     * otherwise the delay in milliseconds clamped to `[0, MAX_RETRY_AFTER_MS]`.
     */
    @VisibleForTesting
    internal fun parseRetryAfterMs(header: String?): Long {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return -1L
        raw.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return -1L
            if (seconds > MAX_RETRY_AFTER_MS / 1000L) return MAX_RETRY_AFTER_MS
            return (seconds * 1000L).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }
        return try {
            val epochMs = (HTTP_DATE_FORMAT.get() ?: return -1L).parse(raw)?.time ?: return -1L
            val deltaMs = epochMs - System.currentTimeMillis()
            deltaMs.coerceIn(0L, MAX_RETRY_AFTER_MS)
        } catch (_: Exception) {
            -1L
        }
    }

    private fun OpenRouterException.isZdrRouteUnavailable(): Boolean {
        return isZdrRouteUnavailable(statusCode, errorBody)
    }
}

internal fun isZdrRouteUnavailable(statusCode: Int, errorBody: String): Boolean {
    if (statusCode !in setOf(400, 404, 409, 422)) return false
    val lowerBody = errorBody.lowercase(Locale.US)
    return lowerBody.contains("zdr") ||
        lowerBody.contains("zero data") ||
        lowerBody.contains("data retention") ||
        lowerBody.contains("no endpoint")
}

class OpenRouterException(
    message: String,
    val statusCode: Int = -1,
    val retryAfterMs: Long = -1L,
    val errorBody: String = "",
) : Exception(message)
