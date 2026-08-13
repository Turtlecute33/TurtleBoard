// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.clipboard

import helium314.keyboard.latin.ClipboardHistoryEntry
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ClipboardPanelStateTest {

    private fun entry(id: Long, text: String, pinned: Boolean = false) =
        ClipboardHistoryEntry(id, id, pinned, text)

    @Test fun `detects links, emails and numbers`() {
        assertEquals(ClipKind.LINK, ClipItem.kindOf("https://example.com/a/b?c=d"))
        assertEquals(ClipKind.LINK, ClipItem.kindOf("www.example.com"))
        assertEquals(ClipKind.EMAIL, ClipItem.kindOf("someone@example.com"))
        assertEquals(ClipKind.NUMBER, ClipItem.kindOf("123456"))
        assertEquals(ClipKind.NUMBER, ClipItem.kindOf("+39 02 1234 5678"))
        assertEquals(ClipKind.TEXT, ClipItem.kindOf("meeting at 10 with the team"))
        assertEquals(ClipKind.TEXT, ClipItem.kindOf(""))
    }

    @Test fun `pinned clips stay out of the list, but the pinned filter shows them`() {
        val state = ClipboardPanelState()
        state.setClips(listOf(entry(1, "pinned one", true), entry(2, "plain one")))

        assertEquals(listOf(2L), state.listed.map { it.id })
        assertEquals(listOf(1L), state.pinned.map { it.id })

        state.filter = ClipFilter.PINNED
        assertEquals(listOf(1L), state.listed.map { it.id })
    }

    @Test fun `filter keeps only clips of that kind`() {
        val state = ClipboardPanelState()
        state.setClips(listOf(entry(1, "https://example.com"), entry(2, "hello there"), entry(3, "42")))

        state.filter = ClipFilter.LINK
        assertEquals(listOf(1L), state.listed.map { it.id })
        state.filter = ClipFilter.NUMBER
        assertEquals(listOf(3L), state.listed.map { it.id })
        state.filter = ClipFilter.TEXT
        assertEquals(listOf(2L), state.listed.map { it.id })
    }

    @Test fun `search ignores case and ranks early matches first`() {
        val state = ClipboardPanelState()
        state.setClips(listOf(entry(1, "a long text with keyword inside"), entry(2, "KEYWORD first"), entry(3, "nothing")))
        state.buffer.set("keyword")

        assertEquals(listOf(2L, 1L), state.searchResults().map { it.id })
    }

    @Test fun `expanded and open menu are dropped when their clip is gone`() {
        val state = ClipboardPanelState()
        state.setClips(listOf(entry(1, "one"), entry(2, "two")))
        state.expanded.add(1)
        state.menuFor = 1

        state.setClips(listOf(entry(2, "two")))

        assertEquals(emptyList(), state.expanded.toList())
        assertEquals(null, state.menuFor)
    }

    @Test fun `text buffer inserts and deletes at the cursor`() {
        val buffer = KeyboardTextBuffer()
        buffer.set("hello")
        assertEquals(5, buffer.cursor)

        buffer.insert("!")
        assertEquals("hello!", buffer.text)

        buffer.moveCursor(0)
        buffer.insert("say ")
        assertEquals("say hello!", buffer.text)
        assertEquals(4, buffer.cursor)

        buffer.backspace()
        assertEquals("sayhello!", buffer.text)
        assertEquals(3, buffer.cursor)
    }

    @Test fun `text buffer deletes a full code point`() {
        val buffer = KeyboardTextBuffer()
        buffer.set("ok 👍")
        buffer.backspace()
        assertEquals("ok ", buffer.text)
    }

    @Test fun `text buffer clamps the cursor`() {
        val buffer = KeyboardTextBuffer()
        buffer.set("abc")
        buffer.moveCursorBy(10)
        assertEquals(3, buffer.cursor)
        buffer.moveCursorBy(-10)
        assertEquals(0, buffer.cursor)
        buffer.backspace()
        assertEquals("abc", buffer.text)
    }
}
