// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceInputBlockedFieldTest {
    private fun call(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        isPasswordField: Boolean = false,
        noLearning: Boolean = false,
        incognitoModeEnabled: Boolean = false,
        imeOptions: Int = 0,
    ): Int? = VoiceInputManager.getBlockedErrorResId(
        inputType = inputType,
        isPasswordField = isPasswordField,
        noLearning = noLearning,
        incognitoModeEnabled = incognitoModeEnabled,
        imeOptions = imeOptions,
    )

    @Test
    fun normalTextFieldIsAllowed() {
        assertNull(call(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL))
    }

    @Test
    fun passwordFieldIsSensitive() {
        assertEquals(R.string.voice_error_sensitive_field, call(isPasswordField = true))
    }

    @Test
    fun noLearningAndIncognitoFieldsAreSensitive() {
        assertEquals(R.string.voice_error_sensitive_field, call(noLearning = true))
        assertEquals(R.string.voice_error_sensitive_field, call(incognitoModeEnabled = true))
    }

    @Test
    fun editorPrivacyFlagsAreSensitive() {
        assertEquals(
            R.string.voice_error_sensitive_field,
            call(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        )
    }

    /**
     * Apps set NO_SUGGESTIONS on ordinary chat and search fields just to hide the suggestion strip.
     * It carries no privacy meaning, so it must not block voice input on its own.
     */
    @Test
    fun textNoSuggestionsFlagAloneIsAllowed() {
        assertNull(call(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }

    @Test
    fun noSuggestionsStillBlockedWhenARealPrivacySignalIsPresent() {
        assertEquals(
            R.string.voice_error_sensitive_field,
            call(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                incognitoModeEnabled = true,
            ),
        )
    }

    @Test
    fun numericPhoneAndEmailFieldsAreUnsupported() {
        assertEquals(R.string.voice_error_unsupported_field, call(InputType.TYPE_CLASS_NUMBER))
        assertEquals(R.string.voice_error_unsupported_field, call(InputType.TYPE_CLASS_PHONE))
        assertEquals(
            R.string.voice_error_unsupported_field,
            call(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        )
    }
}
