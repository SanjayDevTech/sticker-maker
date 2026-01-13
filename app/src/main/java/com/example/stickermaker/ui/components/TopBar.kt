package com.example.stickermaker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Motion

/**
 * Collapsing top bar. Screens show their title as a huge headline at the top of the list;
 * once that scrolls away ([collapsed]) the small centred title drops in and an ink rule
 * appears under the bar. Use [rememberCollapsed] to derive the flag from a list.
 */
@Composable
fun StickerTopBar(
    title: String,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val ruleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, Motion.effects(), label = "rule")
    Column(modifier.fillMaxWidth().background(colors.canvas)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Dimens.ScreenGutter, vertical = 10.dp)
                .height(Dimens.IconTile + Dimens.ShadowS),
        ) {
            Row(Modifier.align(Alignment.CenterStart)) {
                navigation?.invoke() ?: Spacer(Modifier.size(Dimens.IconTile))
            }
            CollapsedTitle(title, collapsed, Modifier.align(Alignment.Center))
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = { actions?.invoke(this) },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(Dimens.Border)
                .graphicsLayer { alpha = ruleAlpha }
                .background(colors.ink),
        )
    }
}

@Composable
private fun CollapsedTitle(title: String, visible: Boolean, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(Motion.effects()) + slideInVertically(Motion.spatialBouncy()) { it / 2 },
        exit = fadeOut(Motion.effects()) + slideOutVertically(Motion.spatialFast()) { it / 2 },
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
    }
}

/** True once the list has scrolled past its first ~[thresholdDp] of content. */
@Composable
fun rememberCollapsed(listState: LazyListState, thresholdDp: Int = 72): Boolean {
    val threshold = with(LocalDensity.current) { thresholdDp.dp.roundToPx() }
    val collapsed by remember(listState, threshold) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold
        }
    }
    return collapsed
}
