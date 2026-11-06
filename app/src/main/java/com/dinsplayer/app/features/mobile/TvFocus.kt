package com.dinsplayer.app.features.mobile

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp

import androidx.compose.foundation.focusable

/**
 * Visible focus ring for D-pad and keyboard navigation.
 *
 * Card surfaces built from `clickable` only ripple on press, so on a TV the user has no way to
 * tell which item the remote is pointing at. This draws a ring while the element holds focus.
 * No effect on touch devices, where focus never lands on these items.
 *
 * Uses `isFocused || hasFocus` so the ring appears both when the element itself is focused and
 * when focus returns from another activity/composable (e.g. back from PlayerActivity).
 */
internal fun Modifier.tvFocusRing(
    shape: Shape = RoundedCornerShape(14.dp),
    color: Color = Color.White,
    width: Dp = 3.dp,
    scaleOnFocus: Float = 1.0f
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && scaleOnFocus != 1.0f) scaleOnFocus else 1.0f,
        label = "tvFocusScale"
    )

    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .then(if (scaleOnFocus != 1.0f) Modifier.scale(scale) else Modifier)
        .border(
            width = if (focused) width else 0.dp,
            color = if (focused) color else Color.Transparent,
            shape = shape
        )
}

/**
 * Scrolls a single-line label that does not fit its slot.
 *
 * Channel names, EPG titles and file names routinely run past the width of a grid cell.
 * `TextOverflow.Ellipsis` cut them with a "…" and the rest was unreachable, so long names were
 * unreadable at 5 columns on wide screens.
 *
 * Pair with `overflow = TextOverflow.Clip`. Ellipsis takes precedence over the marquee and
 * nothing will move.
 */
internal fun Modifier.tvMarquee(): Modifier = basicMarquee()
