package io.github.kaustubhowmick.clipjoiner.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Indigo = Color(0xFF3F51B5)

@Composable
fun ClipJoinerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> darkColorScheme(primary = Color(0xFFBAC3FF), secondary = Color(0xFFFFC107))
        else -> lightColorScheme(primary = Indigo, secondary = Color(0xFF7A5900))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
