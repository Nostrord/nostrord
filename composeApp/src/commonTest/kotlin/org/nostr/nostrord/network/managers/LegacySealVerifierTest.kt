package org.nostr.nostrord.network.managers

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacySealVerifierTest {
    private val author = "a".repeat(64)
    private val announced = "b".repeat(64)
    private val other = "c".repeat(64)

    private fun verifier(
        keys: MutableMap<String, String?>,
        onFetch: () -> Unit = {},
    ) = LegacySealVerifier(
        fetchWaitMs = 3_000L,
        announcedKeyFor = { keys[it] },
        requestAnnouncement = { onFetch() },
    )

    @Test
    fun `a seal matching the cached announcement is accepted without a fetch`() = runTest {
        var fetches = 0
        val verifier = verifier(mutableMapOf(author to announced)) { fetches++ }

        assertTrue(verifier.verify(author, announced))
        assertEquals(0, fetches, "the cached announcement already answers it")
    }

    @Test
    fun `an unknown author is fetched once and then accepted`() = runTest {
        val keys = mutableMapOf<String, String?>()
        var fetches = 0
        val verifier = verifier(keys) {
            fetches++
            keys[author] = announced
        }

        assertTrue(verifier.verify(author, announced))
        assertEquals(1, fetches)
    }

    @Test
    fun `an unresolvable announcement defers instead of deciding`() = runTest {
        var fetches = 0
        val verifier = verifier(mutableMapOf()) { fetches++ }

        // False leaves the wrap unhandled, so the pipeline retries it later. Accepting here would
        // let anyone attribute a message to this author; rejecting would drop it for good.
        assertFalse(verifier.verify(author, announced))
        assertEquals(1, fetches)
    }

    @Test
    fun `a stale cached key forces exactly one re-fetch before deciding`() = runTest {
        // The author rotated: our cache still holds the retired key, and the seal is signed with
        // the new one. One fetch resolves it.
        val keys = mutableMapOf<String, String?>(author to other)
        var fetches = 0
        val verifier = verifier(keys) {
            fetches++
            keys[author] = announced
        }

        assertTrue(verifier.verify(author, announced))
        assertEquals(1, fetches)
    }

    @Test
    fun `a seal that still disagrees after the re-fetch is not accepted`() = runTest {
        var fetches = 0
        val verifier = verifier(mutableMapOf(author to announced)) { fetches++ }

        assertFalse(verifier.verify(author, other))
        assertEquals(1, fetches, "one re-fetch, then a decision: never a fetch loop")
    }

    @Test
    fun `an author who withdrew their key cannot be legacy-verified`() = runTest {
        // A null entry means the latest announcement carries no key at all.
        val verifier = verifier(mutableMapOf(author to null))
        assertFalse(verifier.verify(author, announced))
    }
}
