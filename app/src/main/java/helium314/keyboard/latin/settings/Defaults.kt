package helium314.keyboard.latin.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.utils.LayoutType
import helium314.keyboard.latin.utils.POPUP_KEYS_LABEL_DEFAULT
import helium314.keyboard.latin.utils.POPUP_KEYS_ORDER_DEFAULT
import helium314.keyboard.latin.utils.defaultClipboardToolbarPref
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.defaultToolbarPref

object Defaults {
    fun initDynamicDefaults(context: Context) {
        PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM = getTransitionAnimationScale(context) != 0.0f
        val dm = context.resources.displayMetrics
        val px600 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 600f, dm)
        PREF_POPUP_ON = dm.widthPixels >= px600 || dm.heightPixels >= px600
    }

    // must correspond to a file name
    val LayoutType.default get() = when (this) {
        LayoutType.MAIN -> "qwerty"
        LayoutType.SYMBOLS -> "symbols"
        LayoutType.MORE_SYMBOLS -> "symbols_shifted"
        LayoutType.FUNCTIONAL -> if (Settings.getInstance().isTablet) "functional_keys_tablet" else "functional_keys"
        LayoutType.NUMBER -> "number"
        LayoutType.NUMBER_ROW -> "number_row"
        LayoutType.NUMPAD -> "numpad"
        LayoutType.NUMPAD_LANDSCAPE -> "numpad_landscape"
        LayoutType.PHONE -> "phone"
        LayoutType.PHONE_SYMBOLS -> "phone_symbols"
        LayoutType.EMOJI_BOTTOM -> "emoji_bottom_row"
        LayoutType.CLIPBOARD_BOTTOM -> "clip_bottom_row"
    }

    private const val DEFAULT_SIZE_SCALE = 1.0f // 100%
    const val PREF_THEME_STYLE = KeyboardTheme.STYLE_ROUNDED
    const val PREF_ICON_STYLE = KeyboardTheme.STYLE_MATERIAL
    const val PREF_THEME_COLORS = KeyboardTheme.THEME_LIGHT
    const val PREF_THEME_COLORS_NIGHT = KeyboardTheme.THEME_DARK
    const val PREF_THEME_KEY_BORDERS = true
    @JvmField
    val PREF_THEME_DAY_NIGHT = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    const val PREF_CUSTOM_ICON_NAMES = ""
    const val PREF_TOOLBAR_CUSTOM_KEY_CODES = ""
    const val PREF_AUTO_CAP = true
    const val PREF_VIBRATE_ON = false
    const val PREF_VIBRATE_IN_DND_MODE = false
    const val PREF_SOUND_ON = false
    const val PREF_SUGGEST_EMOJIS = true
    const val PREF_INLINE_EMOJI_SEARCH = true
    const val PREF_SHOW_EMOJI_DESCRIPTIONS = true
    @JvmField
    var PREF_POPUP_ON = true
    const val PREF_AUTO_CORRECTION = true
    const val PREF_MORE_AUTO_CORRECTION = false
    const val PREF_AUTO_CORRECT_THRESHOLD = 0.14f
    const val PREF_AUTOCORRECT_SHORTCUTS = true
    const val PREF_BACKSPACE_REVERTS_AUTOCORRECT = true
    const val PREF_CENTER_SUGGESTION_TEXT_TO_ENTER = false
    const val PREF_SHOW_SUGGESTIONS = true
    const val PREF_ALWAYS_SHOW_SUGGESTIONS = false
    const val PREF_ALWAYS_SHOW_SUGGESTIONS_EXCEPT_WEB_TEXT = true
    const val PREF_KEY_USE_PERSONALIZED_DICTS = true
    const val PREF_KEY_USE_DOUBLE_SPACE_PERIOD = true
    const val PREF_BLOCK_POTENTIALLY_OFFENSIVE = true
    const val PREF_SHOW_LANGUAGE_SWITCH_KEY = false
    const val PREF_LANGUAGE_SWITCH_KEY = "internal"
    const val PREF_SHOW_EMOJI_KEY = false
    const val PREF_VARIABLE_TOOLBAR_DIRECTION = true
    const val PREF_ADDITIONAL_SUBTYPES = "de${Separators.SET}${ExtraValue.KEYBOARD_LAYOUT_SET}=MAIN:qwerty${Separators.SETS}" +
            "fr${Separators.SET}${ExtraValue.KEYBOARD_LAYOUT_SET}=MAIN:qwertz${Separators.SETS}" +
            "hu${Separators.SET}${ExtraValue.KEYBOARD_LAYOUT_SET}=MAIN:qwerty"
    const val PREF_ENABLE_SPLIT_KEYBOARD = false
    const val PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE = false
    @JvmField
    val PREF_SPLIT_SPACER_SCALE = Array(2) { DEFAULT_SIZE_SCALE }
    @JvmField
    val PREF_KEYBOARD_HEIGHT_SCALE = Array(2) { DEFAULT_SIZE_SCALE }
    @JvmField
    val PREF_BOTTOM_ROW_SCALE = Array(2) { DEFAULT_SIZE_SCALE }
    @JvmField
    val PREF_BOTTOM_PADDING_SCALE = arrayOf(DEFAULT_SIZE_SCALE, 0f)
    @JvmField
    val PREF_SIDE_PADDING_SCALE = Array(4) { 0f }
    const val PREF_FONT_SCALE = DEFAULT_SIZE_SCALE
    const val PREF_EMOJI_FONT_SCALE = DEFAULT_SIZE_SCALE
    const val PREF_EMOJI_KEY_FIT = true
    const val PREF_EMOJI_SKIN_TONE = ""
    const val PREF_SPACE_HORIZONTAL_SWIPE = "move_cursor"
    const val PREF_SPACE_VERTICAL_SWIPE = "none"
    const val PREF_DELETE_SWIPE = true
    const val PREF_AUTOSPACE_AFTER_PUNCTUATION = false
    const val PREF_AUTOSPACE_AFTER_SUGGESTION = true
    const val PREF_AUTOSPACE_AFTER_GESTURE_TYPING = true
    const val PREF_AUTOSPACE_BEFORE_GESTURE_TYPING = true
    const val PREF_SHIFT_REMOVES_AUTOSPACE = false
    const val PREF_ALWAYS_INCOGNITO_MODE = false
    const val PREF_BIGRAM_PREDICTIONS = true
    const val PREF_SUGGEST_PUNCTUATION = false
    const val PREF_SUGGEST_CLIPBOARD_CONTENT = false
    const val PREF_GESTURE_INPUT = true
    const val PREF_VIBRATION_DURATION_SETTINGS = -1
    const val PREF_VIBRATION_INTENSITY = -1
    const val PREF_KEY_PRESS_RIPPLE = true
    const val PREF_KEYPRESS_SOUND_VOLUME = -0.01f
    const val PREF_KEY_LONGPRESS_TIMEOUT = 300
    const val PREF_ENABLE_EMOJI_ALT_PHYSICAL_KEY = true
    const val PREF_GESTURE_PREVIEW_TRAIL = true
    const val PREF_GESTURE_FLOATING_PREVIEW_TEXT = true
    const val PREF_GESTURE_FLOATING_PREVIEW_DYNAMIC = true
    @JvmField
    var PREF_GESTURE_DYNAMIC_PREVIEW_FOLLOW_SYSTEM = true
    const val PREF_GESTURE_SPACE_AWARE = false
    const val PREF_GESTURE_FAST_TYPING_COOLDOWN = 500
    const val PREF_GESTURE_TRAIL_FADEOUT_DURATION = 800
    const val PREF_SHOW_SETUP_WIZARD_ICON = true
    const val PREF_USE_CONTACTS = false
    const val PREF_USE_APPS = false
    const val PREFS_LONG_PRESS_SYMBOLS_FOR_NUMPAD = false
    const val PREF_ONE_HANDED_MODE = false
    @SuppressLint("RtlHardcoded")
    const val PREF_ONE_HANDED_GRAVITY = Gravity.LEFT
    const val PREF_ONE_HANDED_SCALE = 1f
    const val PREF_SHOW_NUMBER_ROW = false
    const val PREF_SHOW_NUMBER_ROW_IN_SYMBOLS = true
    const val PREF_LOCALIZED_NUMBER_ROW = true
    const val PREF_SHOW_NUMBER_ROW_HINTS = false
    const val PREF_CUSTOM_CURRENCY_KEY = ""
    const val PREF_SHOW_HINTS = true
    const val PREF_POPUP_KEYS_ORDER = POPUP_KEYS_ORDER_DEFAULT
    const val PREF_POPUP_KEYS_LABELS_ORDER = POPUP_KEYS_LABEL_DEFAULT
    const val PREF_SHOW_POPUP_HINTS = false
    const val PREF_SHOW_TLD_POPUP_KEYS = true
    const val PREF_MORE_POPUP_KEYS = "main"
    const val PREF_SPACE_TO_CHANGE_LANG = true
    const val PREF_LANGUAGE_SWIPE_DISTANCE = 5
    const val PREF_ENABLE_CLIPBOARD_HISTORY = false
    const val PREF_CLIPBOARD_HISTORY_RETENTION_TIME = 10 // minutes
    const val PREF_CLIPBOARD_HISTORY_PINNED_FIRST = true
    const val PREF_ADD_TO_PERSONAL_DICTIONARY = true
    @JvmField
    val PREF_NAVBAR_COLOR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    const val PREF_NARROW_KEY_GAPS = false
    const val PREF_ENABLED_SUBTYPES = ""
    const val PREF_SELECTED_SUBTYPE = ""
    const val PREF_URL_DETECTION = false
    const val PREF_DONT_SHOW_MISSING_DICTIONARY_DIALOG = false
    const val PREF_TOOLBAR_MODE = "EXPANDABLE"
    const val PREF_TOOLBAR_HIDING_GLOBAL = true
    const val PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE = false
    const val PREF_QUICK_PIN_TOOLBAR_KEYS = false
    val PREF_PINNED_TOOLBAR_KEYS = defaultPinnedToolbarPref
    val PREF_TOOLBAR_KEYS = defaultToolbarPref
    const val PREF_AUTO_SHOW_TOOLBAR = false
    const val PREF_AUTO_HIDE_TOOLBAR = false
    val PREF_CLIPBOARD_TOOLBAR_KEYS = defaultClipboardToolbarPref
    const val PREF_ABC_AFTER_EMOJI = false
    const val PREF_ABC_AFTER_CLIP = false
    const val PREF_ABC_AFTER_SYMBOL_SPACE = true
    const val PREF_ABC_AFTER_NUMPAD_SPACE = false
    const val PREF_REMOVE_REDUNDANT_POPUPS = false
    const val PREF_SPACE_BAR_TEXT = ""
    const val PREF_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
    const val PREF_EMOJI_RECENT_KEYS = ""
    const val PREF_LAST_SHOWN_EMOJI_CATEGORY_PAGE_ID = 0
    const val PREF_SHOW_DEBUG_SETTINGS = false
    val PREF_DEBUG_MODE = BuildConfig.DEBUG
    const val PREF_SHOW_SUGGESTION_INFOS = false
    const val PREF_FORCE_NON_DISTINCT_MULTITOUCH = false
    const val PREF_SLIDING_KEY_INPUT_PREVIEW = true
    const val PREF_USER_COLORS = "[]"
    const val PREF_USER_MORE_COLORS = 0
    const val PREF_USER_ALL_COLORS = ""
    const val PREF_SAVE_SUBTYPE_PER_APP = false
    const val PREF_VOICE_INPUT_ENABLED = false
    const val PREF_VOICE_SPEECH_ENGINE = "cloud"
    const val PREF_AI_PROVIDER = "openrouter"
    const val PREF_OPENROUTER_API_KEY = ""
    const val PREF_OPENROUTER_ZDR_ENABLED = true
    const val PREF_PAYPERQ_API_KEY = ""
    // Must be a slug the voice picker actually offers for both providers — the previous
    // default ("google/gemini-3-flash-preview") is a text-fix model that the OpenRouter and
    // PayPerQ voice pickers don't expose, so it would silently leave the picker without a
    // valid selection on fresh installs.
    const val PREF_VOICE_MODEL = "~google/gemini-flash-latest"
    const val PREF_VOICE_MODEL_CUSTOM = ""
    const val PREF_VOICE_TRADITIONAL_BUTTON_ENABLED = true
    const val PREF_VOICE_STT_ENABLED = false
    const val PREF_VOICE_STT_MODEL = "google/chirp-3"
    const val PREF_VOICE_STT_MODEL_CUSTOM = ""
    // Default transcription prompts: stronger, model-independent instructions for high-quality
    // verbatim output with natural punctuation and casing. Drives quality uniformly across STT
    // backends and chat-audio models.
    const val PREF_VOICE_STT_PROMPT = "Transcribe the audio with the highest possible fidelity. Preserve every spoken word verbatim, including proper names, numbers, code, and technical terms. Apply natural punctuation and sentence casing (periods, commas, question marks, capitalization). Do not translate, summarize, censor, or add commentary. If unsure of a word, write your best phonetic guess. Output only the transcription, with no preamble, quotes, or explanation."
    const val PREF_VOICE_STT_DICTIONARY = ""
    const val PREF_VOICE_STT_EXPECTED_LANGUAGES = ""
    const val PREF_VOICE_TRANSCRIPTION_PROMPT = "Transcribe the audio with the highest possible fidelity. Preserve every spoken word verbatim, including proper names, numbers, code, and technical terms. Apply natural punctuation and sentence casing (periods, commas, question marks, capitalization). Do not translate, summarize, censor, or add commentary. If unsure of a word, write your best phonetic guess. Output only the transcription, with no preamble, quotes, or explanation."
    const val PREF_VOICE_TRANSCRIPTION_DICTIONARY = ""
    const val PREF_VOICE_EXPECTED_LANGUAGES = ""
    const val PREF_VOICE_MAX_DURATION_SECONDS = 90
    const val PREF_VOICE_AUTO_STOP_SILENCE = false
    const val PREF_VOICE_AUTO_STOP_SILENCE_SECONDS = 2
    const val PREF_VOICE_MIC_SENSITIVITY = "normal"
    const val PREF_VOICE_LANGUAGE_HINT = true
    const val PREF_VOICE_SPACE_HEURISTIC = true
    const val PREF_VOICE_HAPTIC_FEEDBACK = true
    const val PREF_VOICE_AUTO_POLISH_ENABLED = false
    // Polish level values: "natural" (bypass), "light", "fixed", "rephrased", "corrected", "polished".
    // Kept as a plain string so it persists like the other enum-style prefs in this file.
    const val PREF_VOICE_POLISH_LEVEL = "fixed"
    const val PREF_VOICE_POLISH_MODEL = "~openai/gpt-mini-latest"
    const val PREF_VOICE_POLISH_MODEL_CUSTOM = ""
    // System prompts per polish level. The "natural" level skips the polish pass entirely so it
    // has no prompt. Each level is graded from no-rewrite to aggressive rewrite; all of them
    // strictly preserve meaning, facts, and the speaker's language.
    const val PREF_VOICE_POLISH_PROMPT_LIGHT = "You receive a voice-to-text transcript. Fix only obvious transcription errors: capitalization, punctuation, and clear spelling mistakes. Do not rewrite, restructure, reorder, or change wording. Preserve every word, pause, and the speaker's voice exactly. Preserve the original language. Output only the corrected text, with no preamble, quotes, or explanation."
    const val PREF_VOICE_POLISH_PROMPT_FIXED = "You receive a voice-to-text transcript. Fix grammar, punctuation, spelling, and capitalization. Remove disfluencies (um, uh, like, you know) and false starts. Keep the speaker's wording, tone, and intent — do not rephrase or reorder. Preserve the original language. Output only the corrected text, with no preamble, quotes, or explanation."
    const val PREF_VOICE_POLISH_PROMPT_REPHRASED = "You receive a voice-to-text transcript. Rewrite it into clean, natural prose. Remove disfluencies, false starts, and stray repetitions. Restructure run-on sentences and clarify confusing phrasing while strictly preserving meaning, facts, and the speaker's intent. Keep the same language and a conversational tone. Output only the rewritten text, with no preamble, quotes, or explanation."
    const val PREF_VOICE_POLISH_PROMPT_CORRECTED = "You receive a voice-to-text transcript that likely contains transcription mistakes, misheard words, and misspeaks. Reconstruct what the speaker most likely meant: fix misheard words using context, honor any self-corrections (\"I mean…\"), drop disfluencies and false starts, and produce a grammatical, well-punctuated final text. Strictly preserve meaning, facts, and language. Output only the corrected text, with no preamble, quotes, or explanation."
    const val PREF_VOICE_POLISH_PROMPT_POLISHED = "You receive a voice-to-text transcript. Produce a polished, concise written version: drop filler, tighten redundancy, restructure for clarity, and improve flow while strictly preserving the speaker's meaning, facts, and language. Aim for natural written prose, not a verbatim transcript. Do not invent details. Output only the polished text, with no preamble, quotes, or explanation."
    const val PREF_TEXT_FIX_ENABLED = false
    const val PREF_TEXT_FIX_MODEL = "~openai/gpt-mini-latest"
    const val PREF_TEXT_FIX_MODEL_CUSTOM = ""
    const val PREF_TEXT_FIX_PROMPT = "You are a writing assistant. Improve the following text: fix typos, grammar, and punctuation while preserving the original meaning, tone, and language. Output only the improved text, with no preamble, quotes, or explanation."
    const val PREF_TEXT_FIX_2_ENABLED = false
    const val PREF_TEXT_FIX_2_PROMPT = "You are a writing assistant. Rewrite the following text to be clearer and more concise while preserving the original meaning, tone, and language. Output only the rewritten text, with no preamble, quotes, or explanation."
    const val PREF_POPUP_DRAG_HAPTIC = false
    const val PREF_ACTION_POPUP_ORDER = "clipboard_action_key:true|emoji_action_key:true|shortcut_key:true|stt_action_key:true|text_fix_key:true|text_fix_2_key:true"
}
