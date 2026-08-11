// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import helium314.keyboard.latin.R

@Composable
fun Theme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val accent = colorResource(R.color.accent)
    // Both are remembered because MaterialTheme compares by identity: handing it a freshly built
    // ColorScheme or Typography on every recomposition invalidates every consumer of
    // MaterialTheme.colorScheme in the tree. dynamicDarkColorScheme also reads ~40 system colors
    // per call, which is not something to repeat on each frame.
    val colorScheme = remember(dark, context, accent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            // todo (later): more colors
            if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)
        }
    }
    val typography = remember {
        val material3 = Typography()
        Typography(
            titleLarge = material3.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = material3.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = material3.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        //shapes = Shapes(),
        content = content
    )
}

const val previewDark = true
