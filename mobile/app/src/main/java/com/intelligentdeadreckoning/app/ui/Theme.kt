package com.intelligentdeadreckoning.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF101916)
val Panel = Color(0xFF1B2722)
val Lime = Color(0xFFC5F487)
val Muted = Color(0xFFA8BAB0)
val Paper = Color(0xFFF0F4EE)
val Amber = Color(0xFFF3CF84)
val Line = Color(0xFF35463D)

@Composable
fun IdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Lime, onPrimary = Ink,
            secondary = Amber, onSecondary = Ink,
            background = Ink, onBackground = Paper,
            surface = Panel, onSurface = Paper,
            surfaceVariant = Panel, onSurfaceVariant = Muted,
            outline = Line,
        ),
        typography = Typography(),
        content = content,
    )
}
