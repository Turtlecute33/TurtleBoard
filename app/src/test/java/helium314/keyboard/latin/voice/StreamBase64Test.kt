// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The audio upload base64-encodes the WAV as it streams, so the encoder must only emit padding at
 * the very end. `InputStream.read` is free to return fewer bytes than asked for; encoding such a
 * short chunk on its own appends `=` padding in the middle of the JSON string, which the server
 * rejects as a malformed body (intermittent 400s that are near-impossible to reproduce by hand).
 */
@RunWith(RobolectricTestRunner::class)
class StreamBase64Test {

    /** Returns at most [limit] bytes per read, mimicking a stream that hands back short counts. */
    private class ChokedInputStream(data: ByteArray, private val limit: Int) : InputStream() {
        private val delegate = ByteArrayInputStream(data)
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, minOf(len, limit))
    }

    private fun client() = OpenRouterClient(
        apiKey = "test-key",
        model = "test/model",
        systemPrompt = "prompt",
        runtimeInstruction = null,
    )

    private fun encode(data: ByteArray, readLimit: Int): String {
        val out = ByteArrayOutputStream()
        client().encodeBase64Stream(ChokedInputStream(data, readLimit), out)
        return out.toString(Charsets.UTF_8.name())
    }

    private fun expected(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    @Test
    fun shortReadsStillProduceTheSameEncodingAsEncodingAtOnce() {
        // Larger than one 48 KiB block so the block-boundary path is exercised too.
        val data = ByteArray(150_000) { (it % 251).toByte() }
        // 1 and 2 are the worst cases: neither is a multiple of 3.
        for (limit in intArrayOf(1, 2, 7, 1_000, 48 * 1024 - 1)) {
            assertEquals("readLimit=$limit", expected(data), encode(data, limit))
        }
    }

    @Test
    fun paddingOnlyEverAppearsAtTheEnd() {
        // Length deliberately not a multiple of 3, so the result must end in padding.
        val data = ByteArray(100_001) { (it % 251).toByte() }
        val encoded = encode(data, 2)
        val body = encoded.trimEnd('=')
        assertFalse("padding found mid-stream", body.contains('='))
        assertEquals(expected(data), encoded)
    }

    @Test
    fun exactBlockMultipleProducesNoPadding() {
        val data = ByteArray(48 * 1024 * 2) { (it % 251).toByte() }
        val encoded = encode(data, 3)
        assertFalse(encoded.contains('='))
        assertEquals(expected(data), encoded)
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertEquals("", encode(ByteArray(0), 1))
    }
}
