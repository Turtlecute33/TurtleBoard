// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZdrRequestTest {
    @Test
    fun enabledZdrIsRequestedForVerifiedModels() {
        val body = JSONObject()
        val client = OpenRouterClient(
            apiKey = "test-key",
            model = "~google/gemini-flash-latest",
            systemPrompt = "prompt",
            runtimeInstruction = null,
            provider = AiProvider.OPENROUTER,
            useZeroDataRetention = true,
        )

        client.putProviderPreferences(body, enforceZdr = true)

        assertTrue(body.getJSONObject("provider").getBoolean("zdr"))
    }

    @Test
    fun enabledZdrIsAlsoRequestedForCustomModels() {
        val body = JSONObject()
        val client = OpenRouterClient(
            apiKey = "test-key",
            model = "custom/provider-model",
            systemPrompt = "prompt",
            runtimeInstruction = null,
            provider = AiProvider.OPENROUTER,
            useZeroDataRetention = true,
        )

        client.putProviderPreferences(body, enforceZdr = true)

        assertTrue(body.getJSONObject("provider").getBoolean("zdr"))
    }

    @Test
    fun disabledZdrDoesNotAddProviderPreferences() {
        val body = JSONObject()
        val client = OpenRouterClient(
            apiKey = "test-key",
            model = "custom/provider-model",
            systemPrompt = "prompt",
            runtimeInstruction = null,
        )

        client.putProviderPreferences(body, enforceZdr = false)

        assertFalse(body.has("provider"))
    }

    @Test
    fun onlyRoutingConstraintErrorsTriggerTheNonZdrRetry() {
        assertTrue(isZdrRouteUnavailable(404, "No endpoints found matching the data policy"))
        assertTrue(isZdrRouteUnavailable(400, "No ZDR endpoint is available"))
        assertFalse(isZdrRouteUnavailable(401, "No endpoints found"))
        assertFalse(isZdrRouteUnavailable(404, "Model does not exist"))
    }
}
