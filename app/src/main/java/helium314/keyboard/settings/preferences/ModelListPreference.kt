// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.ModelEntry
import helium314.keyboard.latin.voice.PricingTier
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog

private data class PickerItem(val displayName: String, val slug: String, val badges: List<ModelBadge>)

@Composable
internal fun ModelListPreference(
    setting: Setting,
    entries: List<ModelEntry>,
    defaultSlug: String,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val customLabel = ctx.getString(R.string.voice_custom_model)
    val items = remember(entries, defaultSlug) {
        entries.map { it.toPickerItem(isDefault = it.slug == defaultSlug) } +
            PickerItem(customLabel, "custom", emptyList())
    }
    val selectedSlug = rememberStringPreferenceState(setting.key, defaultSlug).value
    val selected = items.firstOrNull { it.slug == selectedSlug }
    var showDialog by rememberSaveable { mutableStateOf(false) }

    // If the saved slug isn't in this catalog (e.g. a model retired in a previous app version,
    // or one carried over from the other provider), call it out so the user understands why the
    // picker doesn't reflect their choice — and so they pick something current.
    val description = selected?.displayName
        ?: ctx.getString(R.string.voice_model_unavailable, selectedSlug)
    Preference(
        name = setting.title,
        description = description,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        ModelPickerDialog(
            items = items,
            selectedSlug = selectedSlug,
            onDismissRequest = { showDialog = false },
            onItemSelected = { picked ->
                if (picked.slug != selectedSlug) {
                    prefs.edit { putString(setting.key, picked.slug) }
                }
            },
            title = { Text(setting.title) },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    items: List<PickerItem>,
    selectedSlug: String,
    onDismissRequest: () -> Unit,
    onItemSelected: (PickerItem) -> Unit,
    title: @Composable () -> Unit,
) {
    var selected by remember { mutableStateOf(items.firstOrNull { it.slug == selectedSlug }) }
    val state = rememberLazyListState()
    LaunchedEffect(selectedSlug) {
        // Custom is pinned to the bottom of the list; if it's currently selected, scroll to
        // the top so the user can see (and re-pick) the bundled presets — otherwise reopening
        // the picker lands on Custom and the presets above sit above the viewport. Stale slugs
        // (not in this catalog) also scroll to the top so the user sees the available choices.
        val rawIndex = if (selectedSlug == "custom") 0 else items.indexOfFirst { it.slug == selectedSlug }
        val index = if (rawIndex == -1) 0 else rawIndex
        state.scrollToItem(index, -state.layoutInfo.viewportSize.height / 3)
    }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { selected?.let { onItemSelected(it) } },
        confirmButtonText = null,
        checkOk = { selected != null },
        title = title,
        content = {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                LazyColumn(state = state) {
                    items(items, key = { it.slug }) { item ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    onDismissRequest()
                                    onItemSelected(item)
                                    selected = item
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .heightIn(min = 56.dp),
                        ) {
                            RadioButton(
                                selected = selected?.slug == item.slug,
                                // The whole row is already clickable with the same action; a
                                // second handler here only duplicates the ripple on the button.
                                onClick = null,
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(item.displayName)
                                if (item.badges.isNotEmpty()) PillRow(item.badges)
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun ModelEntry.toPickerItem(isDefault: Boolean): PickerItem =
    PickerItem(displayName, slug, buildBadges(isDefault))

private fun ModelEntry.buildBadges(isDefault: Boolean): List<ModelBadge> = buildList {
    if (isDefault) add(ModelBadge.DEFAULT)
    add(when (tier) {
        PricingTier.FREE -> ModelBadge.FREE
        PricingTier.CHEAP -> ModelBadge.CHEAP
        PricingTier.MEDIUM -> ModelBadge.MEDIUM
        PricingTier.EXPENSIVE -> ModelBadge.EXPENSIVE
    })
    if (zdr) add(ModelBadge.ZDR)
    if (cache) add(ModelBadge.CACHE)
}
