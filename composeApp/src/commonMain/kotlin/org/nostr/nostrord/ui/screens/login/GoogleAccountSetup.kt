package org.nostr.nostrord.ui.screens.login

import androidx.compose.runtime.Immutable
import org.nostr.nostrord.auth.pomegranate.PomegranateAccountConfig
import org.nostr.nostrord.auth.pomegranate.PomegranateConfig
import org.nostr.nostrord.auth.pomegranate.pomegranateOperatorLabel
import org.nostr.nostrord.auth.pomegranate.pomegranateOperatorUrlOrNull

/**
 * The "Create your account" step of the Google login, shown when the sign-in finds no
 * pomegranate account for that Google identity yet: the freshly generated key the user
 * must back up, plus the operators and signing threshold the account is registered with.
 *
 * Pure state with pure transforms so Compose and web share the clamping and validation
 * instead of each screen reimplementing them.
 */
@Immutable
data class GoogleAccountSetup(
    val privateKeyHex: String,
    val nsec: String,
    val operators: List<String> = PomegranateConfig.OPERATOR_URLS,
    val threshold: Int = PomegranateConfig.defaultThreshold(PomegranateConfig.OPERATOR_URLS.size),
    /** Probe result per operator URL; missing entries have not been checked yet. */
    val operatorStatus: Map<String, OperatorStatus> = emptyMap(),
    /** The key was pasted by the user (an identity they already have), not generated here. */
    val importedKey: Boolean = false,
) {
    /** Reachability of one operator as the setup step knows it. */
    enum class OperatorStatus { Unknown, Checking, Reachable, Unreachable }

    /** Recommended operators not currently picked, offered as one-tap chips. */
    val recommendedOperators: List<String>
        get() = PomegranateConfig.OPERATOR_URLS.filterNot { it in operators }

    val canRemoveOperator: Boolean get() = operators.size > PomegranateConfig.MIN_OPERATORS
    val canLowerThreshold: Boolean get() = threshold > PomegranateConfig.MIN_OPERATORS
    val canRaiseThreshold: Boolean get() = threshold < usableOperators.size

    fun statusOf(url: String): OperatorStatus = operatorStatus[url] ?: OperatorStatus.Unknown

    /** Adopts a key the user pasted; [hex] is already validated by the caller. */
    fun withImportedKey(
        hex: String,
        nsec: String,
    ): GoogleAccountSetup = copy(privateKeyHex = hex, nsec = nsec, importedKey = true)

    /** One-line context under the title; a pasted key changes what is about to be created. */
    val introLine: String
        get() = if (importedKey) {
            "This Google account has no Nostr key yet. It will be linked to the key you pasted."
        } else {
            "This Google account has no Nostr key yet, so a new one was created for it."
        }

    /** Save-your-key warning shown with the key itself, so it needs no callout of its own. */
    val keyWarning: String
        get() = if (importedKey) {
            "The operators only hold shards of this key. Your own backup is what recovers the account."
        } else {
            "Save a backup now. Google can recover this key, but the backup is what you own."
        }

    /** Operators that answered nothing; the account is created without them. */
    val unreachableOperators: List<String>
        get() = operators.filter { statusOf(it) == OperatorStatus.Unreachable }

    /**
     * The operators the account is actually created with: everything the probe has not ruled
     * out. Sending a shard to a host that just failed to answer would only stall registration
     * and leave a shard nobody holds.
     */
    val usableOperators: List<String>
        get() = operators.filterNot { statusOf(it) == OperatorStatus.Unreachable }

    /** True when the survivors cannot form an account at all; the screens block Create. */
    val tooFewOperators: Boolean
        get() = usableOperators.size < PomegranateConfig.MIN_OPERATORS

    /**
     * True until every operator has a probe verdict. Creating before that would deal
     * shards to hosts nobody has checked, which is how a registration ends up stalling on
     * a dead one, so the screens keep Create disabled while this holds.
     */
    val operatorsPending: Boolean
        get() = operators.any { statusOf(it) == OperatorStatus.Checking || statusOf(it) == OperatorStatus.Unknown }

    /** Names the operators that did not answer and what it means. Empty when all responded. */
    val operatorWarning: String
        get() {
            val down = unreachableOperators
            if (down.isEmpty()) return ""
            val names = down.joinToString { pomegranateOperatorLabel(it) }
            val live = usableOperators.size
            return if (tooFewOperators) {
                "$names did not answer, leaving $live of ${operators.size}. An account needs at least " +
                    "${PomegranateConfig.MIN_OPERATORS} operators, so add another one or try again later."
            } else {
                "$names did not answer and will be skipped. Your account is created with the remaining " +
                    "$live, $threshold of which must sign."
            }
        }

    /** Marks every operator as being probed, dropping results for ones no longer listed. */
    fun checking(): GoogleAccountSetup = copy(operatorStatus = operators.associateWith { OperatorStatus.Checking })

    /**
     * Folds probe results in, ignoring operators removed while the probes were in flight, and
     * pulls the threshold down to what the survivors can actually sign.
     */
    fun withStatuses(results: Map<String, OperatorStatus>): GoogleAccountSetup {
        val next = copy(operatorStatus = operators.associateWith { results[it] ?: statusOf(it) })
        return next.copy(threshold = clampThreshold(next.threshold, next.usableOperators.size))
    }

    /** Threshold clamped into [MIN_OPERATORS, usable operator count]. */
    fun withThreshold(value: Int): GoogleAccountSetup = copy(threshold = clampThreshold(value, usableOperators.size))

    /**
     * Adds a normalized operator origin. Fails with a user-facing message on a malformed
     * URL or a duplicate, which the screens show under the input.
     */
    fun withOperatorAdded(raw: String): Result<GoogleAccountSetup> {
        val url =
            pomegranateOperatorUrlOrNull(raw)
                ?: return Result.failure(IllegalArgumentException("Invalid URL"))
        if (url in operators) return Result.failure(IllegalArgumentException("This operator is already added"))
        return Result.success(copy(operators = operators + url))
    }

    /** Removes an operator, keeping at least [PomegranateConfig.MIN_OPERATORS] and a valid threshold. */
    fun withOperatorRemoved(url: String): GoogleAccountSetup {
        if (!canRemoveOperator) return this
        val next = operators.filterNot { it == url }
        if (next.size == operators.size) return this
        return copy(
            operators = next,
            threshold = clampThreshold(threshold, next.size),
            operatorStatus = operatorStatus - url,
        )
    }

    fun toConfig(): PomegranateAccountConfig = PomegranateAccountConfig(
        operators = usableOperators,
        threshold = clampThreshold(threshold, usableOperators.size),
        privateKeyHex = privateKeyHex,
    )

    private companion object {
        fun clampThreshold(
            value: Int,
            operatorCount: Int,
        ): Int = value.coerceIn(PomegranateConfig.MIN_OPERATORS, maxOf(PomegranateConfig.MIN_OPERATORS, operatorCount))
    }
}
