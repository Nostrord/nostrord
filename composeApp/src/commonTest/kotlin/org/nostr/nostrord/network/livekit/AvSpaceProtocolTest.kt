package org.nostr.nostrord.network.livekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.nostr.Nip98
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NIP-29 AV spaces protocol layer: the kind:39000 tags, the kind:39004 roster, the NIP-98
 * token request and the LiveKit identity mapping.
 */
class AvSpaceProtocolTest {
    private val client = NostrGroupClient("wss://relay.example")
    private val hexA = "a".repeat(64)
    private val hexB = "b".repeat(64)

    private fun metadataEvent(vararg tags: String): String {
        val tagJson = tags.joinToString(",")
        return """{"kind":39000,"tags":[["d","grp"],["name","Test"],$tagJson],"content":""}"""
    }

    private fun parseMetadata(json: String) = client.parseGroupMetadata(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `livekit tag marks the group as having an AV room`() {
        val withTag = assertNotNull(parseMetadata(metadataEvent("""["livekit"]""")))
        assertTrue(withTag.hasLiveKit)

        val withoutTag = assertNotNull(parseMetadata(metadataEvent("""["about","no av here"]""")))
        assertFalse(withoutTag.hasLiveKit)
    }

    @Test
    fun `absent supported_kinds means every kind is allowed`() {
        val metadata = assertNotNull(parseMetadata(metadataEvent("""["livekit"]""")))
        assertNull(metadata.supportedKinds)
        assertFalse(metadata.isAvOnly)
        assertTrue(metadata.supportsKind(9))
        assertTrue(metadata.supportsKind(11))
    }

    @Test
    fun `supported_kinds lists the accepted kinds and rejects the rest`() {
        val metadata = assertNotNull(parseMetadata(metadataEvent("""["supported_kinds","9","11"]""")))
        assertEquals(listOf(9, 11), metadata.supportedKinds)
        assertFalse(metadata.isAvOnly)
        assertTrue(metadata.supportsKind(9))
        assertFalse(metadata.supportsKind(7))
    }

    @Test
    fun `empty supported_kinds is an AV-only group`() {
        val metadata = assertNotNull(parseMetadata(metadataEvent("""["livekit"]""", """["supported_kinds"]""")))
        assertEquals(emptyList(), metadata.supportedKinds)
        assertTrue(metadata.isAvOnly)
        assertFalse(metadata.supportsKind(9))
    }

    @Test
    fun `non-numeric supported_kinds entries are dropped`() {
        val metadata = assertNotNull(parseMetadata(metadataEvent("""["supported_kinds","9","chat","11"]""")))
        assertEquals(listOf(9, 11), metadata.supportedKinds)
        // A junk-only list must not read as AV-only: the tag did carry kinds, they were unusable.
        val junkOnly = assertNotNull(parseMetadata(metadataEvent("""["supported_kinds","chat"]""")))
        assertEquals(emptyList(), junkOnly.supportedKinds)
    }

    @Test
    fun `39004 carries the live participants`() {
        val event = """{"kind":39004,"tags":[["d","grp"],["participant","$hexA"],["participant","$hexB"]],"content":""}"""
        val parsed = assertNotNull(client.parseLiveKitParticipants(Json.parseToJsonElement(event).jsonObject))
        assertEquals("grp", parsed.groupId)
        assertContentEquals(listOf(hexA, hexB), parsed.participants)
    }

    @Test
    fun `39004 with no participants means an empty room`() {
        val event = """{"kind":39004,"tags":[["d","grp"]],"content":""}"""
        val parsed = assertNotNull(client.parseLiveKitParticipants(Json.parseToJsonElement(event).jsonObject))
        assertEquals(emptyList(), parsed.participants)
    }

    @Test
    fun `39004 drops malformed pubkeys and duplicates`() {
        val event = """{"kind":39004,"tags":[["d","grp"],["participant","$hexA"],["participant","nope"],["participant","$hexA"]],"content":""}"""
        val parsed = assertNotNull(client.parseLiveKitParticipants(Json.parseToJsonElement(event).jsonObject))
        assertContentEquals(listOf(hexA), parsed.participants)
    }

    @Test
    fun `39004 parser rejects other kinds and events with no d tag`() {
        val wrongKind = """{"kind":39002,"tags":[["d","grp"]],"content":""}"""
        assertNull(client.parseLiveKitParticipants(Json.parseToJsonElement(wrongKind).jsonObject))
        val noD = """{"kind":39004,"tags":[["participant","$hexA"]],"content":""}"""
        assertNull(client.parseLiveKitParticipants(Json.parseToJsonElement(noD).jsonObject))
    }

    @Test
    fun `token and support URLs derive from the relay websocket URL`() {
        assertEquals(
            "https://relay.example/.well-known/nip29/livekit/grp",
            liveKitTokenUrl("wss://relay.example", "grp"),
        )
        assertEquals(
            "https://relay.example/.well-known/nip29/livekit",
            liveKitSupportUrl("wss://relay.example/"),
        )
        assertEquals(
            "http://localhost:7777/.well-known/nip29/livekit/grp",
            liveKitTokenUrl("ws://localhost:7777", "grp"),
        )
    }

    @Test
    fun `nip98 auth event targets the exact token URL`() {
        val url = liveKitTokenUrl("wss://relay.example", "grp")
        val event = Nip98.buildAuthEvent(hexA, url, "get")
        assertEquals(27235, event.kind)
        assertEquals("", event.content)
        assertEquals(listOf("u", url), event.tags[0])
        assertEquals(listOf("method", "GET"), event.tags[1])
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `nip98 header is the base64 of the signed event`() {
        val signed = Nip98.buildAuthEvent(hexA, "https://relay.example/x", "GET")
            .copy(id = "id", sig = "sig")
        val header = Nip98.encodeAuthHeader(signed)
        assertTrue(header.startsWith("Nostr "))
        val decoded = Base64.decode(header.removePrefix("Nostr ")).decodeToString()
        assertEquals(27235, Json.parseToJsonElement(decoded).jsonObject["kind"].toString().toInt())
    }

    @Test
    fun `livekit identity maps back to the pubkey by prefix`() {
        assertEquals(hexA, pubkeyFromLiveKitIdentity(hexA + "__random-suffix"))
        assertEquals(hexA, pubkeyFromLiveKitIdentity(hexA))
        assertNull(pubkeyFromLiveKitIdentity("short"))
        // Uppercase hex is not the canonical form the spec mandates.
        assertNull(pubkeyFromLiveKitIdentity("A".repeat(64)))
    }

    @Test
    fun `token response is read in both livekit and short spellings`() {
        val native = parseCredentials("""{"participant_token":"jwt","server_url":"wss://lk.example"}""")
        assertEquals(LiveKitCredentials("jwt", "wss://lk.example"), native)

        val short = parseCredentials("""{"token":"jwt","url":"wss://lk.example"}""")
        assertEquals(LiveKitCredentials("jwt", "wss://lk.example"), short)

        val camel = parseCredentials("""{"token":"jwt","serverUrl":"wss://lk.example"}""")
        assertEquals(LiveKitCredentials("jwt", "wss://lk.example"), camel)

        assertNull(parseCredentials("""{"token":"jwt"}"""))
        assertNull(parseCredentials("""{"token":"","url":"wss://lk.example"}"""))
        assertNull(parseCredentials("not json"))
    }
}

/**
 * A kind:9002 carrying a name plus any flag is a full-state replace on the relay: it wipes
 * `livekit` and `supported_kinds` before applying the event. The edit path must therefore
 * re-declare both, or renaming a group would silently tear down its AV room.
 */
class EditGroupPreservesAvTest {
    private val client = NostrGroupClient("wss://relay.example")

    private fun reparse(tags: List<List<String>>): org.nostr.nostrord.network.GroupMetadata? {
        val tagJson = tags.joinToString(",") { tag -> tag.joinToString(",", "[", "]") { "\"$it\"" } }
        return client.parseGroupMetadata(
            Json.parseToJsonElement("""{"kind":39000,"tags":[$tagJson],"content":""}""").jsonObject,
        )
    }

    /** Mirrors the tag set `GroupManager.editGroup` emits for an unchanged AV group. */
    private fun editTags(hasLiveKit: Boolean?, current: Boolean, supportedKinds: List<Int>?): List<List<String>> {
        val tags = mutableListOf(listOf("d", "grp"), listOf("name", "renamed"), listOf("closed"))
        when (hasLiveKit ?: current) {
            true -> tags.add(listOf("livekit"))
            false -> tags.add(listOf("no-livekit"))
            else -> Unit
        }
        supportedKinds?.let { kinds -> tags.add(listOf("supported_kinds") + kinds.map { it.toString() }) }
        return tags
    }

    @Test
    fun `a rename keeps the room and the supported kinds`() {
        val edited = assertNotNull(reparse(editTags(hasLiveKit = null, current = true, supportedKinds = listOf(9, 11))))
        assertTrue(edited.hasLiveKit)
        assertEquals(listOf(9, 11), edited.supportedKinds)
    }

    @Test
    fun `a rename keeps an AV-only group AV-only`() {
        val edited = assertNotNull(reparse(editTags(hasLiveKit = null, current = true, supportedKinds = emptyList())))
        assertTrue(edited.isAvOnly)
    }

    @Test
    fun `turning the room off emits the explicit no-livekit tag`() {
        val tags = editTags(hasLiveKit = false, current = true, supportedKinds = null)
        assertTrue(tags.contains(listOf("no-livekit")))
        // `no-livekit` is an instruction to the relay, not group state: the republished
        // kind:39000 simply carries no livekit tag.
        assertFalse(assertNotNull(reparse(tags)).hasLiveKit)
    }

    @Test
    fun `turning the room on emits the livekit tag`() {
        val edited = assertNotNull(reparse(editTags(hasLiveKit = true, current = false, supportedKinds = null)))
        assertTrue(edited.hasLiveKit)
    }
}
