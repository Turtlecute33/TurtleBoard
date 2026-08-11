// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceDestinationGuardTest {
    @Test
    fun unchangedDestinationIsAccepted() {
        assertTrue(VoiceDestinationGuard.isUnchanged("app/-1/1", "app/-1/1", 4, 4, 8, 8, 8, 8, false))
    }

    @Test
    fun reusedFieldIdentityInANewInputSessionIsRejected() {
        assertFalse(VoiceDestinationGuard.isUnchanged("app/-1/1", "app/-1/1", 4, 5, 8, 8, 8, 8, false))
    }

    @Test
    fun selectionMovementIsRejectedEvenAfterCursorReturns() {
        assertFalse(VoiceDestinationGuard.isUnchanged("app/7/1", "app/7/1", 4, 4, 8, 8, 8, 8, true))
        assertFalse(VoiceDestinationGuard.isUnchanged("app/7/1", "app/7/1", 4, 4, 8, 8, 10, 12, false))
    }
}
