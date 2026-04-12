// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.util.Base64
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends audio to OpenRouter's chat completions API for transcription.
 * Must be called from a background thread.
 */
class OpenRouterClient(
    private val apiKey: String,
    private val model: String
) {
    companion object {
        private const val TAG = "OpenRouterClient"
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    fun transcribe(wavData: ByteArray): String {
        val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)

        val audioContent = JSONObject().apply {
            put("type", "input_audio")
            put("input_audio", JSONObject().apply {
                put("data", base64Audio)
                put("format", "wav")
            })
        }

        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", "Transcribe this audio exactly as spoken. Output only the transcription, nothing else.")
        }

        val message = JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(audioContent)
                put(textContent)
            })
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { put(message) })
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
        }

        try {
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "API error $responseCode: $errorBody")
                throw OpenRouterException("API error: $responseCode", responseCode)
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(responseBody)
            val content = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            return content
        } finally {
            connection.disconnect()
        }
    }
}

class OpenRouterException(message: String, val statusCode: Int = -1) : Exception(message)
