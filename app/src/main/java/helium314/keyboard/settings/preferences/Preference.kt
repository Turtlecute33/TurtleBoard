// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.settings.IconOrImage
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark

// partially taken from StreetComplete / SCEE

@Composable
fun PreferenceCategory(
    title: String,
    modifier: Modifier = Modifier,
) {
    // No divider: the rows below carry their own grouped card, so a rule here would draw a second,
    // competing separator. Aligned to the card's 16dp inset plus the row's own 16dp padding.
    Text(
        text = title,
        modifier = modifier.padding(top = 20.dp, start = 32.dp, end = 24.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall
    )
}

/**
 * Where a row sits inside its visual group, which decides how its card corners are rounded.
 *
 * Rows are wrapped individually rather than a whole group being placed inside one `Card`, so the
 * list stays lazy: a card wrapping 20 rows would force all 20 to compose (and register their
 * preference listeners) the moment any part of the group scrolled into view.
 */
enum class PreferenceGroupPosition { SINGLE, FIRST, MIDDLE, LAST }

/**
 * Position of every row in [rows], where a heading (per [isCategory]) breaks the run. A group is a
 * stretch of consecutive non-heading rows; headings themselves get [PreferenceGroupPosition.SINGLE]
 * and never render a card.
 *
 * Resolved once per settings list rather than while drawing, so a row never has to inspect its
 * neighbours during composition.
 */
internal fun <T> preferenceGroupPositions(rows: List<T>, isCategory: (T) -> Boolean): List<PreferenceGroupPosition> =
    rows.mapIndexed { index, row ->
        if (isCategory(row)) return@mapIndexed PreferenceGroupPosition.SINGLE
        val startsGroup = index == 0 || isCategory(rows[index - 1])
        val endsGroup = index == rows.lastIndex || isCategory(rows[index + 1])
        when {
            startsGroup && endsGroup -> PreferenceGroupPosition.SINGLE
            startsGroup -> PreferenceGroupPosition.FIRST
            endsGroup -> PreferenceGroupPosition.LAST
            else -> PreferenceGroupPosition.MIDDLE
        }
    }

private val GROUP_OUTER_CORNER = 20.dp
private val GROUP_INNER_CORNER = 4.dp

/** Rounded, tonal background that makes consecutive rows read as one grouped section. */
@Composable
fun PreferenceGroupSurface(
    position: PreferenceGroupPosition,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val top = if (position == PreferenceGroupPosition.FIRST || position == PreferenceGroupPosition.SINGLE)
        GROUP_OUTER_CORNER else GROUP_INNER_CORNER
    val bottom = if (position == PreferenceGroupPosition.LAST || position == PreferenceGroupPosition.SINGLE)
        GROUP_OUTER_CORNER else GROUP_INNER_CORNER
    Surface(
        modifier = modifier.padding(
            horizontal = 16.dp,
            // A hairline gap between rows is what separates them now that the divider is gone.
            vertical = 1.dp,
        ),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        content = content,
    )
}

@Composable
fun Preference(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    @DrawableRes icon: Int? = null,
    /**
     * On/off state of the row, for preferences whose trailing [value] is a decorative `Switch`.
     * When set, the row is exposed to accessibility services as a switch carrying this state, so
     * TalkBack announces "on"/"off"; otherwise it is a plain clickable row and the state is
     * invisible to screen readers, since a `Switch` with `onCheckedChange = null` contributes no
     * semantics of its own.
     */
    switchState: Boolean? = null,
    value: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (switchState == null) Modifier.clickable { onClick() }
                else Modifier.toggleable(
                    value = switchState,
                    role = Role.Switch,
                    onValueChange = { onClick() },
                )
            )
            // 16dp horizontal and a 56dp minimum are the Material 3 list-item metrics.
            .heightIn(min = 56.dp)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null)
            IconOrImage(icon, name, 32)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(
                        text = description,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        if (value != null) {
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(
                    textAlign = TextAlign.End,
                    hyphens = Hyphens.Auto
                ),
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        space = 8.dp,
                        alignment = Alignment.End
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) { value() }
            }
        }
    }
}

@Preview
@Composable
private fun PreferencePreview() {
    Theme(previewDark) {
        Surface {
            Column {
                PreferenceCategory("Preference Category")
                Preference(
                    name = "Preference",
                    onClick = {},
                )
                Preference(
                    name = "Preference with icon",
                    onClick = {},
                    icon = R.drawable.ic_settings_about
                )
                SliderPreference(
                    name = "SliderPreference",
                    key = "",
                    default = 1,
                    description = { it.toString() },
                    range = -5f..5f
                )
                Preference(
                    name = "Preference with icon and description",
                    description = "some text",
                    onClick = {},
                    icon = R.drawable.ic_settings_about
                )
                Preference(
                    name = "Preference with switch",
                    onClick = {}
                ) {
                    Switch(checked = true, onCheckedChange = {})
                }
                SwitchPreference(
                    name = "SwitchPreference",
                    key = "none",
                    default = true
                )
                Preference(
                    name = "Preference",
                    onClick = {},
                    description = "A long description which may actually be several lines long, so it should wrap."
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_left), null)
                }
                Preference(
                    name = "Long preference name that wraps",
                    onClick = {},
                ) {
                    Text("Long preference value")
                }
                Preference(
                    name = "Long preference name 2",
                    onClick = {},
                    description = "hello I am description"
                ) {
                    Text("Long preference value")
                }
            }
        }
    }
}
