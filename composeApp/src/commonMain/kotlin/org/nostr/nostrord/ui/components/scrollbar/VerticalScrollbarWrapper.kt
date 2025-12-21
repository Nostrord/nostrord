package org.nostr.nostrord.ui.components.scrollbar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-aware vertical scrollbar wrapper for LazyListState.
 * Renders a scrollbar on desktop/web, no-op on mobile platforms.
 */
@Composable
expect fun VerticalScrollbarWrapper(
    listState: LazyListState,
    modifier: Modifier = Modifier
)

/**
 * Platform-aware vertical scrollbar wrapper for ScrollState.
 */
@Composable
expect fun VerticalScrollbarWrapper(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
)

/**
 * Platform-aware vertical scrollbar wrapper for LazyGridState.
 */
@Composable
expect fun VerticalScrollbarWrapper(
    gridState: LazyGridState,
    modifier: Modifier = Modifier
)
