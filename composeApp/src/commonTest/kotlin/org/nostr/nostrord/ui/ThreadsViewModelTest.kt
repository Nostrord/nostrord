package org.nostr.nostrord.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.nostr.nostrord.network.FakeNostrRepository
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.screens.group.ThreadsViewModel
import org.nostr.nostrord.ui.screens.group.buildThreadSummaries
import org.nostr.nostrord.ui.screens.group.filterMutedReactions
import org.nostr.nostrord.ui.screens.group.friendlyReactionError
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        // Drain Main-dispatched viewModelScope jobs before resetMain (see GroupViewModelTest).
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun root(id: String, createdAt: Long, content: String, subject: String? = null) = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = "author_$id",
        content = content,
        createdAt = createdAt,
        kind = 11,
        tags = if (subject != null) listOf(listOf("subject", subject)) else emptyList(),
    )

    private fun reply(id: String, rootId: String, pubkey: String, createdAt: Long) = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = pubkey,
        content = "re",
        createdAt = createdAt,
        kind = 1111,
        tags = listOf(listOf("E", rootId, "", "author_$rootId")),
    )

    @Test
    fun `summary counts replies by root takes last activity and subject title`() {
        val roots = listOf(root("a", 100, "Hello\nworld", subject = "My title"))
        val replies = listOf(
            reply("r1", "a", "p1", 150),
            reply("r2", "a", "p1", 120),
            reply("r3", "b", "p2", 999), // belongs to a different root, excluded
        )
        val s = buildThreadSummaries(roots, replies).single()
        assertEquals("a", s.rootId)
        assertEquals(2, s.replyCount)
        assertEquals(150, s.lastActivity)
        assertEquals("My title", s.title)
        assertEquals("Hello", s.preview)
        assertEquals(listOf("p1"), s.replierPubkeys) // deduped
    }

    @Test
    fun `title falls back to the first non-blank content line when no subject`() {
        val s = buildThreadSummaries(listOf(root("a", 1, "\n  First line  \nsecond")), emptyList()).single()
        assertEquals("First line", s.title)
    }

    @Test
    fun `a thread with no replies uses the root timestamp as last activity`() {
        val s = buildThreadSummaries(listOf(root("a", 100, "x")), emptyList()).single()
        assertEquals(0, s.replyCount)
        assertEquals(100, s.lastActivity)
    }

    @Test
    fun `threads sort by last activity newest first`() {
        val roots = listOf(root("a", 100, "a"), root("b", 90, "b"))
        // Only a has a reply -> a is most recent.
        assertEquals(
            listOf("a", "b"),
            buildThreadSummaries(roots, listOf(reply("r1", "a", "p", 110))).map { it.rootId },
        )
        // A newer reply on b floats it above a.
        assertEquals(
            listOf("b", "a"),
            buildThreadSummaries(
                roots,
                listOf(reply("r1", "a", "p", 110), reply("r2", "b", "p", 200)),
            ).map { it.rootId },
        )
    }

    // -------------------------------------------------------------------------
    // Reactions
    // -------------------------------------------------------------------------

    @Test
    fun `sendReaction marks the emoji pending, dedupes in-flight, then clears`() = runTest {
        val fake = FakeNostrRepository()
        val vm = ThreadsViewModel(fake, "g1")
        vm.sendReaction("ev1", "pk1", "🔥")
        vm.sendReaction("ev1", "pk1", "🔥") // in-flight duplicate, ignored
        assertEquals(setOf("ev1|🔥"), vm.pendingReactions.value)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.pendingReactions.value.isEmpty())
        assertEquals(1, fake.calls.count { it.startsWith("sendReaction:g1:ev1") })
        assertNull(vm.reactionError.value)
    }

    @Test
    fun `sendReaction failure surfaces a friendly error and still clears pending`() = runTest {
        val fake = FakeNostrRepository()
        fake.sendReactionResult = Result.Error(AppError.Auth.SigningFailed(cause = Exception("blocked: unknown member")))
        val vm = ThreadsViewModel(fake, "g1")
        vm.sendReaction("ev1", "pk1", "👍")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Unknown member", vm.reactionError.value)
        assertTrue(vm.pendingReactions.value.isEmpty())
        vm.clearReactionError()
        assertNull(vm.reactionError.value)
    }

    @Test
    fun `filterMutedReactions drops muted reactors and empty leftovers`() {
        val raw = mapOf(
            "m1" to mapOf(
                "👍" to GroupManager.ReactionInfo(emojiUrl = null, reactors = listOf("alice", "bob")),
                "🔥" to GroupManager.ReactionInfo(emojiUrl = null, reactors = listOf("bob")),
            ),
            "m2" to mapOf("👍" to GroupManager.ReactionInfo(emojiUrl = null, reactors = listOf("bob"))),
        )
        val filtered = filterMutedReactions(raw, setOf("bob"))
        assertEquals(mapOf("m1" to mapOf("👍" to GroupManager.ReactionInfo(emojiUrl = null, reactors = listOf("alice")))), filtered)
        // No mutes: the raw map passes through untouched.
        assertEquals(raw, filterMutedReactions(raw, emptySet()))
    }

    @Test
    fun `friendlyReactionError strips relay prefixes and capitalizes`() {
        val err = AppError.Auth.SigningFailed(cause = Exception("blocked: not a member"))
        assertEquals("Not a member", friendlyReactionError(err))
    }
}
