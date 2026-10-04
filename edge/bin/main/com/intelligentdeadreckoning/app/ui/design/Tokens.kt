package com.intelligentdeadreckoning.app.ui.design

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Intelligent Dead Reckoning design tokens.
 *
 * One accent ("signal lime") carries selection, routes, primary actions and live metrics.
 * Neutral surfaces stay almost black so the offline map and the data read as the content.
 * Semantic tones (success / warning / danger / info) are deliberately separate from the
 * accent so that "this is simulated" can never be confused with "this is selected".
 */
object IdrPalette {
    // Surfaces, darkest to lightest.
    val background = Color(0xFF070707)
    val surface = Color(0xFF111111)
    val surfaceElevated = Color(0xFF151515)
    val surfaceHigh = Color(0xFF181818)

    // Floating chrome over the map. Kept translucent; used sparingly.
    val glass = Color(0xCC0F0F0F)
    val glassStrong = Color(0xE60F0F0F)

    val borderSubtle = Color(0x0FFFFFFF) // 6%
    val border = Color(0x1AFFFFFF) // 10%
    val borderStrong = Color(0x1FFFFFFF) // 12%

    val textPrimary = Color(0xFFF5F5F5)
    val textSecondary = Color(0xFFA1A1AA)
    val textMuted = Color(0xFF71717A)

    /** The single project accent. */
    val accent = Color(0xFFC6F24E)
    val accentPressed = Color(0xFFB4E03C)
    val accentDisabled = Color(0x4DC6F24E)

    val success = Color(0xFF5EE6A8)
    val warning = Color(0xFFF5B94A)
    val danger = Color(0xFFF2555A)
    val info = Color(0xFF7CC7F2)

    /**
     * Map presentation, separate from UI chrome. These are the renderer's shipped layer
     * colours, kept here so the map and the design system cannot drift apart. The values
     * are the ones recorded in `mobile/MAP_DEVICE_VERIFICATION.md` screenshots; changing
     * them changes device-verified evidence and needs a re-verification pass.
     */
    val mapTrail = Color(0xFF7856D8) // drawn positions and trail segments
    val mapHeading = Color(0xFF40228B) // heading wedge
    val mapOutage = Color(0xFFD99100) // automatic outage segment
    val mapAccuracy = Color(0xFF3E8CD9) // reported fix radius / accuracy ring
    val mapComparison = Color(0xFFD74545) // scripted comparison line — an illustration
    val mapScenario = Color(0xFF637888) // scripted scenario path
}

/** 4dp base spacing scale. */
object IdrSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 40.dp
}

object IdrRadius {
    val sm = 10.dp
    val md = 14.dp
    val lg = 18.dp
    val xl = 24.dp
    val xxl = 28.dp
    val pill = 999.dp
}

object IdrSize {
    val iconSm = 16.dp
    val icon = 20.dp
    val iconLg = 24.dp
    val touchTarget = 44.dp
    val rowHeight = 56.dp
    val dot = 8.dp
    val dotSm = 6.dp
}

/**
 * Motion. `effective` collapses every duration to zero when the user has disabled
 * system animations, so the interface never animates against an accessibility setting.
 */
object IdrMotion {
    const val instantMs = 90
    const val fastMs = 150
    const val baseMs = 220
    const val slowMs = 320
    const val pageMs = 380

    fun effective(durationMs: Int, reduced: Boolean): Int = if (reduced) 0 else durationMs

    /** Named `tweenSpec` so it never shadows the framework's `tween` builder. */
    fun <T> tweenSpec(durationMs: Int, reduced: Boolean): TweenSpec<T> =
        tween(durationMillis = effective(durationMs, reduced))

    fun <T> standardSpec(reduced: Boolean): TweenSpec<T> = tweenSpec(baseMs, reduced)
}

/** True when the platform animation scale is zero, or a host asks for reduced motion. */
val LocalIdrReducedMotion = compositionLocalOf { false }

private val fontDefault = FontFamily.Default
private val fontMono = FontFamily.Monospace

/**
 * Typography scale. The product ships no bundled font binaries, so the platform family is
 * used with explicit weights and tightening on large sizes; numeric readouts use the
 * monospaced family so instrument values keep a stable column width.
 */
object IdrType {
    val displayLarge = TextStyle(fontFamily = fontDefault, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp, lineHeight = 48.sp)
    val displayMedium = TextStyle(fontFamily = fontDefault, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.9).sp, lineHeight = 40.sp)
    val headlineLarge = TextStyle(fontFamily = fontDefault, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, lineHeight = 33.sp)
    val headlineMedium = TextStyle(fontFamily = fontDefault, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, lineHeight = 27.sp)
    val titleLarge = TextStyle(fontFamily = fontDefault, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp, lineHeight = 24.sp)
    val titleMedium = TextStyle(fontFamily = fontDefault, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
    val bodyLarge = TextStyle(fontFamily = fontDefault, fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp)
    val bodyMedium = TextStyle(fontFamily = fontDefault, fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val bodySmall = TextStyle(fontFamily = fontDefault, fontSize = 12.5.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    val label = TextStyle(fontFamily = fontDefault, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, lineHeight = 14.sp)
    val labelSmall = TextStyle(fontFamily = fontDefault, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, lineHeight = 13.sp)
    val metric = TextStyle(fontFamily = fontMono, fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.8).sp, lineHeight = 32.sp)
    val metricSmall = TextStyle(fontFamily = fontMono, fontSize = 19.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp, lineHeight = 23.sp)
    /** The single oversized instrument readout (demo gauge). */
    val gauge = TextStyle(fontFamily = fontDefault, fontSize = 52.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, lineHeight = 56.sp)
    val mono = TextStyle(fontFamily = fontMono, fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    val monoSmall = TextStyle(fontFamily = fontMono, fontSize = 11.5.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp)
    /** Tracked product wordmark. */
    val brand = TextStyle(fontFamily = fontDefault, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, lineHeight = 20.sp)
    /** Very compact label for the floating dock. */
    val dockLabel = TextStyle(fontFamily = fontDefault, fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, lineHeight = 12.sp)

    val material = Typography(
        displayLarge = displayLarge,
        displayMedium = displayMedium,
        displaySmall = headlineLarge,
        headlineLarge = headlineLarge,
        headlineMedium = headlineMedium,
        headlineSmall = titleLarge,
        titleLarge = titleLarge,
        titleMedium = titleMedium,
        titleSmall = TextStyle(fontFamily = fontDefault, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
        bodyLarge = bodyLarge,
        bodyMedium = bodyMedium,
        bodySmall = bodySmall,
        labelLarge = TextStyle(fontFamily = fontDefault, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp),
        labelMedium = label,
        labelSmall = labelSmall,
    )
}

object IdrShapes {
    val card = RoundedCornerShape(IdrRadius.xl)
    val cardLarge = RoundedCornerShape(IdrRadius.xxl)
    val control = RoundedCornerShape(IdrRadius.md)
    val controlLarge = RoundedCornerShape(IdrRadius.lg)
    val chip = RoundedCornerShape(IdrRadius.sm)
    val pill = RoundedCornerShape(IdrRadius.pill)

    val material = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(IdrRadius.sm),
        medium = RoundedCornerShape(IdrRadius.md),
        large = RoundedCornerShape(IdrRadius.lg),
        extraLarge = RoundedCornerShape(IdrRadius.xxl),
    )
}
