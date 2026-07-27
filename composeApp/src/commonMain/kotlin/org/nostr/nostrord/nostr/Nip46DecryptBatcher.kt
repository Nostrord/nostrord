package org.nostr.nostrord.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coalesces concurrent NIP-44 decrypt calls into `nip44_decrypt_batch` requests
 * (one request/response event pair for many ciphertexts). No background job:
 * the first caller to grab the drain lock services the queue for everyone and
 * keeps draining until it is empty; items that pile up while a batch is in
 * flight ride the next one, so batches fatten under load by backpressure alone.
 *
 * A signer without the custom method (or one that requires user approval for
 * it) fails the whole batch with [BatchUnsupported]; the owner then falls back
 * to per-item decrypts for the rest of the session.
 */
class Nip46DecryptBatcher(
    private val maxBatch: Int = MAX_BATCH,
    private val send: suspend (List<Pair<String, String>>) -> List<String?>,
) {
    /** The signer cannot serve batches (unknown method / approval required). */
    class BatchUnsupported(cause: Throwable) : Exception("Signer does not support nip44_decrypt_batch: ${cause.message}", cause)

    private class Item(val peer: String, val ciphertext: String) {
        val result = CompletableDeferred<String?>()
    }

    private val queueLock = Mutex()
    private val queue = ArrayDeque<Item>()
    private val drainLock = Mutex()

    /** Returns the plaintext, or null when the signer could not decrypt this item. */
    suspend fun decrypt(peerPubkey: String, ciphertext: String): String? {
        val item = Item(peerPubkey, ciphertext)
        queueLock.withLock { queue.addLast(item) }
        // The active drainer will take this item; anyone else just awaits. If the
        // drainer was cancelled mid-flight the item stays queued until the next
        // caller drains - the owner's own decrypt timeout bounds that wait.
        while (!item.result.isCompleted) {
            if (drainLock.tryLock()) {
                try {
                    drainAll()
                } finally {
                    drainLock.unlock()
                }
            } else {
                break
            }
        }
        return item.result.await()
    }

    private suspend fun drainAll() {
        while (true) {
            val batch = queueLock.withLock {
                if (queue.isEmpty()) return
                List(minOf(maxBatch, queue.size)) { queue.removeFirst() }
            }
            try {
                val results = send(batch.map { it.peer to it.ciphertext })
                batch.forEachIndexed { i, item -> item.result.complete(results.getOrNull(i)) }
            } catch (e: CancellationException) {
                // The drainer's caller died (its decrypt timeout / scope teardown);
                // fail this batch so its awaiters retry rather than hang.
                batch.forEach { it.result.completeExceptionally(Exception("Batch decrypt aborted", e)) }
                throw e
            } catch (e: Throwable) {
                val wrapped = if (isUnsupportedBatchError(e)) BatchUnsupported(e) else e
                batch.forEach { it.result.completeExceptionally(wrapped) }
                if (wrapped is BatchUnsupported) {
                    // Everything still queued would fail the same way; flush it so
                    // every caller falls back to per-item at once.
                    queueLock.withLock {
                        while (queue.isNotEmpty()) queue.removeFirst().result.completeExceptionally(BatchUnsupported(e))
                    }
                    return
                }
            }
        }
    }

    companion object {
        /**
         * Bounded by relay event-size caps: the response carries every plaintext
         * (seal events, ~1-2KB each) NIP-44-encrypted inside one kind:24133.
         */
        const val MAX_BATCH = 16

        /** Errors that mean "this signer can't serve batches", not "this batch failed". */
        fun isUnsupportedBatchError(e: Throwable): Boolean {
            val msg = e.message?.lowercase() ?: return false
            return msg.contains("unrecognized method") ||
                msg.contains("unknown method") ||
                msg.contains("not supported") ||
                msg.contains("approval required") ||
                msg.contains("invalid batch params")
        }
    }
}
