// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.settings.Defaults

enum class AiProvider(val prefValue: String) {
    OPENROUTER("openrouter"),
    PAYPERQ("payperq");

    companion object {
        fun fromPref(value: String?): AiProvider =
            values().firstOrNull { it.prefValue == value } ?: OPENROUTER
    }
}

internal fun AiProvider.apiKeyPrefKey(): String = when (this) {
    AiProvider.OPENROUTER -> helium314.keyboard.latin.settings.Settings.PREF_OPENROUTER_API_KEY
    AiProvider.PAYPERQ -> helium314.keyboard.latin.settings.Settings.PREF_PAYPERQ_API_KEY
}

internal fun AiProvider.defaultApiKey(): String = when (this) {
    AiProvider.OPENROUTER -> Defaults.PREF_OPENROUTER_API_KEY
    AiProvider.PAYPERQ -> Defaults.PREF_PAYPERQ_API_KEY
}

internal fun resolveProviderModel(selectedModel: String, customModel: String): String? =
    resolveVoiceModel(selectedModel, customModel)

internal const val MODEL_CUSTOM = "custom"

/**
 * Slugs (other than [MODEL_CUSTOM]) that each picker offers, derived from [ModelCatalog].
 * Used to decide whether the user's saved selection is still valid after switching provider
 * — if it is, we leave it alone instead of silently overwriting a deliberate choice.
 *
 * Note: OpenRouter "latest" floating aliases are catalogued literally as `~author/model-latest`
 * (e.g. `~google/gemini-flash-latest`). The leading tilde is part of the slug — requests using
 * the bare form return 400 "not a valid model ID". Pinned slugs use the bare form.
 */
private val OPENROUTER_VOICE_SLUGS: Set<String> = ModelCatalog.OPENROUTER_VOICE.mapTo(LinkedHashSet()) { it.slug }
private val OPENROUTER_STT_SLUGS: Set<String> = ModelCatalog.OPENROUTER_STT.mapTo(LinkedHashSet()) { it.slug }
private val PAYPERQ_STT_SLUGS: Set<String> = ModelCatalog.PAYPERQ_STT.mapTo(LinkedHashSet()) { it.slug }
private val PAYPERQ_VOICE_SLUGS: Set<String> = ModelCatalog.PAYPERQ_VOICE.mapTo(LinkedHashSet()) { it.slug }
private val OPENROUTER_TEXT_FIX_SLUGS: Set<String> = ModelCatalog.OPENROUTER_TEXT_FIX.mapTo(LinkedHashSet()) { it.slug }
private val PAYPERQ_TEXT_FIX_SLUGS: Set<String> = ModelCatalog.PAYPERQ_TEXT_FIX.mapTo(LinkedHashSet()) { it.slug }

internal fun AiProvider.supportsVoiceSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return slug in when (this) {
        AiProvider.OPENROUTER -> OPENROUTER_VOICE_SLUGS
        AiProvider.PAYPERQ -> PAYPERQ_VOICE_SLUGS
    }
}

internal fun AiProvider.supportsSttSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return slug in when (this) {
        AiProvider.OPENROUTER -> OPENROUTER_STT_SLUGS
        AiProvider.PAYPERQ -> PAYPERQ_STT_SLUGS
    }
}

/**
 * The transcription model to fall back to when the saved one belongs to the other provider. The two
 * dedicated endpoints do not share a namespace, so there is no single default that suits both.
 */
internal fun AiProvider.defaultSttModel(): String = when (this) {
    AiProvider.OPENROUTER -> Defaults.PREF_VOICE_STT_MODEL
    AiProvider.PAYPERQ -> ModelCatalog.PAYPERQ_STT.first().slug
}

internal fun AiProvider.supportsTextFixSlug(slug: String): Boolean {
    if (slug == MODEL_CUSTOM) return true
    return slug in when (this) {
        AiProvider.OPENROUTER -> OPENROUTER_TEXT_FIX_SLUGS
        AiProvider.PAYPERQ -> PAYPERQ_TEXT_FIX_SLUGS
    }
}

// Accepts: author/slug, author/slug:variant, ~author/slug-latest. Rejects whitespace, bare names,
// missing slug. Empty input is allowed so the user can clear the field without a validation block.
private val CUSTOM_MODEL_SLUG_REGEX = Regex("""^~?[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?/[A-Za-z0-9](?:[A-Za-z0-9._:-]*[A-Za-z0-9])?$""")

internal fun isValidCustomModelSlug(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return true
    return CUSTOM_MODEL_SLUG_REGEX.matches(trimmed)
}
