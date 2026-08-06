package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun AvVideoSurface(
    identity: String?,
    hasVideo: Boolean,
    attach: (Any) -> Boolean,
    detach: (Any) -> Unit,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) = VideoFrameSurface(identity, hasVideo, attach, detach, modifier, fallback)
