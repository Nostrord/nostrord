package org.nostr.nostrord.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfDecryptCacheTest {
    private val pubkey = "a".repeat(64)

    @Test
    fun `every relay copy of one list decrypts once`() = runTest {
        val cache = SelfDecryptCache()
        var prompts = 0
        val gate = CompletableDeferred<Unit>()

        // Five relays deliver the same replaceable event; all five reach the cache before
        // the first decrypt resolves.
        val copies = List(5) {
            async {
                cache.decrypt(pubkey, "cipher") {
                    prompts++
                    gate.await()
                    "plain"
                }
            }
        }
        yield()
        gate.complete(Unit)

        assertEquals(List(5) { "plain" }, copies.awaitAll())
        assertEquals(1, prompts)
    }

    @Test
    fun `a later redelivery is answered from the cache`() = runTest {
        val cache = SelfDecryptCache()
        var prompts = 0
        val read = suspend {
            cache.decrypt(pubkey, "cipher") {
                prompts++
                "plain"
            }
        }

        assertEquals("plain", read())
        assertEquals("plain", read())
        assertEquals(1, prompts)
    }

    @Test
    fun `a refusal is not re-asked on the next redelivery`() = runTest {
        val cache = SelfDecryptCache()
        var prompts = 0
        val read = suspend {
            cache.decrypt(pubkey, "cipher") {
                prompts++
                throw NostrSigner.SigningException("user rejected")
            }
        }

        assertNull(read())
        assertNull(read())
        assertNull(read())
        assertEquals(1, prompts)
    }

    @Test
    fun `a different ciphertext is a new decrypt`() = runTest {
        val cache = SelfDecryptCache()
        var prompts = 0
        val read: suspend (String) -> String? = { ciphertext ->
            cache.decrypt(pubkey, ciphertext) {
                prompts++
                "plain-$ciphertext"
            }
        }

        assertEquals("plain-v1", read("v1"))
        assertEquals("plain-v2", read("v2"))
        assertEquals(2, prompts)
    }

    @Test
    fun `clear drops another account's plaintext`() = runTest {
        val cache = SelfDecryptCache()
        var prompts = 0
        val read = suspend {
            cache.decrypt(pubkey, "cipher") {
                prompts++
                "plain"
            }
        }

        read()
        cache.clear()
        read()
        assertEquals(2, prompts)
    }
}

class SignerPromptsTest {
    @Test
    fun `prompting signers never have two dialogs open at once`() = runTest {
        var open = 0
        var maxOpen = 0

        List(8) {
            async {
                SignerPrompts.queued(promptsUser = true) {
                    open++
                    maxOpen = maxOf(maxOpen, open)
                    yield()
                    open--
                }
            }
        }.awaitAll()

        assertEquals(1, maxOpen)
    }

    @Test
    fun `silent signers stay concurrent`() = runTest {
        var open = 0
        var maxOpen = 0

        List(8) {
            async {
                SignerPrompts.queued(promptsUser = false) {
                    open++
                    maxOpen = maxOf(maxOpen, open)
                    yield()
                    open--
                }
            }
        }.awaitAll()

        assertTrue(maxOpen > 1, "a bunker backlog must not be serialized behind one request")
    }
}
