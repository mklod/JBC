package dev.mklod.jbcbiron.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette lifted from the working web dashboard. */
object C {
    val bg = Color(0xFF0F1113)
    val card = Color(0xFF15181B)
    val cardBorder = Color(0xFF23272B)
    val tile = Color(0xFF1C2024)
    val tileBorder = Color(0xFF262B30)
    val text = Color(0xFFE7E9EA)
    val muted = Color(0xFF8B9198)
    val faint = Color(0xFF5F676E)
    val green = Color(0xFF30D158)
    val greenLine = Color(0xFF37D67A)
    val amber = Color(0xFFE0A23B)
    val red = Color(0xFFE0564B)
    val trackOff = Color(0xFF3A3F45)
    val modal = Color(0xFF1B1F23)
    val modalBorder = Color(0xFF2C3238)
    val stepBg = Color(0xFF262B30)
    val stepBorder = Color(0xFF3A4046)
}

@Composable
fun JbcTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.bg,
            surface = C.card,
            primary = C.green,
            onBackground = C.text,
            onSurface = C.text,
        ),
        content = content,
    )
}
