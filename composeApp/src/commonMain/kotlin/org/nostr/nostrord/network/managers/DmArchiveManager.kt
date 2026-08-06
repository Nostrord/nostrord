package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.nostr.Event
import kotlin.concurrent.Volatile

/**
 * Republishes already-decrypted DM history to ourselves, addressed to our NIP-4e encryption key,
 * so a device that holds that key loads the whole history without ever calling the signer.
 *
 * Only the messages predating the announcement need it: everything after is already addressed to
 * the encryption key by the sender (inbound) or by our own send path (outbound).
 *
 * Progress is gated on relay acceptance and persisted, so a re-run resumes instead of republishing.
 * Losing the progress set only wastes relay storage: rumor ids are unchanged, so every client
 * (ours and Jumble alike) collapses an archived copy onto the original.
 */
class DmArchiveManager {
    data class Progress(
        val done: Int = 0,
        val total: Int = 0,
        val failed: Int = 0,
        val running: Boolean = false,
        val error: String? = null,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var archivedIds: Set<String> = emptySet()

    @Volatile
    private var cancelRequested = false

    fun hydrate(ids: Set<String>) {
        archivedIds = ids
    }

    fun archivedIds(): Set<String> = archivedIds

    fun clear() {
        archivedIds = emptySet()
        cancelRequested = false
        _progress.value = Progress()
    }

    fun cancel() {
        cancelRequested = true
    }

    /** Rumors still needing an archive copy: not already archived, and older than [announcedAt]. */
    fun pending(rumors: List<Event>, announcedAt: Long): List<Event> = rumors.filter { rumor ->
        val id = rumor.id
        id != null && id !in archivedIds && (announcedAt <= 0L || rumor.createdAt < announcedAt)
    }

    /**
     * Archive [rumors] one at a time. [buildWrap] turns a rumor into the gift wrap to publish
     * (null skips it), [publish] returns whether a relay accepted it, and [persistProgress] is
     * called with the updated id set only after acceptance.
     *
     * Stops with an error after [MAX_CONSECUTIVE_FAILURES] rejections in a row rather than
     * hammering a relay that is refusing on size or rate caps.
     */
    suspend fun run(
        rumors: List<Event>,
        buildWrap: suspend (Event) -> Event?,
        publish: suspend (Event) -> Boolean,
        persistProgress: (Set<String>) -> Unit,
    ) {
        if (_progress.value.running) return
        cancelRequested = false
        _progress.value = Progress(total = rumors.size, running = true)

        var done = 0
        var failed = 0
        var consecutiveFailures = 0
        try {
            for (rumor in rumors) {
                if (cancelRequested) break
                val id = rumor.id ?: continue
                val accepted =
                    try {
                        val wrap = buildWrap(rumor)
                        wrap != null && publish(wrap)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        false
                    }
                if (accepted) {
                    archivedIds = archivedIds + id
                    persistProgress(archivedIds)
                    done++
                    consecutiveFailures = 0
                } else {
                    failed++
                    consecutiveFailures++
                }
                _progress.value = _progress.value.copy(done = done, failed = failed)
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    _progress.value =
                        _progress.value.copy(
                            running = false,
                            error = "Your DM relays stopped accepting the archive. Try again later.",
                        )
                    return
                }
            }
            _progress.value = _progress.value.copy(running = false)
        } finally {
            if (_progress.value.running) _progress.value = _progress.value.copy(running = false)
        }
    }

    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 5
    }
}
