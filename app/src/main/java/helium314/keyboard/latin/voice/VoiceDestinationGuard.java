// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice;

/** Pure destination check shared by the IME callback and local regression tests. */
public final class VoiceDestinationGuard {
    private VoiceDestinationGuard() {}

    public static boolean isUnchanged(
            final String targetEditor,
            final String currentEditor,
            final long targetSession,
            final long currentSession,
            final int targetSelectionStart,
            final int targetSelectionEnd,
            final int currentSelectionStart,
            final int currentSelectionEnd,
            final boolean selectionChanged) {
        return targetEditor != null
                && targetEditor.equals(currentEditor)
                && targetSession == currentSession
                && !selectionChanged
                && targetSelectionStart == currentSelectionStart
                && targetSelectionEnd == currentSelectionEnd;
    }
}
