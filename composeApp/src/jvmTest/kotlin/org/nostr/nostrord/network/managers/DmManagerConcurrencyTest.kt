package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Wrap arrivals write the seen-on maps from the relay coroutines while the debounced
 * persist snapshots them from its own. Both land on threads of the same pool, and while
 * those maps were plain LinkedHashMaps the overlap threw ConcurrentModificationException
 * out of a coroutine with no handler, killing the process. JVM-only: it needs real
 * parallelism, which the JS Default dispatcher cannot give.
 */
class DmManagerConcurrencyTest {
    @Test
    fun `snapshots survive concurrent wrap arrivals`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dm = DmManager(scope)

        // Link the wraps up front so every arrival merges into relaysByRumor, which is
        // the map seenRelaysSnapshot() iterates.
        val wraps = (0 until 500).associate { "wrap-$it" to "rumor-$it" }
        dm.hydrate(messages = emptyList(), lastRead = emptyMap(), wrapToRumor = wraps)

        val writers =
            (0 until 4).map { w ->
                async(Dispatchers.Default) {
                    wraps.keys.forEach { wrapId -> dm.recordWrapRelay(wrapId, "wss://relay$w.example") }
                }
            }
        val readers =
            (0 until 4).map {
                async(Dispatchers.Default) {
                    repeat(500) {
                        dm.seenRelaysSnapshot().forEach { (_, relays) -> relays.size }
                        dm.wrapToRumorSnapshot().forEach { (_, rumor) -> rumor.length }
                    }
                }
            }
        (writers + readers).awaitAll()

        val seen = dm.seenRelaysSnapshot()
        assertTrue(seen.size == wraps.size, "every rumor kept its seen-on entry")
        assertTrue(seen.values.all { it.size == 4 }, "every writer's relay survived the merges")
        scope.cancel()
    }
}
