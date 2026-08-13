// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.graphics.drawable.VectorDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.dialogs.ToolbarKeysCustomizer
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.ReorderSwitchPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.rememberBooleanPreferenceState
import helium314.keyboard.settings.preferences.rememberStringPreferenceState
import helium314.keyboard.latin.utils.previewDark

@Composable
fun ToolbarScreen(
    onClickBack: () -> Unit,
) {
    val toolbarModeName by rememberStringPreferenceState(Settings.PREF_TOOLBAR_MODE, Defaults.PREF_TOOLBAR_MODE)
    val toolbarHidingGlobal by rememberBooleanPreferenceState(Settings.PREF_TOOLBAR_HIDING_GLOBAL, Defaults.PREF_TOOLBAR_HIDING_GLOBAL)
    val items = buildToolbarScreenItems(
        toolbarMode = ToolbarMode.valueOf(toolbarModeName),
        toolbarHidingGlobal = toolbarHidingGlobal,
    )

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_toolbar),
        settings = items
    )
}

internal fun buildToolbarScreenItems(
    toolbarMode: ToolbarMode,
    toolbarHidingGlobal: Boolean,
): List<Any?> {
    // the clipboard panel has no toolbar strip any more, so only the emoji strip is left here
    val secondaryToolbarVisible = toolbarMode != ToolbarMode.HIDDEN || !toolbarHidingGlobal
    return listOf(
        Settings.PREF_TOOLBAR_MODE,
        if (toolbarMode == ToolbarMode.HIDDEN) Settings.PREF_TOOLBAR_HIDING_GLOBAL else null,
        if (toolbarMode != ToolbarMode.HIDDEN) Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE || toolbarMode == ToolbarMode.TOOLBAR_KEYS)
            Settings.PREF_TOOLBAR_KEYS else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE || toolbarMode == ToolbarMode.SUGGESTION_STRIP)
            Settings.PREF_PINNED_TOOLBAR_KEYS else null,
        if (secondaryToolbarVisible) Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_QUICK_PIN_TOOLBAR_KEYS else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_AUTO_SHOW_TOOLBAR else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_AUTO_HIDE_TOOLBAR else null,
        if (toolbarMode != ToolbarMode.HIDDEN) Settings.PREF_VARIABLE_TOOLBAR_DIRECTION else null,
    )
}

fun createToolbarSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_TOOLBAR_MODE, R.string.toolbar_mode) { setting ->
        val ctx = LocalContext.current
        val items =
            ToolbarMode.entries.map { it.name.lowercase().getStringResourceOrName("toolbar_mode_", ctx) to it.name }
        ListPreference(
            setting,
            items,
            Defaults.PREF_TOOLBAR_MODE
        ) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_TOOLBAR_HIDING_GLOBAL, R.string.toolbar_hiding_global) {
        SwitchPreference(it, Defaults.PREF_TOOLBAR_HIDING_GLOBAL) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE, R.string.toolbar_swipe_down_to_hide, R.string.toolbar_swipe_down_to_hide_summary) {
        SwitchPreference(it, Defaults.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE)
    },
    Setting(context, Settings.PREF_TOOLBAR_KEYS, R.string.toolbar_keys) {
        ReorderSwitchPreference(it, Defaults.PREF_TOOLBAR_KEYS)
    },
    Setting(context, Settings.PREF_PINNED_TOOLBAR_KEYS, R.string.pinned_toolbar_keys) {
        ReorderSwitchPreference(it, Defaults.PREF_PINNED_TOOLBAR_KEYS)
    },
    Setting(context, Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, R.string.customize_toolbar_key_codes) {
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = it.title,
            onClick = { showDialog = true },
        )
        if (showDialog)
            ToolbarKeysCustomizer(
                key = it.key,
                onDismissRequest = { showDialog = false }
            )
    },
    Setting(context, Settings.PREF_QUICK_PIN_TOOLBAR_KEYS,
        R.string.quick_pin_toolbar_keys, R.string.quick_pin_toolbar_keys_summary)
    {
        SwitchPreference(it, Defaults.PREF_QUICK_PIN_TOOLBAR_KEYS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_AUTO_SHOW_TOOLBAR, R.string.auto_show_toolbar, R.string.auto_show_toolbar_summary)
    {
        SwitchPreference(it, Defaults.PREF_AUTO_SHOW_TOOLBAR)
    },
    Setting(context, Settings.PREF_AUTO_HIDE_TOOLBAR, R.string.auto_hide_toolbar, R.string.auto_hide_toolbar_summary)
    {
        SwitchPreference(it, Defaults.PREF_AUTO_HIDE_TOOLBAR)
    },
    Setting(context, Settings.PREF_VARIABLE_TOOLBAR_DIRECTION,
        R.string.var_toolbar_direction, R.string.var_toolbar_direction_summary)
    {
        SwitchPreference(it, Defaults.PREF_VARIABLE_TOOLBAR_DIRECTION)
    },
)

@Composable
fun KeyboardIconsSet.GetIcon(name: String?) {
    val ctx = LocalContext.current
    val drawable = getNewDrawable(name, ctx)
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (drawable is VectorDrawable)
            getIconId(name)?.let { Icon(painterResource(it), name, Modifier.fillMaxSize(0.8f)) }
        else if (drawable != null) {
            val px = with(LocalDensity.current) { 40.dp.toPx() }.toInt()
            Icon(drawable.toBitmap(px, px).asImageBitmap(), name, Modifier.fillMaxSize(0.8f))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            ToolbarScreen { }
        }
    }
}
