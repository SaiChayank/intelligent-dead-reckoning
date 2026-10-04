package com.intelligentdeadreckoning.app.ui

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.intelligentdeadreckoning.app.ui.design.IdrPalette
import com.intelligentdeadreckoning.app.ui.design.IdrShapes
import com.intelligentdeadreckoning.app.ui.design.IdrType
import com.intelligentdeadreckoning.app.ui.design.LocalIdrReducedMotion

/**
 * The application theme. Colour, type, shape and motion live in `ui.design`; this entry
 * point additionally honours the platform animation scale so every animated primitive can
 * read [LocalIdrReducedMotion] and collapse its duration to zero.
 */
@Composable
fun IdrTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f) == 0f
    }
    CompositionLocalProvider(LocalIdrReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = IdrPalette.accent,
                onPrimary = IdrPalette.background,
                primaryContainer = IdrPalette.accent.copy(alpha = 0.16f),
                onPrimaryContainer = IdrPalette.accent,
                secondary = IdrPalette.info,
                onSecondary = IdrPalette.background,
                tertiary = IdrPalette.success,
                onTertiary = IdrPalette.background,
                background = IdrPalette.background,
                onBackground = IdrPalette.textPrimary,
                surface = IdrPalette.surface,
                onSurface = IdrPalette.textPrimary,
                surfaceVariant = IdrPalette.surfaceElevated,
                onSurfaceVariant = IdrPalette.textSecondary,
                outline = IdrPalette.borderStrong,
                outlineVariant = IdrPalette.border,
                error = IdrPalette.danger,
                onError = IdrPalette.textPrimary,
                scrim = Color(0xCC000000),
            ),
            typography = IdrType.material,
            shapes = IdrShapes.material,
            content = content,
        )
    }
}
