package top.yexkr.findscanner.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import top.yexkr.findscanner.Page
import top.yexkr.findscanner.ScannerState
import top.yexkr.findscanner.backPage

private fun ScannerState.pageKey() = page to (page == Page.Home && inferenceTab)

@Composable
internal fun ScannerPageTransition(state: ScannerState, modifier: Modifier = Modifier, content: @Composable (ScannerState) -> Unit) {
    AnimatedContent(
        targetState = state,
        modifier = modifier.clipToBounds(),
        // Sampling ticks, edited fields, and incoming results only update the current page.
        contentKey = { it.pageKey() },
        transitionSpec = {
            val direction = when {
                initialState.page == Page.Home && targetState.page == Page.Home -> if (targetState.inferenceTab) 1 else -1
                targetState.page == initialState.backPage() -> -1
                else -> 1
            }
            ((fadeIn(tween(180, delayMillis = 40)) +
                slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { direction * (it / 14) }) togetherWith
                (fadeOut(tween(100)) +
                    slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -direction * (it / 28) }))
                .using(null)
        },
        label = "scannerPage"
    ) { displayedState ->
        val outgoing = displayedState.pageKey() != state.pageKey()
        Box(if (outgoing) Modifier.clearAndSetSemantics {} else Modifier) {
            content(displayedState)
            // The fading page remains visible briefly, but must no longer accept clicks.
            if (outgoing) Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            })
        }
    }
}

@Composable
internal fun Modifier.pressFeedback(interactions: MutableInteractionSource): Modifier {
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = tween(if (pressed) 90 else 160, easing = FastOutSlowInEasing),
        label = "pressScale"
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
