// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.util.Patterns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.R

/** What kind of content a clip holds; used for the filter chips and the icon on each card */
enum class ClipKind(val filter: ClipFilter?) {
    LINK(ClipFilter.LINK),
    EMAIL(ClipFilter.EMAIL),
    NUMBER(ClipFilter.NUMBER),
    TEXT(ClipFilter.TEXT);
}

enum class ClipFilter(val labelRes: Int) {
    ALL(R.string.clipboard_filter_all),
    PINNED(R.string.clipboard_filter_pinned),
    LINK(R.string.clipboard_filter_links),
    EMAIL(R.string.clipboard_filter_emails),
    NUMBER(R.string.clipboard_filter_numbers),
    TEXT(R.string.clipboard_filter_text)
}

/** A history entry prepared for display */
class ClipItem(entry: ClipboardHistoryEntry) {
    val id = entry.id
    val text = entry.text
    val isPinned = entry.isPinned
    val timeStamp = entry.timeStamp
    /** shortened copy used for display, so we never lay out huge texts */
    val preview: String = text.trim().let { if (it.length > PREVIEW_LENGTH) it.take(PREVIEW_LENGTH) + "…" else it }
    val kind = kindOf(text)
    val isTruncated = text.trim().length > PREVIEW_LENGTH

    companion object {
        private const val PREVIEW_LENGTH = 600

        private val numberRegex = Regex("""[+\-]?[\d\s()./,-]{2,}""")

        fun kindOf(text: String): ClipKind {
            val t = text.trim().let { if (it.length > 200) it.take(200) else it }
            if (t.isEmpty()) return ClipKind.TEXT
            if (!t.contains(' ') && !t.contains('\n')) {
                if (Patterns.EMAIL_ADDRESS.matcher(t).matches()) return ClipKind.EMAIL
                if (Patterns.WEB_URL.matcher(t).matches()) return ClipKind.LINK
            }
            if (numberRegex.matches(t) && t.any { it.isDigit() }) return ClipKind.NUMBER
            return ClipKind.TEXT
        }
    }
}

/** What the alphabet keyboard below the panel is typing into, if anything */
sealed interface TypingMode {
    data object Search : TypingMode
    data class Edit(val id: Long) : TypingMode
}

/** Text buffer edited with the keyboard, since a real text field cannot receive input inside the IME */
class KeyboardTextBuffer {
    var text by mutableStateOf("")
        private set
    var cursor by mutableIntStateOf(0)
        private set

    fun set(newText: String, newCursor: Int = newText.length) {
        text = newText
        cursor = newCursor.coerceIn(0, newText.length)
    }

    fun insert(insertion: String) {
        val at = cursor.coerceIn(0, text.length)
        text = text.substring(0, at) + insertion + text.substring(at)
        cursor = at + insertion.length
    }

    fun backspace() {
        val at = cursor.coerceIn(0, text.length)
        if (at == 0) return
        // delete a whole code point, so surrogate pairs and emojis do not break
        val start = text.offsetByCodePoints(at, -1)
        text = text.substring(0, start) + text.substring(at)
        cursor = start
    }

    fun moveCursor(to: Int) {
        cursor = to.coerceIn(0, text.length)
    }

    fun moveCursorBy(delta: Int) = moveCursor(cursor + delta)

    fun clear() = set("")
}

/** All state the panel renders from. Owned by [ClipboardHistoryView], read by the composables. */
class ClipboardPanelState {
    val clips: SnapshotStateList<ClipItem> = mutableStateListOf()
    /** pin icon of the current keyboard theme */
    var pinIconRes = R.drawable.ic_clipboard_pin_lxx
    var filter by mutableStateOf(ClipFilter.ALL)
    var typingMode by mutableStateOf<TypingMode?>(null)
    val buffer = KeyboardTextBuffer()
    /** id of the clip whose action sheet is open */
    var menuFor by mutableStateOf<Long?>(null)
    /** ids of clips shown with their full text */
    val expanded = mutableStateListOf<Long>()

    fun setClips(entries: List<ClipboardHistoryEntry>) {
        val items = entries.map { ClipItem(it) }
        clips.clear()
        clips.addAll(items)
        val ids = items.mapTo(HashSet()) { it.id }
        expanded.retainAll { it in ids }
        menuFor = menuFor?.takeIf { it in ids }
    }

    fun clip(id: Long?) = clips.firstOrNull { it.id == id }

    val pinned get() = clips.filter { it.isPinned }

    /** clips shown in the main list, after filter (pinned ones live in their own row unless filtered for) */
    val listed
        get() = when (filter) {
            ClipFilter.ALL -> clips.filter { !it.isPinned }
            ClipFilter.PINNED -> clips.filter { it.isPinned }
            else -> clips.filter { it.kind.filter == filter }
        }

    /** search results, ranked so that matches at the start of a clip come first */
    fun searchResults(): List<ClipItem> {
        val query = buffer.text.trim()
        if (query.isEmpty()) return clips
        return clips.filter { it.text.contains(query, ignoreCase = true) }
            .sortedBy { it.text.indexOf(query, ignoreCase = true) }
    }
}

/** Everything the panel can ask the keyboard to do */
interface ClipboardPanelActions {
    fun onPaste(item: ClipItem)
    fun onTogglePin(id: Long)
    fun onDelete(id: Long)
    fun onCopy(item: ClipItem)
    fun onShare(item: ClipItem)
    fun onStartEdit(item: ClipItem)
    fun onStartSearch()
    fun onFinishTyping(commit: Boolean)
    fun onClearHistory()
    fun onCloseHistory()
}
