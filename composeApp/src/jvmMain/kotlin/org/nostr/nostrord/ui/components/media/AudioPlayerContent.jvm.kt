package org.nostr.nostrord.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState

/**
 * Desktop audio uses the same kdroidfilter player as the inline video (system GStreamer on
 * Linux, MediaFoundation on Windows, AVPlayer on macOS), so it shares the full system codec
 * set. The previous JavaFX-media engine had a crippled codec set on Linux and played little
 * correctly. No video surface is rendered; an audio-only URL plays through the audio sink.
 */
@Composable
actual fun AudioPlayerContent(
    url: String,
    modifier: Modifier,
) {
    val state = rememberVideoPlayerState()

    // Open paused so duration shows before the user hits play; the player loads metadata.
    LaunchedEffect(url) {
        state.openUri(url, InitialPlayerState.PAUSE)
    }
    // The library doesn't stop on composition exit; pause so leaving the chat releases audio.
    DisposableEffect(url) {
        onDispose { state.pause() }
    }

    // Use the library's own normalized position + formatted text (like the video player), not the
    // raw metadata.duration: a streamed file (e.g. a CDN .wav) can report a bogus duration on the
    // GStreamer/Linux backend (millions of minutes), which the raw value would then display. Hide
    // the duration when it is missing or absurd (> 12h) so the chrome shows just the elapsed time.
    val rawDurationMs = state.metadata.duration ?: 0L
    val durationValid = rawDurationMs in 1..(12L * 60 * 60 * 1000)
    val progress = state.sliderPos / SLIDER_MAX

    AudioPlayerChrome(
        isPlaying = state.isPlaying,
        progress = progress,
        positionText = state.positionText.ifBlank { "0:00" },
        durationText = if (durationValid) state.durationText.ifBlank { null } else null,
        url = url,
        onToggle = {
            if (state.isPlaying) {
                state.pause()
            } else {
                // At end-of-media the slider sits at the end; rewind so play replays instead of
                // doing nothing (slider runs 0..SLIDER_MAX).
                if (state.sliderPos >= SLIDER_MAX - 1f) state.seekTo(0f)
                state.play()
            }
        },
        modifier = modifier,
    )
}

private const val SLIDER_MAX = 1000f
