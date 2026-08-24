package org.nostr.nostrord.ui.media

/** A short display name for a media url: last path segment, query stripped, capped. */
fun mediaDisplayName(url: String): String = url.substringAfterLast("/").substringBefore("?").take(40)

/** A position or duration in milliseconds as m:ss. */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val secStr = if (seconds < 10) "0$seconds" else "$seconds"
    return "$minutes:$secStr"
}
