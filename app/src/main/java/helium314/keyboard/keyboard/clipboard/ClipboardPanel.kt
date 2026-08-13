// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import helium314.keyboard.latin.R
import kotlinx.coroutines.delay

private val ICON_LINK = R.drawable.ic_link
private val ICON_MAIL = R.drawable.ic_mail
private val ICON_NUMBERS = R.drawable.ic_numbers
private val ICON_TEXT = R.drawable.ic_text_lines

@Composable
fun ClipboardPanel(state: ClipboardPanelState, actions: ClipboardPanelActions, modifier: Modifier = Modifier) {
    ClipboardPanelTheme {
        // The panel draws on the keyboard background instead of a Surface, so nothing sets
        // LocalContentColor and Material falls back to black: untinted icons were invisible on a
        // dark theme. onSurface is the key text color of the active keyboard theme.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(modifier.fillMaxSize()) {
                when (val mode = state.typingMode) {
                    is TypingMode.Search -> SearchLayer(state, actions)
                    is TypingMode.Edit -> EditLayer(state, actions, mode.id)
                    null -> BrowseLayer(state, actions)
                }
                ClipActionSheet(state, actions)
            }
        }
    }
}

// region browsing

@Composable
private fun BrowseLayer(state: ClipboardPanelState, actions: ClipboardPanelActions) {
    Column(Modifier.fillMaxSize()) {
        PanelTopBar(state, actions)
        val pinned = state.pinned
        AnimatedVisibility(
            visible = pinned.isNotEmpty() && state.filter == ClipFilter.ALL,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            PinnedRow(pinned, state, actions)
        }
        val listed = state.listed
        if (listed.isEmpty()) EmptyState(state.filter)
        else LazyVerticalGrid(
            columns = GridCells.Fixed(integerResource(R.integer.config_clipboard_keyboard_col_count).coerceAtLeast(1)),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(listed, key = { it.id }) { item ->
                SwipeToDelete(item, actions, Modifier.animateItem()) {
                    ClipCard(item, state, actions)
                }
            }
        }
    }
}

@Composable
private fun PanelTopBar(state: ClipboardPanelState, actions: ClipboardPanelActions) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundIconButton(
            iconRes = R.drawable.sym_keyboard_search_lxx,
            description = stringResource(R.string.clipboard_search),
            onClick = actions::onStartSearch,
            tonal = true
        )
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(ClipFilter.entries.toList(), key = { it.name }) { filter ->
                val enabled = when (filter) {
                    ClipFilter.ALL -> true
                    ClipFilter.PINNED -> state.clips.any { it.isPinned }
                    else -> state.clips.any { it.kind.filter == filter }
                }
                if (enabled || state.filter == filter) {
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { state.filter = if (state.filter == filter) ClipFilter.ALL else filter },
                        label = { Text(stringResource(filter.labelRes), style = MaterialTheme.typography.labelLarge) },
                        shape = RoundedCornerShape(ClipboardShapes.chip),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        border = if (state.filter == filter) null else FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = false,
                            borderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
        }
        if (state.clips.any { !it.isPinned }) {
            RoundIconButton(
                iconRes = R.drawable.ic_bin,
                description = stringResource(R.string.clipboard_clear_all),
                onClick = actions::onClearHistory
            )
        }
        RoundIconButton(
            iconRes = R.drawable.ic_close,
            description = stringResource(R.string.dialog_close),
            onClick = actions::onCloseHistory
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedRow(pinned: List<ClipItem>, state: ClipboardPanelState, actions: ClipboardPanelActions) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(pinned, key = { it.id }) { item ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(ClipboardShapes.pinnedCard),
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(min = 72.dp, max = 190.dp)
                    .combinedClickable(
                        onClick = { actions.onPaste(item) },
                        onLongClick = { state.menuFor = item.id }
                    )
                    .animateItem()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painterResource(state.pinIconRes), null,
                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.preview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(
    item: ClipItem,
    actions: ClipboardPanelActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (item.isPinned) { // pinned clips stay until they are unpinned
        Box(modifier) { content() }
        return
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                actions.onDelete(item.id)
                true
            } else false
        },
        positionalThreshold = { it * 0.45f }
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(ClipboardShapes.card))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(end = 18.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    painterResource(R.drawable.ic_bin), null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipCard(
    item: ClipItem,
    state: ClipboardPanelState,
    actions: ClipboardPanelActions,
    modifier: Modifier = Modifier
) {
    val expanded = item.id in state.expanded
    var clamped by remember(item.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(ClipboardShapes.card),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { actions.onPaste(item) },
                onLongClick = { state.menuFor = item.id }
            )
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    item.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) 12 else 3,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { clamped = it.hasVisualOverflow || item.isTruncated }
                )
                Spacer(Modifier.height(2.dp))
                ClipMeta(item)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmallIconButton(
                    iconRes = R.drawable.ic_more_vert,
                    description = stringResource(R.string.clipboard_actions),
                    onClick = { state.menuFor = item.id }
                )
                if (clamped || expanded) {
                    SmallIconButton(
                        iconRes = R.drawable.ic_arrow_left,
                        description = stringResource(if (expanded) R.string.clipboard_show_less else R.string.clipboard_show_more),
                        rotation = if (expanded) 90f else -90f,
                        onClick = {
                            if (expanded) state.expanded.remove(item.id) else state.expanded.add(item.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipMeta(item: ClipItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(item.kind.iconRes()), null,
            Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        val time = remember(item.id, item.timeStamp) {
            DateUtils.getRelativeTimeSpanString(
                item.timeStamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            ).toString()
        }
        val chars = item.text.length
        val label = if (chars > 120)
            time + " · " + pluralStringResource(R.plurals.clipboard_characters, chars, chars)
        else time
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyState(filter: ClipFilter) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painterResource(R.drawable.sym_keyboard_clipboard_lxx), null,
            Modifier.size(32.dp).alpha(0.5f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (filter == ClipFilter.ALL) R.string.clipboard_empty_title else R.string.clipboard_empty_filtered),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (filter == ClipFilter.ALL) {
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.clipboard_empty_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// endregion

// region action sheet

@Composable
private fun BoxScope.ClipActionSheet(state: ClipboardPanelState, actions: ClipboardPanelActions) {
    val current = state.clip(state.menuFor)
    // keep the last clip while the sheet slides out, so it does not blink away
    var item by remember { mutableStateOf<ClipItem?>(null) }
    if (current != null) item = current
    val visible = current != null && state.typingMode == null
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { state.menuFor = null }
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(spring()) { it } + fadeIn(),
        exit = slideOutVertically(spring()) { it } + fadeOut()
    ) {
        val sheetItem = item ?: return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = ClipboardShapes.sheet, topEnd = ClipboardShapes.sheet),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(top = 8.dp, bottom = 10.dp)) {
                Box(
                    Modifier
                        .padding(bottom = 6.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 28.dp, height = 3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Text(
                    sheetItem.preview,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SheetAction(R.drawable.sym_keyboard_paste, R.string.clipboard_action_paste) {
                        state.menuFor = null
                        actions.onPaste(sheetItem)
                    }
                    SheetAction(
                        state.pinIconRes,
                        if (sheetItem.isPinned) R.string.clipboard_action_unpin else R.string.clipboard_action_pin,
                        highlighted = sheetItem.isPinned
                    ) {
                        state.menuFor = null
                        actions.onTogglePin(sheetItem.id)
                    }
                    SheetAction(R.drawable.ic_edit, R.string.clipboard_action_edit) {
                        state.menuFor = null
                        actions.onStartEdit(sheetItem)
                    }
                    SheetAction(R.drawable.sym_keyboard_copy, R.string.clipboard_action_copy) {
                        state.menuFor = null
                        actions.onCopy(sheetItem)
                    }
                    SheetAction(R.drawable.ic_share, R.string.clipboard_action_share) {
                        state.menuFor = null
                        actions.onShare(sheetItem)
                    }
                    SheetAction(R.drawable.ic_bin, R.string.clipboard_action_delete, destructive = true) {
                        state.menuFor = null
                        actions.onDelete(sheetItem.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    iconRes: Int,
    labelRes: Int,
    highlighted: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val container = when {
        destructive -> MaterialTheme.colorScheme.errorContainer
        highlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val content = when {
        destructive -> MaterialTheme.colorScheme.onErrorContainer
        highlighted -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .width(66.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(color = container, contentColor = content, shape = CircleShape) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(iconRes), null, Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

// endregion

// region search and edit

@Composable
private fun SearchLayer(state: ClipboardPanelState, actions: ClipboardPanelActions) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = CircleShape,
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Icon(
                        painterResource(R.drawable.sym_keyboard_search_lxx), null,
                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    KeyboardTextView(
                        buffer = state.buffer,
                        placeholder = stringResource(R.string.clipboard_search),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.buffer.text.isNotEmpty()) {
                        SmallIconButton(R.drawable.ic_close, stringResource(R.string.dialog_close)) {
                            state.buffer.clear()
                        }
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            RoundIconButton(
                iconRes = R.drawable.ic_arrow_back,
                description = stringResource(R.string.spoken_description_action_previous),
                onClick = { actions.onFinishTyping(false) }
            )
        }
        val results = state.searchResults()
        if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.clipboard_no_matches),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(results, key = { it.id }) { item ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(ClipboardShapes.pinnedCard),
                        modifier = Modifier.fillMaxHeight().widthIn(min = 72.dp, max = 220.dp)
                            .clickable { actions.onPaste(item) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painterResource(item.kind.iconRes()), null,
                                Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                item.preview,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditLayer(state: ClipboardPanelState, actions: ClipboardPanelActions, id: Long) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(
                iconRes = R.drawable.ic_close,
                description = stringResource(R.string.dialog_close),
                onClick = { actions.onFinishTyping(false) }
            )
            Text(
                stringResource(R.string.clipboard_action_edit),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                maxLines = 1
            )
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier.height(34.dp).clickable { actions.onFinishTyping(true) }
            ) {
                Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(ClipboardShapes.card),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Box(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                KeyboardTextView(
                    buffer = state.buffer,
                    placeholder = stringResource(R.string.clipboard_edit_hint),
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Text display with a caret, edited through the keyboard below it: a real text field cannot be
 * used inside the IME, as the IME cannot deliver input to its own window.
 */
@Composable
private fun KeyboardTextView(
    buffer: KeyboardTextBuffer,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var caretVisible by remember { mutableStateOf(true) }
    LaunchedEffect(buffer.text, buffer.cursor) {
        caretVisible = true
        while (true) {
            delay(530)
            caretVisible = !caretVisible
        }
    }
    val caretColor = MaterialTheme.colorScheme.primary
    val style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    Box(modifier) {
        if (buffer.text.isEmpty()) {
            Text(
                placeholder,
                style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BasicText(
            text = buffer.text,
            style = style,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        layout?.let { buffer.moveCursor(it.getOffsetForPosition(offset)) }
                    }
                }
                .drawWithContent {
                    drawContent()
                    val result = layout ?: return@drawWithContent
                    if (!caretVisible) return@drawWithContent
                    val cursor = buffer.cursor.coerceIn(0, buffer.text.length)
                    val rect = runCatching { result.getCursorRect(cursor) }.getOrNull() ?: return@drawWithContent
                    drawRect(
                        brush = SolidColor(caretColor),
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(2.dp.toPx(), rect.height)
                    )
                }
        )
    }
}

// endregion

// region small building blocks

@Composable
private fun RoundIconButton(
    iconRes: Int,
    description: String,
    tonal: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = if (tonal) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        contentColor = if (tonal) MaterialTheme.colorScheme.onSurface else LocalContentColor.current,
        shape = CircleShape,
        modifier = Modifier.size(36.dp).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(iconRes), description, Modifier.size(19.dp))
        }
    }
}

@Composable
private fun SmallIconButton(
    iconRes: Int,
    description: String,
    rotation: Float = 0f,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(iconRes), description,
            Modifier.size(17.dp).rotate(rotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun ClipKind.iconRes() = when (this) {
    ClipKind.LINK -> ICON_LINK
    ClipKind.EMAIL -> ICON_MAIL
    ClipKind.NUMBER -> ICON_NUMBERS
    ClipKind.TEXT -> ICON_TEXT
}

// endregion
