// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.CloseIcon
import helium314.keyboard.latin.utils.SearchIcon
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.settings.preferences.PreferenceGroupPosition
import helium314.keyboard.settings.preferences.PreferenceGroupSurface
import helium314.keyboard.settings.preferences.PreferenceListVerticalPadding
import helium314.keyboard.settings.preferences.preferenceGroupPositions

@Composable
fun SearchSettingsScreen(
    onClickBack: () -> Unit,
    title: String,
    settings: List<Any?>,
    content: @Composable (ColumnScope.() -> Unit)? = null // overrides settings if not null
) {
    SearchScreen(
        onClickBack = onClickBack,
        title = { Text(title) },
        content = {
            if (content != null) content()
            else {
                // LazyColumn over a pre-filtered, pre-keyed snapshot. Going lazy means only the
                // rows currently on screen run their `Preference()` composition (each of which
                // registers a SharedPreferences listener), which makes scrolling a long screen
                // — Voice especially — meaningfully smoother than the previous verticalScroll
                // Column that composed every row up-front.
                //
                // Keys are derived from the setting id alone, never the list position. Screens with
                // conditional rows (Voice hides most of its rows until Voice Input is on) insert and
                // remove entries, and a position-based key changed identity for every row after the
                // insertion point — so LazyColumn tore down and rebuilt every visible row, listeners
                // included, on something as small as flipping one switch. Duplicate values (the same
                // category heading twice on one screen) get an occurrence suffix so keys stay unique
                // without reintroducing that coupling.
                // Group positions are resolved here, alongside the keys, so each row knows which
                // corners to round without the list having to look at its neighbours while drawing.
                val visibleItems = remember(settings) {
                    val occurrences = HashMap<String, Int>()
                    val rows = settings.mapNotNull { value ->
                        value?.let {
                            val base = "${it.javaClass.simpleName}:$it"
                            val seen = (occurrences[base] ?: 0) + 1
                            occurrences[base] = seen
                            (if (seen == 1) base else "$base#$seen") to it
                        }
                    }
                    val positions = preferenceGroupPositions(rows) { (_, value) -> value is Int }
                    rows.mapIndexed { index, (key, value) -> Triple(key, value, positions[index]) }
                }
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                ) { innerPadding ->
                    LazyColumn(contentPadding = innerPadding.plusVertical()) {
                        items(
                            items = visibleItems,
                            key = { (key, _, _) -> key },
                            contentType = { (_, value, _) -> if (value is Int) "category" else "pref" },
                        ) { (_, value, position) ->
                            if (value is Int) {
                                PreferenceCategory(stringResource(value))
                            } else {
                                PreferenceGroupSurface(position) {
                                    SettingsActivity.settingsContainer[value]?.Preference()
                                }
                            }
                        }
                    }
                }
            }
        },
        filteredItems = { SettingsActivity.settingsContainer.filter(it) },
        itemContent = { it.Preference() }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T: Any?> SearchScreen(
    onClickBack: () -> Unit,
    title: @Composable () -> Unit,
    filteredItems: (String) -> List<T>,
    itemContent: @Composable (T) -> Unit,
    icon: @Composable (() -> Unit)? = null,
    menu: List<Pair<String, () -> Unit>>? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    // searchText and showSearch should have the same remember or rememberSaveable
    // saveable survives orientation changes and switching between screens, but shows the
    // keyboard in unexpected situations such as going back from another screen, which is rather annoying
    var searchText by remember { mutableStateOf(TextFieldValue()) }
    var showSearch by remember { mutableStateOf(false) }
    // The large title collapses as the content scrolls, the way Android's own Settings behaves.
    // Nested scroll bubbles up from whichever scrollable the screen puts in `content`, so the inner
    // LazyColumn (or a screen's own verticalScroll Column) drives this without extra wiring.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
    )
    { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {

            fun setShowSearch(value: Boolean) {
                showSearch = value
                if (!value) searchText = TextFieldValue()
            }
            BackHandler {
                if (showSearch || searchText.text.isNotEmpty()) setShowSearch(false)
                else onClickBack()
            }
            // Plain `surface`, matching the page behind the list. The header used to be
            // `surfaceContainer` — the same tone the grouped cards use — so a full-bleed block of
            // card colour sat above a column of inset cards and read as one giant mismatched card.
            // Only the cards are tinted now; the header just blends into the background, and lifts
            // to `surfaceContainer` once content scrolls under it.
            Surface(
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    LargeTopAppBar(
                        title = title,
                        windowInsets = WindowInsets(0),
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        navigationIcon = {
                            BackButton {
                                if (showSearch) setShowSearch(false)
                                else onClickBack()
                            }
                        },
                        actions = {
                            if (icon == null)
                                IconButton(onClick = { setShowSearch(!showSearch) }) { SearchIcon() }
                            else
                                icon()
                            if (menu != null)
                                Box {
                                    var showMenu by remember { mutableStateOf(false) }
                                    IconButton(
                                        onClick = { showMenu = true }
                                    ) { Icon(painterResource(R.drawable.ic_arrow_left), "menu", Modifier.rotate(-90f)) }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        menu.forEach {
                                            DropdownMenuItem(
                                                text = { Text(it.first) },
                                                onClick = { showMenu = false; it.second() }
                                            )
                                        }
                                    }
                                }
                        },
                    )
                    ExpandableSearchField(
                        expanded = showSearch,
                        onDismiss = { setShowSearch(false) },
                        search = searchText,
                        onSearchChange = { searchText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        // The header is `surface` now, so the field needs a tone above it to stay
                        // visible — it used to be set to `surface` to sit on a tinted header.
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                    )
                }
            }
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                if (searchText.text.isBlank() && content != null) {
                    Column {
                        content()
                    }
                } else {
                    // Cache the filter result by search text so scrolling / unrelated recompositions
                    // don't re-scan settingsContainer on every frame.
                    val query = searchText.text
                    val items = remember(query) { filteredItems(query) }
                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                    ) { innerPadding ->
                        LazyColumn(contentPadding = innerPadding.plusVertical()) {
                            // No `key` here: T is arbitrary (Setting, ColorSetting, Word), and a
                            // key that cannot go into a Bundle makes LazySaveableStateHolder throw.
                            // The list is rebuilt by remember(query) anyway, so identity buys nothing.
                            items(items) {
                                // Search results have no categories to group by, so each hit is its
                                // own card.
                                PreferenceGroupSurface(PreferenceGroupPosition.SINGLE) {
                                    itemContent(it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Adds [PreferenceListVerticalPadding] above and below the window insets a Scaffold hands its
 * content, so the first card clears the app bar and the last one clears the bottom edge. Added to
 * the existing padding rather than replacing it, or the list would draw under the navigation bar.
 */
@Composable
private fun PaddingValues.plusVertical(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        end = calculateEndPadding(direction),
        top = calculateTopPadding() + PreferenceListVerticalPadding,
        bottom = calculateBottomPadding() + PreferenceListVerticalPadding,
    )
}

// from StreetComplete
/** Expandable text field that can be dismissed and requests focus when it is expanded */
@Composable
fun ExpandableSearchField(
    expanded: Boolean,
    onDismiss: () -> Unit,
    search: TextFieldValue,
    onSearchChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) focusRequester.requestFocus()
    }
    AnimatedVisibility(visible = expanded, modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = modifier.focusRequester(focusRequester),
            leadingIcon = { SearchIcon() },
            trailingIcon = { IconButton(onClick = {
                if (search.text.isBlank()) onDismiss()
                else onSearchChange(TextFieldValue())
            }) { CloseIcon(android.R.string.cancel) } },
            singleLine = true,
            colors = colors,
            textStyle = contentTextDirectionStyle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
    }
}
