package org.nostr.nostrord.nostr

import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.auth.NostrSigner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip17Test {
    private fun signer() = NostrSigner.Local(KeyPair.generate())

    /** A signer that delegates signing + NIP-44 to a held key, like a remote NIP-46/NIP-07 signer. */
    private fun remoteStyleSigner(): NostrSigner {
        val kp = KeyPair.generate()
        return object : NostrSigner {
            override val pubkey = kp.publicKeyHex

            override suspend fun signEvent(event: Event): Event = event.sign(kp)

            override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String = Nip44.encrypt(plaintext, kp.privateKeyHex, peerPubkeyHex)

            override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String = Nip44.decrypt(ciphertext, kp.privateKeyHex, peerPubkeyHex)

            override fun dispose() {}
        }
    }

    @Test
    fun `wrap then unwrap round-trips the message`() = runTest {
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "hi bob")
        val wrap = Nip17.wrap(rumor, bob.pubkey, alice)

        assertEquals(Nip17.KIND_GIFT_WRAP, wrap.kind)
        assertEquals(bob.pubkey, wrap.getTag("p")?.getOrNull(1))
        assertTrue(wrap.pubkey != alice.pubkey, "gift wrap must use a throwaway key, not the sender's")
        assertTrue(wrap.verify(), "gift wrap must be validly signed by the throwaway key")

        val out = Nip17.unwrap(wrap, bob)
        assertNotNull(out)
        assertEquals("hi bob", out.rumor.content)
        assertEquals(alice.pubkey, out.senderPubkey)
        assertEquals(Nip17.KIND_CHAT, out.rumor.kind)
        assertEquals(bob.pubkey, out.rumor.getTag("p")?.getOrNull(1))
    }

    @Test
    fun `a third party cannot unwrap`() = runTest {
        val alice = signer()
        val bob = signer()
        val eve = signer()
        val wrap = Nip17.wrap(Nip17.buildRumor(alice.pubkey, bob.pubkey, "secret"), bob.pubkey, alice)
        assertNull(Nip17.unwrap(wrap, eve))
    }

    @Test
    fun `seal is identity-signed by the sender`() = runTest {
        val alice = signer()
        val bob = signer()
        val seal = Nip17.seal(Nip17.buildRumor(alice.pubkey, bob.pubkey, "x"), bob.pubkey, alice)
        assertEquals(Nip17.KIND_SEAL, seal.kind)
        assertEquals(alice.pubkey, seal.pubkey)
        assertTrue(seal.verify())
    }

    @Test
    fun `gift wrap timestamp is at or before now`() = runTest {
        val alice = signer()
        val bob = signer()
        val wrap = Nip17.wrap(Nip17.buildRumor(alice.pubkey, bob.pubkey, "x"), bob.pubkey, alice)
        assertTrue(wrap.createdAt <= org.nostr.nostrord.utils.epochSeconds())
    }

    @Test
    fun `wrap then unwrap works through a remote-style signer - NIP-46 and NIP-07 delegation`() = runTest {
        // Mirrors how Bunker / Nip07Extension delegate: signEvent + nip44 go to a remote that holds
        // the key; the envelope only depends on the NostrSigner interface, not on Local.
        val alice = remoteStyleSigner()
        val bob = remoteStyleSigner()
        val wrap = Nip17.wrap(Nip17.buildRumor(alice.pubkey, bob.pubkey, "via bunker"), bob.pubkey, alice)

        val out = Nip17.unwrap(wrap, bob)
        assertNotNull(out)
        assertEquals("via bunker", out.rumor.content)
        assertEquals(alice.pubkey, out.senderPubkey)
    }

    @Test
    fun `classic wrap keeps a single p tag and no n tag`() = runTest {
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "x")
        val seal = Nip17.seal(rumor, bob.pubkey, alice)
        val wrap = Nip17.giftWrap(seal, bob.pubkey)

        assertTrue(seal.tags.isEmpty(), "classic seal carries no NIP-4e n tag")
        assertEquals(listOf(listOf("p", bob.pubkey)), wrap.tags)
    }

    @Test
    fun `NIP-4e wrap addresses the encryption key and round-trips with it`() = runTest {
        val alice = signer()
        val bob = signer()
        // Bob announced this key; only he holds its private half.
        val bobEnc = KeyPair.generate()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "hi via nip4e")
        val wrap =
            Nip17.wrap(
                rumor,
                bob.pubkey,
                alice,
                encryptTo = bobEnc.publicKeyHex,
                senderEncTag = alice.pubkey,
            )

        // Encryption key leads so readers taking p[0] find it; the identity p tag must remain,
        // because that is what relays route on and what every inbox REQ filters by.
        assertEquals(listOf(listOf("p", bobEnc.publicKeyHex), listOf("p", bob.pubkey)), wrap.tags)

        val out = Nip17.unwrap(wrap, NostrSigner.Local(bobEnc))
        assertNotNull(out)
        assertEquals("hi via nip4e", out.rumor.content)
        assertEquals(alice.pubkey, out.senderPubkey, "sender is still the identity that signed the seal")
    }

    @Test
    fun `NIP-4e seal names the key it was encrypted against`() = runTest {
        val alice = signer()
        val bob = signer()
        val bobEnc = KeyPair.generate()
        val seal =
            Nip17.seal(
                Nip17.buildRumor(alice.pubkey, bob.pubkey, "x"),
                bob.pubkey,
                alice,
                encryptTo = bobEnc.publicKeyHex,
                senderEncTag = alice.pubkey,
            )
        // Without this tag a reader falls back to a kind:10044 lookup and flags us unverified.
        assertEquals(alice.pubkey, Nip4e.encryptionKeyFromTags(seal.tags))
        assertEquals(alice.pubkey, seal.pubkey, "the seal is still identity-signed")
    }

    @Test
    fun `an encryption-key-addressed wrap does not open with the identity key alone`() = runTest {
        val alice = signer()
        val bob = signer()
        val bobEnc = KeyPair.generate()
        val wrap =
            Nip17.wrap(
                Nip17.buildRumor(alice.pubkey, bob.pubkey, "secret"),
                bob.pubkey,
                alice,
                encryptTo = bobEnc.publicKeyHex,
                senderEncTag = alice.pubkey,
            )
        assertNull(Nip17.unwrap(wrap, bob))
    }

    private fun keyDecryptor(kp: KeyPair) = Nip17.Nip44Decryptor { peer, ciphertext -> Nip44.decrypt(ciphertext, kp.privateKeyHex, peer) }

    @Test
    fun `a legacy seal is reported unauthenticated for the caller to check`() = runTest {
        val alice = signer()
        val aliceEnc = KeyPair.generate()
        val bob = signer()
        val bobEnc = KeyPair.generate()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "legacy shape")
        // Seal signed by the sender's ENCRYPTION key: proves nothing about who sent it.
        val seal = Nip17.legacySeal(rumor, aliceEnc.privateKeyHex, bobEnc.publicKeyHex)
        val wrap = Nip17.giftWrap(seal, bob.pubkey, encryptTo = bobEnc.publicKeyHex)

        val out = Nip17.unwrap(wrap, bob.pubkey, listOf(keyDecryptor(bobEnc)))
        assertNotNull(out)
        assertEquals("legacy shape", out.rumor.content)
        assertEquals(alice.pubkey, out.senderPubkey, "the claimed sender is the rumor author")
        assertEquals(aliceEnc.publicKeyHex, out.sealPubkey)
        assertTrue(!out.sealSignedByIdentity, "caller must verify this against the author's kind:10044")
    }

    @Test
    fun `a modern seal cannot claim someone else's rumor`() = runTest {
        val alice = signer()
        val mallory = signer()
        val bobEnc = KeyPair.generate()
        // Mallory seals a rumor attributed to Alice and tags it as NIP-4e.
        val rumor = Nip17.buildRumor(alice.pubkey, mallory.pubkey, "forged")
        val seal = Nip17.seal(rumor, mallory.pubkey, mallory, encryptTo = bobEnc.publicKeyHex, senderEncTag = mallory.pubkey)
        val wrap = Nip17.giftWrap(seal, mallory.pubkey, encryptTo = bobEnc.publicKeyHex)

        assertNull(Nip17.unwrap(wrap, "00".repeat(32), listOf(keyDecryptor(bobEnc))))
    }

    @Test
    fun `our own seal may carry someone else's rumor`() = runTest {
        // The self-archive shape: only our signer can produce our signature, so the author guard
        // is relaxed for it.
        val me = signer()
        val alice = signer()
        val myEnc = KeyPair.generate()
        val rumor = Nip17.buildRumor(alice.pubkey, me.pubkey, "archived")
        // Seal content encrypted locally with our encryption key to our own identity, so the seal
        // needs no `n`: the reader's peer is seal.pubkey, and we hold the other half.
        val seal = Nip17.seal(rumor, me.pubkey, me, encryptTo = me.pubkey, encryptWith = myEnc.privateKeyHex)
        val wrap = Nip17.giftWrap(seal, me.pubkey, encryptTo = myEnc.publicKeyHex)

        val out = Nip17.unwrap(wrap, me.pubkey, listOf(keyDecryptor(myEnc)))
        assertNotNull(out)
        assertEquals("archived", out.rumor.content)
        assertEquals(alice.pubkey, out.senderPubkey, "the original author survives archiving")
    }

    @Test
    fun `a retired key still opens its own history`() = runTest {
        val alice = signer()
        val bob = signer()
        val retired = KeyPair.generate()
        val current = KeyPair.generate()
        val wrap =
            Nip17.wrap(
                Nip17.buildRumor(alice.pubkey, bob.pubkey, "old message"),
                bob.pubkey,
                alice,
                encryptTo = retired.publicKeyHex,
                senderEncTag = alice.pubkey,
            )

        // Current key first, exactly as the receive path orders them.
        val out = Nip17.unwrap(wrap, bob.pubkey, listOf(keyDecryptor(current), keyDecryptor(retired)))
        assertNotNull(out)
        assertEquals("old message", out.rumor.content)
    }

    @Test
    fun `the signer is only reached after the local keys miss`() = runTest {
        val alice = signer()
        val bob = signer()
        var signerCalls = 0
        val wrap = Nip17.wrap(Nip17.buildRumor(alice.pubkey, bob.pubkey, "classic"), bob.pubkey, alice)
        val counting =
            Nip17.Nip44Decryptor { peer, ciphertext ->
                signerCalls++
                bob.nip44Decrypt(peer, ciphertext)
            }

        val out = Nip17.unwrap(wrap, bob.pubkey, listOf(keyDecryptor(KeyPair.generate()), counting))
        assertNotNull(out)
        assertEquals("classic", out.rumor.content)
        assertEquals(2, signerCalls, "one round-trip per layer, and only after the local key failed")
    }

    @Test
    fun `a signer without NIP-44 support rejects encryption`() = runTest {
        val stub =
            object : NostrSigner {
                override val pubkey = "00".repeat(32)

                override suspend fun signEvent(event: Event): Event = event

                override fun dispose() {}
            }
        assertFailsWith<NostrSigner.SigningException> { stub.nip44Encrypt(stub.pubkey, "x") }
    }
}
