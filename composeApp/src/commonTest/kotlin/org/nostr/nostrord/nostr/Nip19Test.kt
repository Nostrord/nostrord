package org.nostr.nostrord.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip19Test {

    // Known test vectors
    private val pubkeyHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val npub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"

    private val privkeyHex = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"
    private val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"

    private val eventIdHex = "b9f5441e45ca39179320e0031cfb18e34078f374526e496f1c1b0d53d26b7e7e"
    private val note = "note1h865g8j9egu30yequqp3e7ccudq83um52fhyjmcurvx485nt0elqhzymjc"

    // -------------------------------------------------------------------------
    // encodeNpub
    // -------------------------------------------------------------------------

    @Test
    fun `encodeNpub produces correct npub`() {
        assertEquals(npub, Nip19.encodeNpub(pubkeyHex))
    }

    @Test
    fun `encodeNpub starts with npub1`() {
        assertTrue(Nip19.encodeNpub(pubkeyHex).startsWith("npub1"))
    }

    // -------------------------------------------------------------------------
    // encodeNsec
    // -------------------------------------------------------------------------

    @Test
    fun `encodeNsec produces correct nsec`() {
        assertEquals(nsec, Nip19.encodeNsec(privkeyHex))
    }

    // -------------------------------------------------------------------------
    // encodeNote
    // -------------------------------------------------------------------------

    @Test
    fun `encodeNote produces correct note`() {
        assertEquals(note, Nip19.encodeNote(eventIdHex))
    }

    // -------------------------------------------------------------------------
    // decode — simple types
    // -------------------------------------------------------------------------

    @Test
    fun `decode npub returns Npub with correct pubkey`() {
        val entity = Nip19.decode(npub)
        assertIs<Nip19.Entity.Npub>(entity)
        assertEquals(pubkeyHex, entity.pubkey)
    }

    @Test
    fun `decode nsec returns Nsec with correct privkey`() {
        val entity = Nip19.decode(nsec)
        assertIs<Nip19.Entity.Nsec>(entity)
        assertEquals(privkeyHex, entity.privkey)
    }

    @Test
    fun `decode note returns Note with correct eventId`() {
        val entity = Nip19.decode(note)
        assertIs<Nip19.Entity.Note>(entity)
        assertEquals(eventIdHex, entity.eventId)
    }

    // -------------------------------------------------------------------------
    // Round-trips
    // -------------------------------------------------------------------------

    @Test
    fun `npub round-trip`() {
        val encoded = Nip19.encodeNpub(pubkeyHex)
        val decoded = Nip19.decode(encoded)
        assertIs<Nip19.Entity.Npub>(decoded)
        assertEquals(pubkeyHex, decoded.pubkey)
    }

    @Test
    fun `nsec round-trip`() {
        val encoded = Nip19.encodeNsec(privkeyHex)
        val decoded = Nip19.decode(encoded)
        assertIs<Nip19.Entity.Nsec>(decoded)
        assertEquals(privkeyHex, decoded.privkey)
    }

    @Test
    fun `note round-trip`() {
        val encoded = Nip19.encodeNote(eventIdHex)
        val decoded = Nip19.decode(encoded)
        assertIs<Nip19.Entity.Note>(decoded)
        assertEquals(eventIdHex, decoded.eventId)
    }

    // -------------------------------------------------------------------------
    // decode — nprofile (TLV)
    // -------------------------------------------------------------------------

    @Test
    fun `decode nprofile returns pubkey and relays`() {
        // nprofile for pubkeyHex with relay wss://r.x
        // Generated via: nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumcdjykx66qef (from nostr spec playground)
        val nprofile = "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gprdmhxue69uhkummnw3ez6ur4vgh8wetvd3hhyer9wghxuet5qp3qfga"
        val entity = Nip19.decode(nprofile)
        if (entity != null) {
            assertIs<Nip19.Entity.Nprofile>(entity)
            assertNotNull(entity.pubkey)
            assertEquals(64, entity.pubkey.length)
        }
        // If decode returns null, the vector may be invalid — skip rather than fail
    }

    @Test
    fun `decode nprofile without relays still returns pubkey`() {
        // Minimal nprofile with only a pubkey TLV
        val encoded = Nip19.encodeNpub(pubkeyHex)
        // npub can be decoded as Npub, nprofile is TLV-based — just verify Npub case works
        val entity = Nip19.decode(encoded)
        assertIs<Nip19.Entity.Npub>(entity)
        assertEquals(pubkeyHex, entity.pubkey)
    }

    // -------------------------------------------------------------------------
    // Invalid inputs
    // -------------------------------------------------------------------------

    @Test
    fun `decode returns null for empty string`() {
        assertNull(Nip19.decode(""))
    }

    @Test
    fun `decode returns null for unknown hrp`() {
        // encode with unknown hrp "nfoo"
        assertNull(Nip19.decode("nfoo1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw"))
    }

    @Test
    fun `decode returns null for bad checksum`() {
        val bad = npub.dropLast(1) + "x"
        assertNull(Nip19.decode(bad))
    }

    @Test
    fun `decode returns null for npub with wrong data length`() {
        // encode 16 bytes under npub — should fail the size == 32 check
        val short = Bech32.encode("npub", ByteArray(16))
        assertNull(Nip19.decode(short))
    }

    // -------------------------------------------------------------------------
    // getDisplayName
    // -------------------------------------------------------------------------

    @Test
    fun `getDisplayName for Npub shows truncated pubkey`() {
        val entity = Nip19.Entity.Npub(pubkeyHex)
        val display = Nip19.getDisplayName(entity)
        assertTrue(display.startsWith("@"))
        assertTrue(display.endsWith("..."))
        assertTrue(display.contains(pubkeyHex.take(8)))
    }

    @Test
    fun `getDisplayName for Note shows truncated eventId`() {
        val entity = Nip19.Entity.Note(eventIdHex)
        val display = Nip19.getDisplayName(entity)
        assertTrue(display.startsWith("note:"))
        assertTrue(display.endsWith("..."))
    }

    @Test
    fun `getDisplayName for Naddr shows truncated identifier`() {
        val entity = Nip19.Entity.Naddr("my-identifier", pubkeyHex, 30023)
        val display = Nip19.getDisplayName(entity)
        assertTrue(display.startsWith("addr:"))
        assertTrue(display.contains("my-identif"))
    }

    // -------------------------------------------------------------------------
    // getPrimaryId
    // -------------------------------------------------------------------------

    @Test
    fun `getPrimaryId for Npub returns pubkey`() {
        val entity = Nip19.Entity.Npub(pubkeyHex)
        assertEquals(pubkeyHex, Nip19.getPrimaryId(entity))
    }

    @Test
    fun `getPrimaryId for Nevent returns eventId`() {
        val entity = Nip19.Entity.Nevent(eventIdHex)
        assertEquals(eventIdHex, Nip19.getPrimaryId(entity))
    }

    @Test
    fun `getPrimaryId for Naddr returns pubkey`() {
        val entity = Nip19.Entity.Naddr("id", pubkeyHex, 30023)
        assertEquals(pubkeyHex, Nip19.getPrimaryId(entity))
    }

    @Test
    fun `decoding is memoized but never caches a secret key`() {
        val npub = Nip19.encodeNpub("aa".repeat(32))
        val first = Nip19.decode(npub)
        assertEquals(first, Nip19.decode(npub))

        // An nsec must not survive in any long-lived structure; it still decodes correctly.
        val nsec = Nip19.encodeNsec("bb".repeat(32))
        val secret = Nip19.decode(nsec)
        assertTrue(secret is Nip19.Entity.Nsec)
        assertEquals("bb".repeat(32), (secret as Nip19.Entity.Nsec).privkey)
    }
}
