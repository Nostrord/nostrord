package org.nostr.nostrord.nostr

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip46DecryptBatcherTest {
    @Test
    fun coalescesConcurrentDecryptsIntoFewBatches() = runTest {
        val batchSizes = mutableListOf<Int>()
        val batcher = Nip46DecryptBatcher { items ->
            batchSizes += items.size
            delay(100)
            items.map { (peer, ciphertext) -> "$peer:$ciphertext" }
        }
        val results = (1..20).map { i ->
            async { batcher.decrypt("p$i", "c$i") }
        }.awaitAll()
        assertEquals((1..20).map { "p$it:c$it" }, results)
        assertEquals(20, batchSizes.sum())
        // The first caller drains a batch of one; everyone who queued while it was
        // in flight rides the next batches (fattening by backpressure).
        assertTrue(batchSizes.size <= 3)
        assertTrue(batchSizes.all { it <= Nip46DecryptBatcher.MAX_BATCH })
    }

    @Test
    fun nullResultMeansItemNotDecryptable() = runTest {
        val batcher = Nip46DecryptBatcher { items -> items.map { null } }
        assertNull(batcher.decrypt("p", "c"))
    }

    @Test
    fun unsupportedSignerFailsTheWholeQueueWithBatchUnsupported() = runTest {
        val batcher = Nip46DecryptBatcher { throw Exception("Unrecognized method: nip44_decrypt_batch") }
        val a = async { runCatching { batcher.decrypt("p1", "c1") } }
        val b = async { runCatching { batcher.decrypt("p2", "c2") } }
        assertTrue(a.await().exceptionOrNull() is Nip46DecryptBatcher.BatchUnsupported)
        assertTrue(b.await().exceptionOrNull() is Nip46DecryptBatcher.BatchUnsupported)
    }

    @Test
    fun otherSendFailuresPropagateWithoutDisablingBatches() = runTest {
        var calls = 0
        val batcher = Nip46DecryptBatcher { items ->
            calls++
            if (calls == 1) throw Exception("rate-limited: you are noting too much")
            items.map { "ok" }
        }
        val first = runCatching { batcher.decrypt("p", "c") }.exceptionOrNull()
        assertTrue(first != null && first !is Nip46DecryptBatcher.BatchUnsupported)
        assertEquals("ok", batcher.decrypt("p", "c"))
    }
}
