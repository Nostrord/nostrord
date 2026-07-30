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
import org.nostr.nostrord.ui.screens.group.ReactionChip
import org.nostr.nostrord.ui.screens.group.ThreadsViewModel
import org.nostr.nostrord.ui.screens.group.buildThreadSummaries
import org.nostr.nostrord.ui.screens.group.filterMutedReactions
import org.nostr.nostrord.ui.screens.group.friendlyRelayError
import org.nostr.nostrord.ui.screens.group.threadParentIdTag
import org.nostr.nostrord.ui.screens.group.threadRootIdTag
import org.nostr.nostrord.ui.screens.group.topReactionChips
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
    fun `a NIP-7D title tag names the thread when there is no subject`() {
        val msg = NostrGroupClient.NostrMessage(
            id = "a",
            pubkey = "p",
            content = "body text",
            createdAt = 1,
            kind = 11,
            tags = listOf(listOf("title", "From another client")),
        )
        val s = buildThreadSummaries(listOf(msg), emptyList()).single()
        assertEquals("From another client", s.title)
    }

    @Test
    fun `preview and fallback title end in an ellipsis when the content is cut`() {
        val long = "x".repeat(200)
        val s = buildThreadSummaries(listOf(root("a", 1, long)), emptyList()).single()
        assertEquals("x".repeat(140) + "...", s.preview)
        assertEquals("x".repeat(80) + "...", s.title)
        // Exactly at the cap: nothing was cut, no ellipsis.
        val exact = buildThreadSummaries(listOf(root("b", 1, "y".repeat(140))), emptyList()).single()
        assertEquals("y".repeat(140), exact.preview)
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

    @Test
    fun `threadParentIdTag reads only the lowercase e tag of a nested reply`() {
        val nested = NostrGroupClient.NostrMessage(
            id = "n",
            pubkey = "p",
            content = "re",
            createdAt = 1,
            kind = 1111,
            tags = listOf(listOf("E", "rootid", "", "rootpk"), listOf("e", "parentid", "", "parentpk")),
        )
        assertEquals("rootid", nested.threadRootIdTag())
        assertEquals("parentid", nested.threadParentIdTag())
        // A top-level reply carries only the uppercase root scope.
        assertNull(reply("r1", "rootid", "p", 1).threadParentIdTag())
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
    fun `createThread with shareToChat announces a kind9 with the thread nevent`() = runTest {
        val fake = FakeNostrRepository()
        var announced: String? = null
        fake.sendMessageAction = { _, content, _, _, _ ->
            announced = content
            Result.Success(Unit)
        }
        val vm = ThreadsViewModel(fake, "g1")
        vm.createThread("My title", "body", shareToChat = true)
        testDispatcher.scheduler.advanceUntilIdle()
        val text = announced ?: error("no chat announcement sent")
        assertTrue(text.startsWith("Started a thread: My title\nnostr:nevent1"), text)

        // Opting out sends nothing.
        announced = null
        vm.createThread("Quiet", "body", shareToChat = false)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(announced)
    }

    @Test
    fun `deleteThread surfaces the relay rejection as a friendly error`() = runTest {
        val fake = FakeNostrRepository()
        fake.deleteMessageResult = Result.Error(AppError.Group.SendFailed("g1", Exception("blocked: event kind 5 not allowed")))
        val vm = ThreadsViewModel(fake, "g1")
        vm.deleteThread("root1")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("Event kind 5 not allowed", vm.deleteError.value)
        vm.clearDeleteError()
        assertNull(vm.deleteError.value)
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
    fun `topReactionChips ranks by count, caps the list, and shows plus as thumbs`() {
        val byEmoji = mapOf(
            "🎯" to GroupManager.ReactionInfo(emojiUrl = null, reactors = listOf("a", "b", "c")),
            "+" to GroupManager.ReactionInfo(emojiUrl = null, reactors = listOf("a", "b", "c", "d")),
            "🔥" to GroupManager.ReactionInfo(emojiUrl = null, reactors = listOf("a")),
            ":pepe:" to GroupManager.ReactionInfo(emojiUrl = "https://x/pepe.png", reactors = listOf("a", "b")),
        )
        val chips = topReactionChips(byEmoji, maxChips = 3)
        assertEquals(
            listOf(
                ReactionChip("👍", null, 4),
                ReactionChip("🎯", null, 3),
                ReactionChip(":pepe:", "https://x/pepe.png", 2),
            ),
            chips,
        )
        assertTrue(topReactionChips(emptyMap()).isEmpty())
    }

    @Test
    fun `friendlyRelayError strips relay prefixes and capitalizes`() {
        val err = AppError.Auth.SigningFailed(cause = Exception("blocked: not a member"))
        assertEquals("Not a member", friendlyRelayError(err))
    }
}
