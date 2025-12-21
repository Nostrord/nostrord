package org.nostr.nostrord.ui.components.scrollbar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VerticalScrollbarWrapper(
    listState: LazyListState,
    modifier: Modifier
) {
    // No-op on iOS - native scrolling handles this
}

@Composable
actual fun VerticalScrollbarWrapper(
    scrollState: ScrollState,
    modifier: Modifier
) {
    // No-op on iOS
}

@Composable
actual fun VerticalScrollbarWrapper(
    gridState: LazyGridState,
    modifier: Modifier
) {
    // No-op on iOS
}
