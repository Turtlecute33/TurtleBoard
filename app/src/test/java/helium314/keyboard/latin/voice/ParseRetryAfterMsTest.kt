// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ParseRetryAfterMsTest {
    private val client = OpenRouterClient(
        apiKey = "unused",
        model = "unused/unused",
        systemPrompt = "unused",
        runtimeInstruction = null,
    )

    @Test
    fun nullReturnsMinusOne() {
        assertEquals(-1L, client.parseRetryAfterMs(null))
    }

    @Test
    fun zeroSecondsReturnsZero() {
        assertEquals(0L, client.parseRetryAfterMs("0"))
    }

    @Test
    fun fiveSecondsReturnsFiveThousandMs() {
        assertEquals(5_000L, client.parseRetryAfterMs("5"))
    }

    @Test
    fun thirtySecondsReturnsThirtyThousandMs() {
        assertEquals(30_000L, client.parseRetryAfterMs("30"))
    }

    @Test
    fun sixtySecondsIsClampedToThirtyThousand() {
        assertEquals(30_000L, client.parseRetryAfterMs("60"))
    }

    @Test
    fun hugeSecondsValueIsClampedWithoutOverflow() {
        assertEquals(30_000L, client.parseRetryAfterMs(Long.MAX_VALUE.toString()))
    }

    @Test
    fun httpDateTenSecondsInFutureIsApproximatelyTenThousandMs() {
        val futureMs = System.currentTimeMillis() + 10_000L
        // RFC 1123 / HTTP-date format.
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val header = formatter.format(Date(futureMs))
        val parsed = client.parseRetryAfterMs(header)
        assertTrue("expected ~10000ms, got $parsed", parsed in 8_000L..12_000L)
    }

    @Test
    fun garbageStringReturnsMinusOne() {
        assertEquals(-1L, client.parseRetryAfterMs("not-a-date-or-number"))
    }

    @Test
    fun negativeNumberReturnsMinusOne() {
        assertEquals(-1L, client.parseRetryAfterMs("-5"))
    }
}
