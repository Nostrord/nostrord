package org.nostr.nostrord.ui.screens.login

import org.nostr.nostrord.auth.pomegranate.PomegranateConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleAccountSetupTest {
    private fun setup() = GoogleAccountSetup(privateKeyHex = "ab".repeat(32), nsec = "nsec1test")

    @Test
    fun `defaults to the recommended operators and threshold`() {
        val s = setup()
        assertEquals(PomegranateConfig.OPERATOR_URLS, s.operators)
        assertEquals(PomegranateConfig.defaultThreshold(s.operators.size), s.threshold)
        assertTrue(s.recommendedOperators.isEmpty())
    }

    @Test
    fun `added operator is normalized to its origin`() {
        val s = setup().withOperatorAdded(" po.example.com/po/ ").getOrThrow()
        assertEquals("https://po.example.com", s.operators.last())
    }

    @Test
    fun `duplicate and malformed operators are rejected with a message`() {
        val s = setup()
        assertEquals(
            "This operator is already added",
            s.withOperatorAdded("https://po.f7z.io").exceptionOrNull()?.message,
        )
        assertEquals("Invalid URL", s.withOperatorAdded("not a url").exceptionOrNull()?.message)
        assertEquals("Invalid URL", s.withOperatorAdded("").exceptionOrNull()?.message)
    }

    @Test
    fun `removing operators stops at the minimum and lowers the threshold with them`() {
        var s = setup().withThreshold(5)
        PomegranateConfig.OPERATOR_URLS.forEach { s = s.withOperatorRemoved(it) }
        assertEquals(PomegranateConfig.MIN_OPERATORS, s.operators.size)
        assertEquals(PomegranateConfig.MIN_OPERATORS, s.threshold)
        assertFalse(s.canRemoveOperator)
        assertEquals(PomegranateConfig.OPERATOR_URLS.size - 2, s.recommendedOperators.size)
    }

    @Test
    fun `threshold is clamped to the operator count and the minimum`() {
        val s = setup()
        assertEquals(s.operators.size, s.withThreshold(99).threshold)
        assertEquals(PomegranateConfig.MIN_OPERATORS, s.withThreshold(0).threshold)
        assertFalse(s.withThreshold(0).canLowerThreshold)
        assertFalse(s.withThreshold(99).canRaiseThreshold)
    }

    @Test
    fun `config carries the shown key, operators and threshold`() {
        val s = setup().withThreshold(4)
        val config = s.toConfig()
        assertEquals("ab".repeat(32), config.privateKeyHex)
        assertEquals(s.operators, config.operators)
        assertEquals(4, config.threshold)
    }

    @Test
    fun `probe results only stick to operators still listed`() {
        val s = setup()
        val down = s.operators.first()
        val checked =
            s.withStatuses(
                mapOf(
                    down to GoogleAccountSetup.OperatorStatus.Unreachable,
                    "https://po.gone.example" to GoogleAccountSetup.OperatorStatus.Reachable,
                ),
            )
        assertEquals(listOf(down), checked.unreachableOperators)
        assertEquals(GoogleAccountSetup.OperatorStatus.Unknown, checked.statusOf("https://po.gone.example"))
        assertEquals(GoogleAccountSetup.OperatorStatus.Unknown, checked.withOperatorRemoved(down).statusOf(down))
    }

    @Test
    fun `an unreachable operator is dropped from the account and the threshold follows`() {
        val s = setup()
        val down = s.operators[0]
        val downOne = s.withStatuses(mapOf(down to GoogleAccountSetup.OperatorStatus.Unreachable))
        assertFalse(downOne.tooFewOperators)
        assertTrue(downOne.operatorWarning.contains("will be skipped"))
        // The dead operator stays listed (so the row can say why) but never gets a shard.
        assertEquals(s.operators.size, downOne.operators.size)
        assertEquals(s.operators - down, downOne.toConfig().operators)
        assertEquals(3, downOne.toConfig().threshold)

        val downMost =
            s.withStatuses(s.operators.take(4).associateWith { GoogleAccountSetup.OperatorStatus.Unreachable })
        assertTrue(downMost.tooFewOperators)
        assertTrue(downMost.operatorWarning.contains("at least ${PomegranateConfig.MIN_OPERATORS} operators"))
    }

    @Test
    fun `a pasted key replaces the generated one and is what gets sharded`() {
        val mine = "cd".repeat(32)
        val s = setup().withImportedKey(mine, "nsec1mine")
        assertEquals(mine, s.toConfig().privateKeyHex)
        assertEquals("nsec1mine", s.nsec)
        assertTrue(s.importedKey)
        assertTrue(s.introLine.contains("the key you pasted"))
        assertTrue(s.keyWarning.contains("only hold shards"))
        assertFalse(setup().introLine.contains("the key you pasted"))
    }

    @Test
    fun `creation waits for every operator verdict`() {
        val s = setup()
        assertTrue(s.operatorsPending, "an unprobed operator set is not ready to create")
        assertTrue(s.checking().operatorsPending, "still pending while the probes run")

        val partial = s.withStatuses(mapOf(s.operators[0] to GoogleAccountSetup.OperatorStatus.Reachable))
        assertTrue(partial.operatorsPending, "one verdict is not all of them")

        val all = s.withStatuses(s.operators.associateWith { GoogleAccountSetup.OperatorStatus.Reachable })
        assertFalse(all.operatorsPending)
        // A newly added operator re-opens the wait: it has no verdict yet.
        assertTrue(all.withOperatorAdded("https://po.example.com").getOrThrow().operatorsPending)
    }

    @Test
    fun `probe results pull an unreachable-heavy threshold down`() {
        val s = setup().withThreshold(5)
        val down = s.withStatuses(s.operators.take(2).associateWith { GoogleAccountSetup.OperatorStatus.Unreachable })
        assertEquals(3, down.threshold)
        assertEquals(3, down.usableOperators.size)
        assertFalse(down.canRaiseThreshold)
    }

    @Test
    fun `localhost operators are accepted, bare hostnames are not`() {
        val s = setup()
        assertNotNull(s.withOperatorAdded("http://localhost:3334").getOrNull())
        assertNull(s.withOperatorAdded("operator").getOrNull())
    }
}
