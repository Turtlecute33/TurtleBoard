// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Response bodies here are trimmed copies of real PayPerQ replies. The reasoning-only case is the
 * one that made a transcription fail outright: HTTP 200, `finish_reason: stop`, and the finished
 * text delivered under `reasoning` while `content` is null. It arrived on 3 of 10 identical
 * requests to one model, which is why the parser has to classify it as retryable rather than fatal.
 */
@RunWith(RobolectricTestRunner::class)
class ParseResponseTest {
    private fun client(provider: AiProvider = AiProvider.PAYPERQ) = OpenRouterClient(
        apiKey = "test-key",
        model = "xiaomi/mimo-v2.5",
        systemPrompt = "prompt",
        runtimeInstruction = null,
        provider = provider,
    )

    private fun assertStatus(expected: Int, body: String, parse: (String) -> String) {
        try {
            val text = parse(body)
            fail("expected status $expected, but parsing returned \"$text\"")
        } catch (e: OpenRouterException) {
            assertEquals(expected, e.statusCode, e.message)
        }
    }

    @Test
    fun contentIsReadFromAWellFormedReply() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"Hello there."},"finish_reason":"stop"}]}"""
        assertEquals("Hello there.", client().parseContent(body))
    }

    @Test
    fun replyWithTextOnlyInReasoningIsRetryable() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":null,
            "reasoning":"This is a longer recording used to measure end-to-end latency.",
            "refusal":null},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":303,"completion_tokens":101}}
        """.trimIndent()
        assertStatus(OpenRouterClient.STATUS_UNUSABLE_RESPONSE, body) { client().parseContent(it) }
    }

    @Test
    fun reasoningIsNeverUsedAsTheAnswer() {
        // The same field carries the model's scratchpad just as often as its answer, so nothing
        // in it may reach the user's text field.
        val scratchpad = "First, the user has provided a system instruction: transcribe the audio."
        val body = """{"choices":[{"message":{"role":"assistant","content":"","reasoning":"$scratchpad"}}]}"""
        try {
            val text = client().parseContent(body)
            assertFalse(scratchpad in text, "reasoning text leaked into the result")
            fail("empty content should not parse")
        } catch (e: OpenRouterException) {
            assertFalse(scratchpad in (e.message ?: ""), "reasoning text leaked into the error")
        }
    }

    @Test
    fun errorEnvelopeServedWithHttp200IsRetryable() {
        val body = """{"error":{"message":"Provider returned error","code":400},"user_id":"user_123"}"""
        assertStatus(OpenRouterClient.STATUS_UNUSABLE_RESPONSE, body) { client().parseContent(it) }
    }

    @Test
    fun malformedBodyIsRetryable() {
        assertStatus(OpenRouterClient.STATUS_UNUSABLE_RESPONSE, "<html>502 Bad Gateway</html>") {
            client().parseContent(it)
        }
    }

    @Test
    fun everyUnusableReplyIsRetried() {
        assertTrue(OpenRouterClient.isRetryableStatus(OpenRouterClient.STATUS_UNUSABLE_RESPONSE))
    }

    @Test
    fun transcriptionFallsBackToPayPerQsTranscriptionKey() {
        val body = """{"text":"","transcription":"Hello. This is a test."}"""
        assertEquals("Hello. This is a test.", client().parseTranscriptionContent(body))
    }

    @Test
    fun transcriptionPrefersTextWhenBothArePresent() {
        val body = """{"text":"Hello. This is a test.","transcription":"Hello. This is a test."}"""
        assertEquals("Hello. This is a test.", client().parseTranscriptionContent(body))
    }

    @Test
    fun emptyTranscriptMeansNoSpeechAndIsNotRetried() {
        // Silence reproduces this exactly; re-uploading the same clip would answer the same way
        // and only cost the user another request.
        assertStatus(OpenRouterClient.STATUS_NO_SPEECH, """{"text":"","transcription":""}""") {
            client().parseTranscriptionContent(it)
        }
        assertFalse(OpenRouterClient.isRetryableStatus(OpenRouterClient.STATUS_NO_SPEECH))
    }

    @Test
    fun plainTextTranscriptionBodyIsAccepted() {
        assertEquals("Hello. This is a test.", client().parseTranscriptionContent("Hello. This is a test.\n"))
    }
}
