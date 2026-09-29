package com.intelligentdeadreckoning.app.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------------------------

/**
 * Card emphasis. The interface uses whitespace for most separation, so only surfaces that
 * genuinely float (glass) or lead a screen (primary) carry elevation.
 */
enum class IdrEmphasis { PRIMARY, SECONDARY, UTILITY, GLASS, QUIET }

@Composable
fun IdrCard(
    modifier: Modifier = Modifier,
    emphasis: IdrEmphasis = IdrEmphasis.SECONDARY,
    accentEdge: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = when (emphasis) {
        IdrEmphasis.PRIMARY, IdrEmphasis.GLASS -> IdrShapes.cardLarge
        IdrEmphasis.SECONDARY -> IdrShapes.card
        IdrEmphasis.UTILITY, IdrEmphasis.QUIET -> IdrShapes.controlLarge
    }
    val background = when (emphasis) {
        IdrEmphasis.PRIMARY -> IdrPalette.surfaceElevated
        IdrEmphasis.GLASS -> IdrPalette.glass
        IdrEmphasis.SECONDARY -> IdrPalette.surface
        IdrEmphasis.UTILITY -> Color.Transparent
        IdrEmphasis.QUIET -> IdrPalette.background
    }
    val padding = when (emphasis) {
        IdrEmphasis.PRIMARY -> IdrSpace.xxl
        IdrEmphasis.GLASS -> IdrSpace.lg
        IdrEmphasis.SECONDARY -> IdrSpace.lg
        IdrEmphasis.UTILITY -> IdrSpace.md
        IdrEmphasis.QUIET -> IdrSpace.md
    }
    val bordered = emphasis != IdrEmphasis.QUIET
    Column(
        modifier
            .then(if (emphasis == IdrEmphasis.GLASS) Modifier.shadow(28.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .background(background, shape)
            .then(if (bordered) Modifier.border(1.dp, IdrPalette.borderSubtle, shape) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(IdrSpace.md),
    ) {
        if (accentEdge) {
            Box(
                Modifier
                    .width(34.dp)
                    .height(3.dp)
                    .clip(IdrShapes.pill)
                    .background(IdrPalette.accent),
            )
        }
        content()
    }
}

@Composable
fun IdrSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = IdrPalette.textMuted,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = tone, style = IdrType.label, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
fun IdrDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(IdrPalette.borderSubtle))
}

// ---------------------------------------------------------------------------------------------
// Icons — one hand-drawn line family, no external icon dependency.
// ---------------------------------------------------------------------------------------------

enum class IdrGlyph {
    HOME, NAVIGATE, PULSE, INFO, LOCATE, COMPASS, PLUS, MINUS, SLIDERS,
    PLAY, PAUSE, RESET, STOP, RECORD, WARNING, SATELLITE, CHECK, CLOSE, LAYERS, ARROW_NORTH,
}

@Composable
fun IdrIcon(
    glyph: IdrGlyph,
    modifier: Modifier = Modifier,
    tint: Color = IdrPalette.textSecondary,
    size: Dp = IdrSize.icon,
    strokeWidth: Dp = 1.7.dp,
) {
    Canvas(modifier.size(size)) {
        drawGlyph(glyph, tint, strokeWidth.toPx())
    }
}

private fun DrawScope.drawGlyph(glyph: IdrGlyph, color: Color, w: Float) {
    val s = size.minDimension
    val cap = StrokeCap.Round
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    when (glyph) {
        IdrGlyph.HOME -> {
            val path = Path().apply {
                moveTo(s * .16f, s * .46f); lineTo(s * .5f, s * .16f); lineTo(s * .84f, s * .46f)
                lineTo(s * .84f, s * .84f); lineTo(s * .16f, s * .84f); close()
            }
            drawPath(path, color, style = Stroke(w, cap = cap))
        }
        IdrGlyph.NAVIGATE -> {
            val path = Path().apply {
                moveTo(s * .2f, s * .8f); lineTo(s * .46f, s * .26f); lineTo(s * .58f, s * .5f)
                lineTo(s * .82f, s * .58f); close()
            }
            drawPath(path, color, style = Stroke(w, cap = cap))
        }
        IdrGlyph.PULSE -> {
            val path = Path().apply {
                moveTo(0f, s * .56f); lineTo(s * .2f, s * .56f); lineTo(s * .34f, s * .22f)
                lineTo(s * .54f, s * .82f); lineTo(s * .68f, s * .4f); lineTo(s, s * .4f)
            }
            drawPath(path, color, style = Stroke(w, cap = cap))
        }
        IdrGlyph.INFO -> {
            drawCircle(color, s * .38f, Offset(s / 2, s / 2), style = Stroke(w))
            drawCircle(color, w * .7f, Offset(s / 2, s * .31f))
            drawLine(color, p(.5f, .45f), p(.5f, .7f), w, cap)
        }
        IdrGlyph.LOCATE -> {
            drawCircle(color, s * .26f, Offset(s / 2, s / 2), style = Stroke(w))
            drawCircle(color, s * .06f, Offset(s / 2, s / 2))
            drawLine(color, p(.5f, .04f), p(.5f, .2f), w, cap)
            drawLine(color, p(.5f, .8f), p(.5f, .96f), w, cap)
            drawLine(color, p(.04f, .5f), p(.2f, .5f), w, cap)
            drawLine(color, p(.8f, .5f), p(.96f, .5f), w, cap)
        }
        IdrGlyph.COMPASS -> {
            drawCircle(color, s * .38f, Offset(s / 2, s / 2), style = Stroke(w))
            val path = Path().apply {
                moveTo(s * .62f, s * .3f); lineTo(s * .5f, s * .58f); lineTo(s * .38f, s * .7f); lineTo(s * .5f, s * .42f); close()
            }
            drawPath(path, color, style = Stroke(w * .8f, cap = cap))
        }
        IdrGlyph.PLUS -> {
            drawLine(color, p(.5f, .2f), p(.5f, .8f), w, cap)
            drawLine(color, p(.2f, .5f), p(.8f, .5f), w, cap)
        }
        IdrGlyph.MINUS -> drawLine(color, p(.2f, .5f), p(.8f, .5f), w, cap)
        IdrGlyph.SLIDERS -> {
            drawLine(color, p(.16f, .3f), p(.84f, .3f), w, cap)
            drawLine(color, p(.16f, .7f), p(.84f, .7f), w, cap)
            drawCircle(color, s * .1f, p(.36f, .3f))
            drawCircle(color, s * .1f, p(.64f, .7f))
        }
        IdrGlyph.PLAY -> {
            val path = Path().apply { moveTo(s * .3f, s * .2f); lineTo(s * .8f, s * .5f); lineTo(s * .3f, s * .8f); close() }
            drawPath(path, color, style = Stroke(w, cap = cap))
        }
        IdrGlyph.PAUSE -> {
            drawLine(color, p(.38f, .22f), p(.38f, .78f), w, cap)
            drawLine(color, p(.62f, .22f), p(.62f, .78f), w, cap)
        }
        IdrGlyph.RESET -> {
            drawArc(color, 40f, 280f, false, Offset(s * .2f, s * .2f), Size(s * .6f, s * .6f), style = Stroke(w, cap = cap))
            drawLine(color, p(.72f, .16f), p(.74f, .38f), w, cap)
            drawLine(color, p(.72f, .16f), p(.5f, .22f), w, cap)
        }
        IdrGlyph.STOP -> {
            val inset = s * .28f
            drawRoundRect(
                color, Offset(inset, inset), Size(s - inset * 2, s - inset * 2),
                androidx.compose.ui.geometry.CornerRadius(s * .08f), style = Stroke(w, cap = cap),
            )
        }
        IdrGlyph.RECORD -> drawCircle(color, s * .28f, Offset(s / 2, s / 2))
        IdrGlyph.WARNING -> {
            val path = Path().apply {
                moveTo(s * .5f, s * .16f); lineTo(s * .88f, s * .8f); lineTo(s * .12f, s * .8f); close()
            }
            drawPath(path, color, style = Stroke(w, cap = cap))
            drawLine(color, p(.5f, .38f), p(.5f, .58f), w, cap)
            drawCircle(color, w * .7f, p(.5f, .69f))
        }
        IdrGlyph.SATELLITE -> {
            drawRoundRect(color, Offset(s * .34f, s * .34f), Size(s * .32f, s * .32f), style = Stroke(w))
            drawArc(color, 200f, 140f, false, Offset(s * .1f, s * .1f), Size(s * .8f, s * .8f), style = Stroke(w, cap = cap))
            drawArc(color, 200f, 140f, false, Offset(s * -.06f, s * -.06f), Size(s * 1.12f, s * 1.12f), style = Stroke(w * .8f, cap = cap))
        }
        IdrGlyph.CHECK -> {
            val path = Path().apply { moveTo(s * .2f, s * .54f); lineTo(s * .42f, s * .74f); lineTo(s * .8f, s * .28f) }
            drawPath(path, color, style = Stroke(w, cap = cap))
        }
        IdrGlyph.CLOSE -> {
            drawLine(color, p(.24f, .24f), p(.76f, .76f), w, cap)
            drawLine(color, p(.76f, .24f), p(.24f, .76f), w, cap)
        }
        IdrGlyph.LAYERS -> {
            fun diamond(y: Float) {
                val path = Path().apply {
                    moveTo(s * .5f, y - s * .14f); lineTo(s * .84f, y); lineTo(s * .5f, y + s * .14f); lineTo(s * .16f, y); close()
                }
                drawPath(path, color, style = Stroke(w * .85f, cap = cap))
            }
            diamond(s * .34f); diamond(s * .62f)
        }
        IdrGlyph.ARROW_NORTH -> {
            drawLine(color, p(.5f, .82f), p(.5f, .22f), w, cap)
            drawLine(color, p(.5f, .22f), p(.3f, .44f), w, cap)
            drawLine(color, p(.5f, .22f), p(.7f, .44f), w, cap)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons and chips
// ---------------------------------------------------------------------------------------------

enum class IdrButtonVariant { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * Press feedback is a 1 → 0.97 scale with a colour shift instead of a ripple, so touch
 * feedback stays consistent across dark surfaces and the map.
 */
@Composable
fun IdrButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: IdrButtonVariant = IdrButtonVariant.PRIMARY,
    glyph: IdrGlyph? = null,
    testTag: String? = null,
    minHeight: Dp = IdrSize.touchTarget,
) {
    val reduced = LocalIdrReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = IdrMotion.tweenSpec(IdrMotion.instantMs, reduced),
        label = "press",
    )
    val shape = IdrShapes.controlLarge
    val target = when (variant) {
        IdrButtonVariant.PRIMARY -> if (enabled) IdrPalette.accent else IdrPalette.accentDisabled
        IdrButtonVariant.SECONDARY -> if (enabled) IdrPalette.surfaceHigh else IdrPalette.surface
        IdrButtonVariant.GHOST -> Color.Transparent
        IdrButtonVariant.DANGER -> if (enabled) IdrPalette.danger else IdrPalette.danger.copy(alpha = 0.3f)
    }
    val background by animateColorAsState(target, IdrMotion.tweenSpec(IdrMotion.fastMs, reduced), label = "buttonBg")
    val contentColor = when (variant) {
        IdrButtonVariant.PRIMARY -> IdrPalette.background
        IdrButtonVariant.DANGER -> IdrPalette.textPrimary
        IdrButtonVariant.SECONDARY, IdrButtonVariant.GHOST -> if (enabled) IdrPalette.textPrimary else IdrPalette.textMuted
    }
    val host = modifier
        .height(minHeight)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .background(background, shape)
        .then(
            if (variant == IdrButtonVariant.SECONDARY || variant == IdrButtonVariant.GHOST) {
                Modifier.border(1.dp, if (enabled) IdrPalette.border else IdrPalette.borderSubtle, shape)
            } else Modifier
        )
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    Row(
        host.then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            IdrIcon(glyph, tint = contentColor, size = IdrSize.iconSm)
            Spacer(Modifier.width(IdrSpace.sm))
        }
        Text(label, color = contentColor, style = IdrType.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Selectable chip. Carries `Selected` semantics so assistive technology (and the existing
 * instrumented suite) can read the selection state.
 */
@Composable
fun IdrChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: IdrGlyph? = null,
    emphasized: Boolean = false,
    testTag: String? = null,
) {
    val reduced = LocalIdrReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = IdrMotion.tweenSpec(IdrMotion.instantMs, reduced),
        label = "chipPress",
    )
    val shape = IdrShapes.pill
    val backgroundTone by animateColorAsState(
        when {
            !enabled -> Color.Transparent
            selected && emphasized -> IdrPalette.accent.copy(alpha = 0.16f)
            selected -> IdrPalette.surfaceHigh
            else -> Color.Transparent
        },
        IdrMotion.tweenSpec(IdrMotion.fastMs, reduced), label = "chipBg",
    )
    val borderTone by animateColorAsState(
        when {
            !enabled -> IdrPalette.borderSubtle
            selected -> IdrPalette.accent.copy(alpha = 0.55f)
            else -> IdrPalette.border
        },
        IdrMotion.tweenSpec(IdrMotion.fastMs, reduced), label = "chipBorder",
    )
    val contentTone by animateColorAsState(
        when {
            !enabled -> IdrPalette.textMuted
            selected -> IdrPalette.accent
            else -> IdrPalette.textSecondary
        },
        IdrMotion.tweenSpec(IdrMotion.fastMs, reduced), label = "chipText",
    )
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(backgroundTone, shape)
            .border(1.dp, borderTone, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { this.selected = selected; role = Role.Checkbox }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = IdrSpace.md, vertical = IdrSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            IdrIcon(glyph, tint = contentTone, size = 14.dp)
            Spacer(Modifier.width(IdrSpace.xs))
        }
        Text(label, color = contentTone, style = IdrType.monoSmall, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Status, stats, meters
// ---------------------------------------------------------------------------------------------

enum class IdrTone(val color: Color) {
    NEUTRAL(IdrPalette.textMuted),
    ACCENT(IdrPalette.accent),
    SUCCESS(IdrPalette.success),
    WARNING(IdrPalette.warning),
    DANGER(IdrPalette.danger),
    INFO(IdrPalette.info),
}

/** Dot + label + optional detail, used for source and mode banners. */
@Composable
fun StatusPill(
    label: String,
    modifier: Modifier = Modifier,
    tone: IdrTone = IdrTone.NEUTRAL,
    detail: String? = null,
    glyph: IdrGlyph? = null,
    testTag: String? = null,
) {
    Row(
        modifier
            .clip(IdrShapes.pill)
            .background(tone.color.copy(alpha = 0.10f), IdrShapes.pill)
            .border(1.dp, tone.color.copy(alpha = 0.24f), IdrShapes.pill)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = IdrSpace.md, vertical = IdrSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            IdrIcon(glyph, tint = tone.color, size = 14.dp)
        } else {
            Box(Modifier.size(6.dp).clip(IdrShapes.pill).background(tone.color))
        }
        Spacer(Modifier.width(IdrSpace.sm))
        Text(label, color = tone.color, style = IdrType.label, maxLines = 1)
        if (detail != null) {
            Spacer(Modifier.width(IdrSpace.sm))
            Text(detail, color = IdrPalette.textSecondary, style = IdrType.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Compact metric block: label, value, optional unit and caption. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    caption: String? = null,
    tone: IdrTone = IdrTone.NEUTRAL,
    valueStyle: TextStyle = IdrType.metricSmall,
    valueTag: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(IdrSpace.xs)) {
        Text(label.uppercase(), color = IdrPalette.textMuted, style = IdrType.labelSmall, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = if (tone == IdrTone.NEUTRAL) IdrPalette.textPrimary else tone.color,
                style = valueStyle,
                maxLines = 1,
                modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
            )
            if (unit != null) {
                Spacer(Modifier.width(IdrSpace.xs))
                Text(unit, color = IdrPalette.textMuted, style = IdrType.bodySmall, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (caption != null) Text(caption, color = IdrPalette.textMuted, style = IdrType.bodySmall)
    }
}

/** Thin magnitude bar. Used for sensor amplitude and queue occupancy. */
@Composable
fun IdrMeter(
    fraction: Float,
    modifier: Modifier = Modifier,
    tone: IdrTone = IdrTone.ACCENT,
    height: Dp = 4.dp,
) {
    val reduced = LocalIdrReducedMotion.current
    val clamped = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec = IdrMotion.standardSpec(reduced),
        label = "meter",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(IdrShapes.pill)
            .background(IdrPalette.borderSubtle),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(IdrShapes.pill)
                .background(tone.color),
        )
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    tone: IdrTone = IdrTone.NEUTRAL,
    valueTag: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = IdrPalette.textMuted, style = IdrType.bodySmall, modifier = Modifier.weight(1f))
        Text(
            value,
            color = if (tone == IdrTone.NEUTRAL) IdrPalette.textSecondary else tone.color,
            style = if (mono) IdrType.monoSmall else IdrType.bodySmall,
            textAlign = TextAlign.End,
            modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Loading, empty and error states — never a blank screen.
// ---------------------------------------------------------------------------------------------

enum class IdrStateTone { LOADING, EMPTY, ERROR }

/** Static placeholder blocks. Deliberately not animated: a keyboard-driven test clock must
 *  never be blocked by a running pulsing animation while a screen is under test. */
@Composable
fun PlaceholderBlock(modifier: Modifier = Modifier, height: Dp = 14.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(IdrShapes.chip)
            .background(
                Brush.verticalGradient(
                    listOf(IdrPalette.surfaceHigh, IdrPalette.surface, IdrPalette.surfaceHigh)
                )
            ),
    )
}

@Composable
fun StatePanel(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: IdrStateTone = IdrStateTone.EMPTY,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val accent = when (tone) {
        IdrStateTone.LOADING -> IdrTone.INFO
        IdrStateTone.EMPTY -> IdrTone.NEUTRAL
        IdrStateTone.ERROR -> IdrTone.DANGER
    }
    val glyph = when (tone) {
        IdrStateTone.LOADING -> IdrGlyph.SATELLITE
        IdrStateTone.EMPTY -> IdrGlyph.INFO
        IdrStateTone.ERROR -> IdrGlyph.WARNING
    }
    IdrCard(modifier, emphasis = IdrEmphasis.UTILITY) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IdrIcon(glyph, tint = accent.color, size = IdrSize.icon)
            Spacer(Modifier.width(IdrSpace.sm))
            Text(
                title,
                color = IdrPalette.textPrimary,
                style = IdrType.titleMedium,
                modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
            )
        }
        Text(message, color = IdrPalette.textSecondary, style = IdrType.bodySmall)
        if (tone == IdrStateTone.LOADING) {
            Column(verticalArrangement = Arrangement.spacedBy(IdrSpace.sm)) {
                PlaceholderBlock(height = 10.dp)
                PlaceholderBlock(Modifier.fillMaxWidth(0.6f), height = 10.dp)
            }
        }
        if (actionLabel != null && onAction != null) {
            IdrButton(actionLabel, onAction, variant = IdrButtonVariant.SECONDARY)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Floating navigation dock
// ---------------------------------------------------------------------------------------------

data class DockItem(val id: String, val label: String, val glyph: IdrGlyph, val testTag: String)

/**
 * Floating translucent dock. The active indicator is a single surface that slides between
 * equal-width items, so changing destination reads as movement rather than a redraw.
 */
@Composable
fun IdrDock(
    items: List<DockItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalIdrReducedMotion.current
    if (items.isEmpty()) return
    val shape = IdrShapes.pill
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(66.dp)
            .shadow(24.dp, shape, clip = false)
            .clip(shape)
            .background(IdrPalette.glassStrong, shape)
            .border(1.dp, IdrPalette.border, shape)
            .padding(IdrSpace.xs),
    ) {
        val itemWidth = maxWidth / items.size
        val index = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        val indicatorOffset by animateDpAsState(
            targetValue = itemWidth * index,
            animationSpec = IdrMotion.tweenSpec(IdrMotion.baseMs, reduced),
            label = "dockIndicator",
        )
        Box(
            Modifier
                // Lambda overload: the animated position is read during layout, so following it
                // never recomposes the dock's item row.
                .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                .width(itemWidth)
                .fillMaxHeight()
                .clip(shape)
                .background(IdrPalette.accent.copy(alpha = 0.14f)),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEach { item ->
                val selected = item.id == selectedId
                val tone by animateColorAsState(
                    if (selected) IdrPalette.accent else IdrPalette.textMuted,
                    IdrMotion.tweenSpec(IdrMotion.fastMs, reduced), label = "dockTone",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(item.id) }
                        .testTag(item.testTag)
                        .semantics { role = Role.Tab },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    IdrIcon(item.glyph, tint = tone, size = 19.dp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label.uppercase(),
                        color = tone,
                        style = IdrType.dockLabel,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
