package org.nostr.nostrord.ui.components.scrollbar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VerticalScrollbarWrapper(
    listState: LazyListState,
    modifier: Modifier
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(listState)
    )
}

@Composable
actual fun VerticalScrollbarWrapper(
    scrollState: ScrollState,
    modifier: Modifier
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(scrollState)
    )
}

@Composable
actual fun VerticalScrollbarWrapper(
    gridState: LazyGridState,
    modifier: Modifier
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(gridState)
    )
}
