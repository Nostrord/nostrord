package org.nostr.nostrord.network.outbox

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.OutboxManager
import org.nostr.nostrord.nostr.Nip51
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `private group entries are read from the encrypted section`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)
        val private = Nip51.encodeTags(listOf(listOf("group", "secret", "wss://relay.two")))

        outbox.handleKind10009Event(
            event(100, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""),
            "wss://relay.one",
            OWNER,
            {},
            decryptPrivate = { if (it == CIPHERTEXT) private else null },
        )

        assertEquals(setOf("wss://relay.two" to "secret"), outbox.privateGroupEntries.value)
        assertEquals(setOf("wss://relay.one", "wss://relay.two"), outbox.kind10009Relays.value)
        scope.cancel()
    }

    @Test
    fun `a group listed in both sections stays public`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)
        val private = Nip51.encodeTags(listOf(listOf("group", "abc", "wss://relay.one")))

        outbox.handleKind10009Event(
            event(100, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""),
            "wss://relay.one",
            OWNER,
            {},
            decryptPrivate = { private },
        )

        // Demoting it would drop the group from the public tags the user already advertises.
        assertTrue(outbox.privateGroupEntries.value.isEmpty())
        scope.cancel()
    }

    @Test
    fun `an unreadable private section is flagged, not discarded`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)

        outbox.handleKind10009Event(
            event(100, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""),
            "wss://relay.one",
            OWNER,
            {},
            // NIP-04-era content, or a signer that refuses to decrypt.
            decryptPrivate = { null },
        )

        assertTrue(outbox.privateSectionOpaque.value)
        assertEquals(CIPHERTEXT, outbox.currentKind10009Baseline().content, "the ciphertext must survive for the next publish")
        scope.cancel()
    }

    @Test
    fun `a failed decrypt keeps the known private entries out of the public tags`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)
        val private = Nip51.encodeTags(listOf(listOf("group", "secret", "wss://relay.two")))

        outbox.handleKind10009Event(
            event(100, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""),
            "wss://relay.one",
            OWNER,
            {},
            decryptPrivate = { private },
        )
        // A newer version arrives while the signer is offline or refuses.
        outbox.handleKind10009Event(
            event(200, "AhRotatedCiphertext==", """[["group","abc","wss://relay.one"]]"""),
            "wss://relay.one",
            OWNER,
            {},
            decryptPrivate = { null },
        )

        // Forgetting them would publish the group the user hid as a public tag.
        assertEquals(setOf("wss://relay.two" to "secret"), outbox.privateGroupEntries.value)
        scope.cancel()
    }

    @Test
    fun `a publish never writes a private group or a private-only relay in the clear`() {
        val publish =
            buildKind10009Publish(
                joinedOrder = listOf("wss://relay.one" to "abc", "wss://relay.two" to "secret"),
                privateEntries = setOf("wss://relay.two" to "secret"),
                nip29Relays = listOf("wss://relay.one", "wss://relay.two"),
                privateOnlyRelays = setOf("wss://relay.two"),
                foreignTags = listOf(listOf("title", "My groups")),
            )

        assertEquals(
            listOf(
                listOf("group", "abc", "wss://relay.one"),
                listOf("r", "wss://relay.one"),
                listOf("title", "My groups"),
            ),
            publish.tags,
        )
        assertEquals(listOf("wss://relay.two" to "secret"), publish.privateOrder)
    }

    @Test
    fun `the private section is only re-encrypted when its groups changed`() {
        val current = listOf(listOf("group", "secret", "wss://relay.two"), listOf("word", "spoiler"))

        // Same groups, same tags: the publish carries the previous ciphertext untouched, which is
        // what keeps another client's section from being rewritten on every unrelated publish.
        assertEquals(current, rebuildPrivateGroupTags(current, listOf("wss://relay.two" to "secret")))
        assertTrue(rebuildPrivateGroupTags(current, emptyList()) != current, "leaving the group must rewrite the section")
    }

    @Test
    fun `no publish may replace the list before the fetch settles`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)

        assertFalse(outbox.kind10009BaselineSettled.value, "nothing has been seen yet: a publish would wipe the content")

        outbox.handleKind10009Event(event(100, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""), "wss://relay.one", OWNER, {})

        assertTrue(outbox.kind10009BaselineSettled.value)
        scope.cancel()
    }

    @Test
    fun `rebuilding the private section keeps the other client's non-group tags`() {
        val previous =
            listOf(
                listOf("group", "gone", "wss://relay.two"),
                listOf("word", "spoiler"),
                listOf("r", "wss://relay.three"),
            )

        val rebuilt = rebuildPrivateGroupTags(previous, listOf("wss://relay.two" to "kept"))

        assertEquals(
            listOf(
                listOf("group", "kept", "wss://relay.two"),
                listOf("word", "spoiler"),
                listOf("r", "wss://relay.three"),
            ),
            rebuilt,
        )
    }

    @Test
    fun `a private entry reaches the joined map the rail is built from`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)
        // The real shape: many public groups across relays, the private one on a relay that only
        // appears as an `r` tag.
        val publicTags =
            """[["group","nostrord","wss://chat.wisp.talk"],["group","lotus","wss://groups.0xchat.com"],""" +
                """["r","wss://chat.wisp.talk"],["r","wss://groups.0xchat.com"],["r","wss://relay.groups.nip29.com"]]"""
        val private = Nip51.encodeTags(listOf(listOf("group", "29", "wss://relay.groups.nip29.com")))

        var relayGroups: Map<String, Set<String>> = emptyMap()
        outbox.handleKind10009Event(
            event(1786035415, CIPHERTEXT, publicTags),
            "wss://chat.wisp.talk",
            OWNER,
            {},
            onRelayGroupsUpdated = { relayGroups = it },
            decryptPrivate = { private },
        )

        assertEquals(setOf("29"), relayGroups["wss://relay.groups.nip29.com"], "the private group must reach the rail")
        scope.cancel()
    }

    @Test
    fun `the same event from many relays decrypts once`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)
        val private = Nip51.encodeTags(listOf(listOf("group", "secret", "wss://relay.two")))
        var decrypts = 0
        val relays = listOf("wss://relay.one", "wss://relay.two", "wss://relay.three", "wss://relay.four")

        // Every connected relay delivers the same own kind:10009.
        relays.forEach { relay ->
            outbox.handleKind10009Event(
                event(100, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""),
                relay,
                OWNER,
                {},
                decryptPrivate = {
                    decrypts++
                    private
                },
            )
        }

        // One round-trip, not one per relay: a remote signer answered 17 identical requests for
        // the same ciphertext and timed them all out.
        assertEquals(1, decrypts)
        scope.cancel()
    }

    @Test
    fun `a group stays in the list when the signer cannot read the private section`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)
        val private = Nip51.encodeTags(listOf(listOf("group", "secret", "wss://relay.two")))

        outbox.handleKind10009Event(
            event(100, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""),
            "wss://relay.one",
            OWNER,
            {},
            decryptPrivate = { private },
        )
        // The list is republished (rotated ciphertext) while the signer is unreachable.
        outbox.handleKind10009Event(
            event(200, "AhRotatedCiphertext==", """[["group","abc","wss://relay.one"]]"""),
            "wss://relay.one",
            OWNER,
            {},
            decryptPrivate = { null },
        )

        // Losing it here would delete the group from the rail AND from its stored slot, because
        // the signer happened to be slow.
        assertEquals(setOf("secret"), outbox.getJoinedGroupsForRelay("wss://relay.two"))
        assertTrue(outbox.privateSectionOpaque.value)
        scope.cancel()
    }

    @Test
    fun `a private group keeps its place instead of falling to the end`() {
        val a = "wss://r" to "a"
        val b = "wss://r" to "b"
        val c = "wss://r" to "c"
        val secret = "wss://r" to "secret"
        // The user dragged the private group between a and b.
        val local = listOf(a, secret, b, c)

        assertEquals(listOf(a, secret, b, c), mergeGroupOrder(listOf(a, b, c), listOf(secret), local))
    }

    @Test
    fun `a private group follows its neighbour when another device reorders`() {
        val a = "wss://r" to "a"
        val b = "wss://r" to "b"
        val c = "wss://r" to "c"
        val secret = "wss://r" to "secret"
        val local = listOf(a, secret, b, c)

        // Another device moved `a` to the end; the private entry travels with it rather than
        // staying at an absolute index that no longer means anything.
        assertEquals(listOf(b, c, a, secret), mergeGroupOrder(listOf(b, c, a), listOf(secret), local))
    }

    @Test
    fun `a private group above everything stays on top`() {
        val a = "wss://r" to "a"
        val secret = "wss://r" to "secret"

        assertEquals(listOf(secret, a), mergeGroupOrder(listOf(a), listOf(secret), listOf(secret, a)))
    }

    @Test
    fun `a private group with no local position goes last`() {
        val a = "wss://r" to "a"
        val b = "wss://r" to "b"
        val fresh = "wss://r" to "fresh"

        // Added privately on another device: nothing here knows where the user wants it.
        assertEquals(listOf(a, b, fresh), mergeGroupOrder(listOf(a, b), listOf(fresh), listOf(a, b)))
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

    @Test
    fun `a read private section is stamped with the ciphertext it came from`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)
        val private = Nip51.encodeTags(listOf(listOf("group", "secret", "wss://relay.two")))

        outbox.handleKind10009Event(
            event(100, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""),
            "wss://relay.one",
            OWNER,
            {},
            decryptPrivate = { private },
        )

        // The next session reads this instead of opening a signer dialog for a ciphertext
        // whose plaintext is already on disk.
        val baseline = outbox.currentKind10009Baseline()
        assertEquals(CIPHERTEXT, baseline.privateDecryptedFrom)
        assertEquals(listOf(listOf("group", "secret", "wss://relay.two")), baseline.readablePrivateTags)
        scope.cancel()
    }

    @Test
    fun `a section the signer refused is not stamped`() = runTest {
        val scope = TestScope(testScheduler)
        val outbox = manager(scope)

        outbox.handleKind10009Event(
            event(100, CIPHERTEXT, """[["group","abc","wss://relay.one"]]"""),
            "wss://relay.one",
            OWNER,
            {},
            decryptPrivate = { null },
        )

        assertEquals(null, outbox.currentKind10009Baseline().readablePrivateTags)
        scope.cancel()
    }

    @Test
    fun `a stamp from a replaced version is not trusted`() {
        val read =
            Kind10009Baseline(
                content = CIPHERTEXT,
                privateTags = listOf(listOf("group", "secret", "wss://relay.two")),
                privateDecryptedFrom = CIPHERTEXT,
            )
        assertEquals(read.privateTags, read.readablePrivateTags)

        // Another client republished the list: the plaintext on disk describes the section
        // that version replaced, so the signer has to read the new one.
        assertEquals(null, read.copy(content = "AhDifferentPayload==").readablePrivateTags)
        assertEquals(null, read.copy(privateDecryptedFrom = "").readablePrivateTags)
    }
}
