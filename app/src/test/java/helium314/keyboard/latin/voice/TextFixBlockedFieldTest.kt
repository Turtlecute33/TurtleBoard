// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextFixBlockedFieldTest {
    private fun call(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        isPasswordField: Boolean = false,
        noLearning: Boolean = false,
        incognitoModeEnabled: Boolean = false,
        imeOptions: Int = 0,
    ): Int? = TextFixManager.getBlockedErrorResId(
        inputType = inputType,
        isPasswordField = isPasswordField,
        noLearning = noLearning,
        incognitoModeEnabled = incognitoModeEnabled,
        imeOptions = imeOptions,
    )

    @Test
    fun normalTextClassIsAllowed() {
        assertNull(call(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL))
    }

    @Test
    fun textPasswordVariationIsSensitive() {
        assertEquals(
            R.string.text_fix_error_sensitive_field,
            call(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                isPasswordField = true),
        )
    }

    @Test
    fun isPasswordFieldFlagAloneIsSensitive() {
        assertEquals(R.string.text_fix_error_sensitive_field, call(isPasswordField = true))
    }

    @Test
    fun noLearningFlagIsSensitive() {
        assertEquals(R.string.text_fix_error_sensitive_field, call(noLearning = true))
    }

    @Test
    fun incognitoModeFlagIsSensitive() {
        assertEquals(R.string.text_fix_error_sensitive_field, call(incognitoModeEnabled = true))
    }

    @Test
    fun textNoSuggestionsFlagAloneIsAllowed() {
        // Chat composers and search boxes set this flag purely to hide the suggestion strip; it is
        // not a privacy signal, so it must not block Text Fix by itself.
        assertNull(call(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }

    @Test
    fun noSuggestionsStillBlockedWhenARealPrivacySignalIsPresent() {
        assertEquals(
            R.string.text_fix_error_sensitive_field,
            call(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                noLearning = true,
            ),
        )
    }

    @Test
    fun imeFlagNoPersonalizedLearningIsSensitive() {
        assertEquals(
            R.string.text_fix_error_sensitive_field,
            call(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        )
    }

    @Test
    fun numberPasswordVariationIsSensitive() {
        assertEquals(
            R.string.text_fix_error_sensitive_field,
            call(inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        )
    }

    @Test
    fun datetimeClassIsUnsupported() {
        assertEquals(
            R.string.text_fix_error_unsupported_field,
            call(inputType = InputType.TYPE_CLASS_DATETIME),
        )
    }

    @Test
    fun phoneClassIsUnsupported() {
        assertEquals(
            R.string.text_fix_error_unsupported_field,
            call(inputType = InputType.TYPE_CLASS_PHONE),
        )
    }

    @Test
    fun textUriVariationIsUnsupported() {
        assertEquals(
            R.string.text_fix_error_unsupported_field,
            call(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
        )
    }

    @Test
    fun textEmailAddressVariationIsUnsupported() {
        assertEquals(
            R.string.text_fix_error_unsupported_field,
            call(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        )
    }
}
