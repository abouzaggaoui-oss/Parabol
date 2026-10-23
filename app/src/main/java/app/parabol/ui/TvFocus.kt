package app.parabol.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp

/*
 * D-pad / TV-remote focus handling.
 *
 * Every interactive element must have exactly ONE focus target. The previous
 * implementation added an extra `.focusable()` after `.clickable()`, which gave
 * each element two focus stops: the remote could land on the extra (non-clickable)
 * one, after which OK did nothing. Now the focus ring only *observes* the focus
 * state of the clickable's own InteractionSource and never adds a focus node.
 */

/** Draws a focus ring + slight scale-up while [interactionSource] is focused. Adds no focus node. */
@Composable
fun Modifier.tvFocusIndicator(interactionSource: InteractionSource, cornerRadius: Int = 8): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    // Only draw the ring for remote/keyboard navigation, never on a touch screen.
    val isFocused = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1f, label = "tvFocusScale")
    val ringColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent
    return this
        .scale(scale)
        .border(width = 2.dp, color = ringColor, shape = RoundedCornerShape(cornerRadius.dp))
}

/**
 * Clickable with a visible TV focus ring, as a single focus target. Use instead of `.clickable{}`.
 * [onLongClick] is optional (touch long-press, e.g. to toggle a favorite).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvClickable(
    cornerRadius: Int = 8,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .tvFocusIndicator(interactionSource, cornerRadius)
        // focusRequester must come before the focus target (clickable) it controls.
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        // Keep the focus/press highlight inside the rounded shape (no square corners).
        .clip(RoundedCornerShape(cornerRadius.dp))
        .combinedClickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onLongClick = onLongClick,
            onClick = onClick
        )
}

/** IconButton with a visible TV focus ring, sharing the button's own focus target. */
@Composable
fun TvIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.tvFocusIndicator(interactionSource, cornerRadius = 24),
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Gives the first item of a list the initial focus once it is on screen, so a remote starts
 * on the first group/channel instead of in the search field. Requested only once per screen,
 * so typing in the search field later never pulls focus away from it.
 */
@Composable
fun rememberFirstItemFocus(ready: Boolean): FocusRequester {
    val requester = remember { FocusRequester() }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (ready && !done) {
            withFrameNanos { } // wait until the first item is laid out and attached
            done = runCatching { requester.requestFocus() }.isSuccess
        }
    }
    return requester
}

/** Requests focus once the target is laid out; silently ignores a target that isn't attached. */
suspend fun FocusRequester.requestAfterLayout() {
    withFrameNanos { }
    runCatching { requestFocus() }
}
