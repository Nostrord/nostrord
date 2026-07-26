package org.nostr.nostrord.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pubkey doubles as id`() {
        val account =
            Account(
                pubkey = "a".repeat(64),
                label = "Account 1",
                authMethod = AuthMethod.LOCAL,
                addedAt = 1_700_000_000_000L,
            )
        assertEquals(account.pubkey, account.id)
    }

    @Test
    fun `account serializes round-trip preserving every field`() {
        val original =
            Account(
                pubkey = "b".repeat(64),
                label = "My bunker",
                authMethod = AuthMethod.BUNKER,
                addedAt = 1_700_000_000_000L,
            )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Account>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `account list serializes round-trip`() {
        val list =
            listOf(
                Account("a".repeat(64), "Local 1", AuthMethod.LOCAL, 1L),
                Account("b".repeat(64), "Bunker 1", AuthMethod.BUNKER, 2L),
                Account("c".repeat(64), "Extension", AuthMethod.NIP07, 3L),
                Account("d".repeat(64), "Amber", AuthMethod.AMBER, 4L),
            )
        val encoded = json.encodeToString(list)
        val decoded = json.decodeFromString<List<Account>>(encoded)
        assertEquals(list, decoded)
    }

    @Test
    fun `auth method values cover all login paths`() {
        // Guards against accidentally dropping a variant.
        // LOCAL, BUNKER, NIP07, AMBER
        assertEquals(4, AuthMethod.entries.size)
    }

    @Test
    fun `amber accounts get per-method copy`() {
        val account = Account("e".repeat(64), "Amber 1", AuthMethod.AMBER, 5L)
        assertEquals("amber", signerLabel(account))
        assertEquals(
            "You will need to reconnect your signer app to log back in.",
            logoutConfirmBody(AuthMethod.AMBER),
        )
    }
}
