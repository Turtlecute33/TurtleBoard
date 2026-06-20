package helium314.keyboard.keyboard.internal

import android.text.TextUtils
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.RecapitalizeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class KeyboardStateTest {
    private val actions = FakeSwitchActions()
    private val state = KeyboardState(actions)

    @Test
    fun shiftTapAppliesToOneLetterOnly() {
        load()

        tap(KeyCode.SHIFT)
        assertEquals(Layout.MANUAL_SHIFTED, actions.layout)

        typeLetter('A', Constants.TextUtils.CAP_MODE_OFF)

        assertEquals(Layout.ALPHABET, actions.layout)
        assertFalse(actions.everShiftLocked)
    }

    @Test
    fun shiftTapWhileAutoShiftedWaitsForNextLetter() {
        load()
        state.onUpdateShiftState(TextUtils.CAP_MODE_SENTENCES, null)
        assertEquals(Layout.AUTOMATIC_SHIFTED, actions.layout)

        tap(KeyCode.SHIFT, TextUtils.CAP_MODE_SENTENCES)

        assertEquals(Layout.MANUAL_SHIFTED, actions.layout)
        assertFalse(actions.everShiftLocked)
    }

    @Test
    fun shiftUpdateAfterShiftTapDoesNotRestoreAutomaticShift() {
        load()
        state.onUpdateShiftState(TextUtils.CAP_MODE_SENTENCES, null)
        assertEquals(Layout.AUTOMATIC_SHIFTED, actions.layout)

        tap(KeyCode.SHIFT, TextUtils.CAP_MODE_SENTENCES)
        state.onUpdateShiftState(TextUtils.CAP_MODE_SENTENCES, null)
        assertEquals(Layout.MANUAL_SHIFTED, actions.layout)

        typeLetter('A', TextUtils.CAP_MODE_SENTENCES)

        assertEquals(Layout.ALPHABET, actions.layout)
        assertFalse(actions.everShiftLocked)
    }

    @Test
    fun automaticSentenceCapsStillUsesOneLetterOnly() {
        load()
        state.onUpdateShiftState(TextUtils.CAP_MODE_SENTENCES, null)
        assertEquals(Layout.AUTOMATIC_SHIFTED, actions.layout)

        typeLetter('A', Constants.TextUtils.CAP_MODE_OFF)

        assertEquals(Layout.ALPHABET, actions.layout)
        assertFalse(actions.everShiftLocked)
    }

    @Test
    fun capsLockRemainsLockedAfterTypingLetter() {
        load()

        tap(KeyCode.CAPS_LOCK)
        assertEquals(Layout.SHIFT_LOCKED, actions.layout)

        typeLetter('A', Constants.TextUtils.CAP_MODE_OFF)

        assertEquals(Layout.SHIFT_LOCKED, actions.layout)
    }

    private fun load() {
        state.onLoadKeyboard(Constants.TextUtils.CAP_MODE_OFF, null, false)
        actions.resetHistory()
    }

    private fun tap(code: Int) {
        tap(code, Constants.TextUtils.CAP_MODE_OFF)
    }

    private fun tap(code: Int, autoCapsFlags: Int) {
        state.onPressKey(code, true, autoCapsFlags, null)
        state.onEvent(Event.createSoftwareKeypressEvent(code, 0, 0, 0, false), autoCapsFlags, null)
        state.onReleaseKey(code, false, autoCapsFlags, null)
    }

    private fun typeLetter(letter: Char, autoCapsFlags: Int) {
        state.onPressKey(letter.code, true, autoCapsFlags, null)
        state.onEvent(Event.createEventForCodePointFromUnknownSource(letter.code), autoCapsFlags, null)
        state.onReleaseKey(letter.code, false, autoCapsFlags, null)
    }

    private class FakeSwitchActions : KeyboardState.SwitchActions {
        var layout = Layout.ALPHABET
            private set
        var everShiftLocked = false
            private set

        fun resetHistory() {
            everShiftLocked = false
        }

        override fun setAlphabetKeyboard() {
            layout = Layout.ALPHABET
        }

        override fun setAlphabetManualShiftedKeyboard() {
            layout = Layout.MANUAL_SHIFTED
        }

        override fun setAlphabetAutomaticShiftedKeyboard() {
            layout = Layout.AUTOMATIC_SHIFTED
        }

        override fun setAlphabetShiftLockedKeyboard() {
            layout = Layout.SHIFT_LOCKED
            everShiftLocked = true
        }

        override fun setAlphabetShiftLockShiftedKeyboard() {
            layout = Layout.SHIFT_LOCK_SHIFTED
            everShiftLocked = true
        }

        override fun setEmojiKeyboard() {}
        override fun setClipboardKeyboard() {}
        override fun setNumpadKeyboard() {}
        override fun toggleNumpad(withSliding: Boolean, autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?, forceReturnToAlpha: Boolean) {}
        override fun setSymbolsKeyboard() {}
        override fun setSymbolsShiftedKeyboard() {}
        override fun requestUpdatingShiftState(autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?) {}
        override fun startDoubleTapShiftKeyTimer() {}
        override val isInDoubleTapShiftKeyTimeout = false
        override fun cancelDoubleTapShiftKeyTimer() {}
        override fun setOneHandedModeEnabled(enabled: Boolean) {}
        override fun switchOneHandedMode() {}
    }

    private enum class Layout {
        ALPHABET,
        MANUAL_SHIFTED,
        AUTOMATIC_SHIFTED,
        SHIFT_LOCKED,
        SHIFT_LOCK_SHIFTED,
    }
}
