/*
 * Copyright (C) 2021 The Android Open Source Project
 * parts taken from Material3 AlertDialog.kt
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark

@Composable
fun ThreeButtonAlertDialog(
    onDismissRequest: () -> Unit,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
    scrollContent: Boolean = false,
    onNeutral: () -> Unit = { },
    checkOk: () -> Boolean = { true },
    confirmButtonText: String? = stringResource(android.R.string.ok),
    cancelButtonText: String = stringResource(android.R.string.cancel),
    neutralButtonText: String? = null,
    properties: DialogProperties = DialogProperties()
) {
    // Material 3 AlertDialog rather than a hand-built Dialog + Surface: it brings the platform's
    // tonal elevation, corner radius, min/max width and text styles, and it keeps predictive-back
    // behaviour. The dialog's own three-slot button layout is not used, because a neutral button
    // that deliberately does NOT dismiss (see callers) does not fit the standard confirm/dismiss
    // pair — so the buttons stay in one FlowRow, with the neutral one pushed to the leading edge
    // as Material specifies.
    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
        modifier = modifier,
        title = title?.let {
            {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.headlineSmall) { title() }
            }
        },
        text = content?.let {
            {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                    if (scrollContent) {
                        Box(Modifier.verticalScroll(rememberScrollState())) { content() }
                    } else {
                        Box { content() }
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (neutralButtonText != null)
                    TextButton(onClick = onNeutral) { Text(neutralButtonText) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismissRequest) { Text(cancelButtonText) }
                if (confirmButtonText != null)
                    TextButton(
                        enabled = checkOk(),
                        onClick = { onConfirmed(); onDismissRequest() },
                    ) { Text(confirmButtonText) }
            }
        },
    )
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        ThreeButtonAlertDialog(
            onDismissRequest = {},
            onConfirmed = { },
            content = { Text("hello") },
            title = { Text("title") },
            neutralButtonText = "Default"
        )
    }
}
