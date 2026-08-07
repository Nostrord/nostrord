package org.nostr.nostrord.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Identity of a route for history de-duplication, deliberately ignoring the one-shot
 * deep-link params on [GroupRoute] ([GroupRoute.inviteCode] / [GroupRoute.messageId]).
 * Re-opening the same group with a fresh invite or message-jump replaces the current
 * entry instead of pushing a new one, so an invite or an `?e=` target never becomes a
 * place back lands on. The default Home tab ([HomeTab.Groups]) shares the null (root)
 * key so the two spellings of Home occupy one history slot.
 *
 * The group [GroupRoute.view] (chat vs threads) and the open [GroupRoute.threadRootId] ARE
 * part of the key: chat, the threads list, and a single thread are distinct destinations, so
 * back/forward (swipe, system back, the toolbar arrows) walk between them instead of collapsing
 * the whole group session into one slot and skipping straight out of the group.
 */
fun routeKey(route: HashRoute?): String = when (route) {
    null -> "home"
    is HomeRoute -> if (route.tab == HomeTab.Groups) "home" else "home:${route.tab}"
    is GroupRoute -> "g:${route.relayUrl}/${route.groupId}:${route.view}:${route.threadRootId ?: ""}"
    is RelayRoute -> "r:${route.relayUrl}"
    is UserRoute -> "u:${route.pubkey}"
    is SpellRoute -> "s:${route.spellId}"
    is DmRoute -> "dm:${route.pubkey ?: ""}"
    is NotificationsRoute -> "notifications"
    is SettingsRoute -> "settings"
}

/** An immutable snapshot of the navigation stack for observers. */
data class NavState(
    val entries: List<HashRoute?>,
    val index: Int,
) {
    /** The route at the cursor; null is Home (the default Groups tab). */
    val current: HashRoute? get() = entries[index]
    val canGoBack: Boolean get() = index > 0
    val canGoForward: Boolean get() = index < entries.lastIndex

    /** Entries before the cursor, oldest first, for a back-history dropdown. */
    val backStack: List<HashRoute?> get() = entries.subList(0, index)
}

/**
 * A browser-style navigation history shared by every platform: a cursor over an ordered
 * list of routes ([HashRoute], with null meaning Home). [navigate] pushes a new entry and
 * truncates any forward history, except when the target shares the current [routeKey], in
 * which case it [replace]s in place (re-tap, or one-shot param consumption). [back] and
 * [forward] move the cursor; [reset] drops the whole stack (account switch) and
 * [seedDeepLink] seeds Home under a startup target so back always returns into the app.
 *
 * Lives in commonMain and exposes [StateFlow] only (no Compose state) so the web React UI
 * consumes the same stack as the native Compose UI. Mutated only from the UI thread.
 */
class NavigationHistory(initial: HashRoute? = null) {
    private companion object {
        const val MAX_HISTORY_SIZE = 50
    }

    private val entries = ArrayDeque<HashRoute?>().apply { add(initial) }
    private var index = 0

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<NavState> = _state.asStateFlow()

    val current: HashRoute? get() = entries[index]
    val canGoBack: Boolean get() = index > 0
    val canGoForward: Boolean get() = index < entries.lastIndex

    private fun snapshot() = NavState(entries.toList(), index)

    private fun emit() {
        _state.value = snapshot()
    }

    /** Push [route], or replace the current entry when it shares the current [routeKey]. */
    fun navigate(route: HashRoute?) {
        if (routeKey(route) == routeKey(entries[index])) replace(route) else push(route)
    }

    /**
     * Pop when the previous entry is [route] (by [routeKey]), else push [route]. Lets an in-screen
     * "up"/back button (e.g. a thread detail returning to the threads list) return to where the
     * user came from without stacking a duplicate, while still working when the screen was reached
     * directly (a deep link with no matching entry behind it).
     */
    fun navigateBackOr(route: HashRoute?) {
        if (index > 0 && routeKey(entries[index - 1]) == routeKey(route)) back() else navigate(route)
    }

    /** Always add a new entry above the cursor, dropping any forward history. */
    fun push(route: HashRoute?) {
        while (entries.lastIndex > index) entries.removeLast()
        entries.add(route)
        index = entries.lastIndex
        while (entries.size > MAX_HISTORY_SIZE) {
            entries.removeFirst()
            index--
        }
        emit()
    }

    /** Swap the current entry in place (re-tap, one-shot param strip, tab mirror). */
    fun replace(route: HashRoute?) {
        entries[index] = route
        emit()
    }

    /** Move the cursor back one entry (no-op at the start); returns the new current. */
    fun back(): HashRoute? {
        if (index > 0) {
            index--
            emit()
        }
        return entries[index]
    }

    /** Move the cursor forward one entry (no-op at the end); returns the new current. */
    fun forward(): HashRoute? {
        if (index < entries.lastIndex) {
            index++
            emit()
        }
        return entries[index]
    }

    /** Jump the cursor to an absolute index (back-history dropdown, or web popstate idx). */
    fun goToIndex(i: Int) {
        if (i in entries.indices && i != index) {
            index = i
            emit()
        }
    }

    /** Replace the whole stack with a single entry; used on account switch. */
    fun reset(route: HashRoute? = null) {
        entries.clear()
        entries.add(route)
        index = 0
        emit()
    }

    /**
     * Seed the stack from a cold-start deep link: Home at the bottom, [route] on top, so
     * back returns Home and never leaves the app. A null or default-Home target is a no-op.
     */
    fun seedDeepLink(route: HashRoute?) {
        if (route == null || (route is HomeRoute && route.tab == HomeTab.Groups)) return
        entries.clear()
        entries.add(null)
        entries.add(route)
        index = 1
        emit()
    }
}
