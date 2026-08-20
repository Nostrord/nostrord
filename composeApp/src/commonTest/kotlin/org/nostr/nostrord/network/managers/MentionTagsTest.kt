package org.nostr.nostrord.network.managers

import org.nostr.nostrord.nostr.Nip19
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MentionTagsTest {
    private val alice = "1".repeat(64)
    private val bob = "2".repeat(64)
    private val anastasia = "3".repeat(64)

    private val candidates = listOf(
        MentionCandidate(alice, listOf("Alice Cooper", "alice")),
        MentionCandidate(bob, listOf("Bob")),
        MentionCandidate(anastasia, listOf("anastasia")),
    )

    @Test
    fun typedNameResolvesWithoutPickingASuggestion() {
        val resolved = MentionTags.resolveTyped("hey @Bob, look", emptyMap(), candidates)
        assertEquals(mapOf("Bob" to bob), resolved)
    }

    @Test
    fun typedNameIsCaseInsensitiveAndIgnoresSpacesInTheName() {
        assertEquals(mapOf("bob" to bob), MentionTags.resolveTyped("@bob", emptyMap(), candidates))
        assertEquals(mapOf("alicecooper" to alice), MentionTags.resolveTyped("@alicecooper hi", emptyMap(), candidates))
    }

    @Test
    fun typedNpubResolves() {
        val npub = Nip19.encodeNpub(bob)
        assertEquals(mapOf(npub to bob), MentionTags.resolveTyped("ping @$npub", emptyMap(), candidates))
    }

    @Test
    fun ambiguousNameStaysPlainText() {
        val twins = listOf(MentionCandidate(alice, listOf("Sam")), MentionCandidate(bob, listOf("sam")))
        assertTrue(MentionTags.resolveTyped("@Sam here", emptyMap(), twins).isEmpty())
    }

    @Test
    fun pickedMentionsAndNonMembersAreLeftAlone() {
        val picked = mapOf("Alice Cooper" to alice)
        val resolved = MentionTags.resolveTyped("@Alice Cooper and @stranger", picked, candidates)
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun midWordAtSignIsNotAMention() {
        assertTrue(MentionTags.resolveTyped("mail me at me@bob.com", emptyMap(), candidates).isEmpty())
    }

    @Test
    fun applyReplacesWholeTokensOnly() {
        val mentions = mapOf("alice" to alice, "anastasia" to anastasia)
        val (content, tags) = MentionTags.apply("@anastasia met @alice", mentions)
        assertEquals("nostr:${Nip19.encodeNpub(anastasia)} met nostr:${Nip19.encodeNpub(alice)}", content)
        assertEquals(listOf(listOf("p", anastasia), listOf("p", alice)), tags.sortedBy { content.indexOf(it[1]) })
    }

    @Test
    fun applyTagsOnlyTheNamesLeftInTheText() {
        val (content, tags) = MentionTags.apply("just @alice now", mapOf("alice" to alice, "Bob" to bob))
        assertEquals("just nostr:${Nip19.encodeNpub(alice)} now", content)
        assertEquals(listOf(listOf("p", alice)), tags)
    }

    @Test
    fun applySkipsPubkeysAlreadyTagged() {
        val (_, tags) = MentionTags.apply("@alice", mapOf("alice" to alice), listOf(listOf("p", alice)))
        assertTrue(tags.isEmpty())
    }
}
