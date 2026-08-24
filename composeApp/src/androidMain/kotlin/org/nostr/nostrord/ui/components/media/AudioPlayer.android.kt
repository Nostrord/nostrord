package org.nostr.nostrord.ui.components.media

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.nostr.nostrord.ui.media.formatDuration

/** Android playback engine backing [AudioPlayerContent], built on android.media.MediaPlayer. */
private class AndroidAudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var currentUrl: String? = null
    private var positionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    fun play(url: String) {
        if (url != currentUrl) {
            mediaPlayer?.release()
            currentUrl = url
            mediaPlayer =
                MediaPlayer().apply {
                    setDataSource(url)
                    setOnPreparedListener { mp ->
                        _durationMs.value = mp.duration.toLong()
                        mp.start()
                        _isPlaying.value = true
                        startPositionTracking()
                    }
                    setOnCompletionListener {
                        _isPlaying.value = false
                        _currentPositionMs.value = _durationMs.value
                        positionJob?.cancel()
                    }
                    setOnErrorListener { _, _, _ ->
                        _isPlaying.value = false
                        positionJob?.cancel()
                        true
                    }
                    prepareAsync()
                }
        } else {
            mediaPlayer?.let { mp ->
                if (!mp.isPlaying) {
                    // After completion the player sits at the end; rewind first so the play button
                    // replays from the start instead of resuming at the finished position.
                    if (_durationMs.value > 0 && _currentPositionMs.value >= _durationMs.value) {
                        mp.seekTo(0)
                        _currentPositionMs.value = 0L
                    }
                    mp.start()
                    _isPlaying.value = true
                    startPositionTracking()
                }
            }
        }
    }

    fun pause() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                _isPlaying.value = false
                positionJob?.cancel()
            }
        }
    }

    fun release() {
        positionJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        currentUrl = null
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob =
            scope.launch {
                while (isActive) {
                    mediaPlayer?.let { mp ->
                        try {
                            if (mp.isPlaying) {
                                _currentPositionMs.value = mp.currentPosition.toLong()
                            }
                        } catch (_: IllegalStateException) {
                        }
                    }
                    delay(250)
                }
            }
    }
}

@Composable
actual fun AudioPlayerContent(
    url: String,
    modifier: Modifier,
) {
    val player = remember { AndroidAudioPlayer() }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    val isPlaying by player.isPlaying.collectAsState()
    val currentMs by player.currentPositionMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()

    AudioPlayerChrome(
        isPlaying = isPlaying,
        progress = if (durationMs > 0) currentMs.toFloat() / durationMs.toFloat() else 0f,
        positionText = formatDuration(currentMs),
        durationText = if (durationMs > 0) formatDuration(durationMs) else null,
        url = url,
        onToggle = { if (isPlaying) player.pause() else player.play(url) },
        modifier = modifier,
    )
}
