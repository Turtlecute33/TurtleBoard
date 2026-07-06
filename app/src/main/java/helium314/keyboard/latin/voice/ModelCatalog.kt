// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * Single source of truth for the models the keyboard offers in Voice / STT / Text-Fix
 * dropdowns. Each entry encodes the slug, display name, pricing tier, and whether the
 * model supports OpenRouter ZDR routing and prompt caching. The same data drives:
 *
 *  - the dropdown labels and pills the user sees,
 *  - the slug allow-lists that survive provider-switch fallback in [AiProvider],
 *  - the per-request decision in OpenRouterClient about whether to enforce `zdr: true`.
 *
 * Note on caching: OpenRouterClient attaches a `cache_control` breakpoint to the system prompt
 * of *every* OpenRouter chat request regardless of catalog membership (providers that need it
 * use it; providers that cache implicitly ignore it). The `cache` flag therefore no longer
 * gates the request — it only drives the verified-"CACHE" pill in the picker.
 *
 * The `zdr` flag is verified against OpenRouter's `/api/v1/endpoints/zdr` response — see the
 * changelog for the verification date. ZDR enforcement is best-effort: with the user toggle on,
 * `provider.zdr: true` is emitted only for catalog models flagged `zdr = true`; other
 * models (including custom slugs) skip enforcement so the request still succeeds. The
 * missing ZDR pill in the picker is the user-visible signal that strict ZDR isn't in
 * effect for that model.
 */
internal enum class PricingTier { FREE, CHEAP, MEDIUM, EXPENSIVE }

internal data class ModelEntry(
    val slug: String,
    val displayName: String,
    val tier: PricingTier,
    val zdr: Boolean = false,
    val cache: Boolean = false,
)

internal object ModelCatalog {
    val OPENROUTER_VOICE: List<ModelEntry> = listOf(
        ModelEntry("~google/gemini-flash-latest", "Gemini Flash", PricingTier.CHEAP, zdr = true, cache = true),
        ModelEntry("~google/gemini-pro-latest", "Gemini Pro", PricingTier.MEDIUM, zdr = true, cache = true),
        ModelEntry("mistralai/voxtral-small-24b-2507", "Voxtral Small 24B", PricingTier.CHEAP, cache = true),
        ModelEntry("xiaomi/mimo-v2.5", "MiMo V2.5", PricingTier.CHEAP, cache = true),
        ModelEntry(
            "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
            "Nemotron Nano Omni",
            PricingTier.FREE,
        ),
    )

    val OPENROUTER_STT: List<ModelEntry> = listOf(
        ModelEntry("google/chirp-3", "Chirp 3", PricingTier.CHEAP, zdr = true),
        ModelEntry("openai/whisper-large-v3-turbo", "Whisper Large V3 Turbo", PricingTier.CHEAP, zdr = true),
        ModelEntry("openai/whisper-large-v3", "Whisper Large V3", PricingTier.MEDIUM, zdr = true),
        ModelEntry("openai/whisper-1", "Whisper 1", PricingTier.CHEAP),
    )

    val OPENROUTER_TEXT_FIX: List<ModelEntry> = listOf(
        ModelEntry("~openai/gpt-mini-latest", "GPT Mini", PricingTier.MEDIUM, zdr = true, cache = true),
        ModelEntry("x-ai/grok-4.3", "Grok 4.3", PricingTier.MEDIUM, cache = true),
        ModelEntry("~anthropic/claude-haiku-latest", "Claude Haiku", PricingTier.MEDIUM, zdr = true, cache = true),
        ModelEntry("~google/gemini-flash-latest", "Gemini Flash", PricingTier.CHEAP, zdr = true, cache = true),
        ModelEntry("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash", PricingTier.CHEAP, zdr = true, cache = true),
    )

    // PayPerQ uses its own model namespace (api.ppq.ai/v1/models) and doesn't honor OpenRouter's
    // ZDR or `:free` tier contracts, so capability flags are stripped and free OpenRouter slugs
    // are swapped for their paid PayPerQ equivalents. Some OpenRouter slugs — notably
    // `~author/...-latest` floating aliases — won't resolve on PayPerQ; users on those entries
    // should fall back to Custom Model ID with a slug from PayPerQ's own model list.
    val PAYPERQ_VOICE: List<ModelEntry> = OPENROUTER_VOICE.map { entry ->
        when (entry.slug) {
            "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free" -> entry.copy(
                slug = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
                tier = PricingTier.CHEAP,
                zdr = false,
                cache = false,
            )
            else -> entry.copy(zdr = false, cache = false)
        }
    }
    val PAYPERQ_TEXT_FIX: List<ModelEntry> = OPENROUTER_TEXT_FIX.map { it.copy(zdr = false, cache = false) }

    private val ALL_OPENROUTER_BY_SLUG: Map<String, ModelEntry> =
        (OPENROUTER_VOICE + OPENROUTER_STT + OPENROUTER_TEXT_FIX).associateBy { it.slug }

    fun openRouterSupportsZdr(slug: String): Boolean =
        ALL_OPENROUTER_BY_SLUG[slug]?.zdr == true
}
