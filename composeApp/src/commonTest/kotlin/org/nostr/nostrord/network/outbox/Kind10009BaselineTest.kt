package org.nostr.nostrord.network.outbox

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.OutboxManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val OWNER = "0000000000000000000000000000000000000000000000000000000000baseline"
private const val CIPHERTEXT = "AhFakeNip44Payload=="

/**
 * kind:10009 is a NIP-51 list shared with every other client the user runs: its `content` may
 * hold their self-encrypted private groups and its tags may carry entries this client does not
 * model. A replaceable-event update that rebuilds the event from scratch destroys both, with
 * nothing left to recover from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Kind10009BaselineTest {
    private fun event(
        createdAt: Long,
        content: String,
        tagsJson: String,
        pubkey: String = OWNER,
    ): JsonObject = Json.parseToJsonElement(
        """{"pubkey":"$pubkey","created_at":$createdAt,"kind":10009,"content":"$content","tags":$tagsJson}""",
    ) as JsonObject

    private fun manager(scope: TestScope) = OutboxManager(ConnectionManager(scope), RelayListManager(), scope)

    @Test
    fun `foreign tags are everything this client does not own`() {
        val baseline =
            Kind10009Baseline.from(
                event(
                    100,
                    CIPHERTEXT,
                    """[["group","abc","wss://relay.one"],["r","wss://relay.one"],["title","My groups"],["alt","list"]]""",
                ),
            )

        assertEquals(CIPHERTEXT, baseline.content)
        assertEquals(listOf(listOf("title", "My groups"), listOf("alt", "list")), baseline.foreignTags)
    }

    @Test
    fun `publish tags keep the foreign tags after the owned ones`() {
        val tags =
            kind10009Tags(
                order = listOf("wss://relay.one" to "abc"),
                nip29Relays = listOf("wss://relay.one"),
                foreignTags = listOf(listOf("title", "My groups")),
            )

        assertEquals(
            listOf(
                listOf("group", "abc", "wss://relay.one"),
                listOf("r", "wss://relay.one"),
                listOf("title", "My groups"),
            ),
            tags,
        )
    }

    @Test
    fun `the newest own event wins the baseline`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)

        outbox.handleKind10009Event(event(100, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""), "wss://relay.one", OWNER, {})
        outbox.handleKind10009Event(event(200, "newer", """[["group","abc","wss://relay.one"]]"""), "wss://relay.one", OWNER, {})

        assertEquals("newer", outbox.currentKind10009Baseline().content)
        scope.cancel()
    }

    @Test
    fun `an older event never replaces the baseline`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)

        outbox.handleKind10009Event(event(200, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""), "wss://relay.one", OWNER, {})
        // A relay still serving a superseded version of the replaceable event.
        outbox.handleKind10009Event(event(100, "", """[["group","abc","wss://relay.one"]]"""), "wss://relay.one", OWNER, {})

        assertEquals(CIPHERTEXT, outbox.currentKind10009Baseline().content)
        scope.cancel()
    }

    @Test
    fun `another author's list is not adopted as our baseline`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)

        outbox.handleKind10009Event(
            event(300, CIPHERTEXT, """[["group","abc","wss://relay.one"]]""", pubkey = "someone-else"),
            "wss://relay.one",
            OWNER,
            {},
        )

        assertTrue(outbox.currentKind10009Baseline().content.isEmpty(), "another account's content must never be republished as ours")
        scope.cancel()
    }
}
