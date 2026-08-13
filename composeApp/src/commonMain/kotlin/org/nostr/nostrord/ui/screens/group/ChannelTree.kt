package org.nostr.nostrord.ui.screens.group

import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.utils.groupKey

/**
 * Discord-style channel model over the NIP-29 subgroup topology: the rail shows only
 * root groups ("servers"); opening one lists the root plus its whole subgroup subtree
 * as channels. These are the pure derivations both UIs and the rail share.
 */

/** One row of the flattened channel list. [depth] is 0 for the root, 1 per nesting level. */
data class ChannelEntry(val id: String, val depth: Int)

/**
 * Walk `parent` links up to the root group. [parentOf] returns null when a group has no
 * parent or the parent is outside the resolvable set (unknown meta / not joined), which
 * makes that group its own root. Cycle-guarded: a loop stops at the first repeat.
 */
fun rootGroupId(groupId: String, parentOf: (String) -> String?): String {
    var current = groupId
    val visited = mutableSetOf(current)
    while (true) {
        val parent = parentOf(current) ?: return current
        if (!visited.add(parent)) return current
        current = parent
    }
}

/**
 * Flatten the subgroup tree under [rootId] into the channel list: the root first (the
 * "general" channel), then each descendant depth-first. Siblings follow the parent's
 * declared child order (position in `meta.children`), undeclared ones after, by name.
 */
fun channelTree(
    rootId: String,
    childrenByParent: Map<String, Set<String>>,
    metaById: Map<String, GroupMetadata>,
): List<ChannelEntry> {
    val out = mutableListOf(ChannelEntry(rootId, 0))
    val visited = mutableSetOf(rootId)
    fun visit(parentId: String, depth: Int) {
        val declaredOrder = metaById[parentId]?.children.orEmpty().withIndex().associate { (i, id) -> id to i }
        val children = childrenByParent[parentId].orEmpty()
            .filter { visited.add(it) }
            .sortedWith(
                compareBy(
                    { declaredOrder[it] ?: Int.MAX_VALUE },
                    { (metaById[it]?.name ?: it).lowercase() },
                ),
            )
        children.forEach { child ->
            out.add(ChannelEntry(child, depth))
            visit(child, depth + 1)
        }
    }
    visit(rootId, 1)
    return out
}

/**
 * Unread total for a rail root: its own count plus every descendant channel's.
 * [unreadByGroupKey] is keyed by [groupKey], so the tree is resolved against [relayUrl]
 * and a same-id group on another relay never contributes.
 */
fun aggregateUnread(
    relayUrl: String,
    rootId: String,
    childrenByParent: Map<String, Set<String>>,
    unreadByGroupKey: Map<String, Int>,
): Int = channelTree(rootId, childrenByParent, emptyMap()).sumOf { unreadByGroupKey[groupKey(relayUrl, it.id)] ?: 0 }

/**
 * [aggregateUnread] with muted subtrees removed, for badge rollups. Muting a root
 * silences its whole tree (its channels are part of the same "server"); inside a live
 * root, an individually muted channel drops out on its own. The underlying counts stay
 * tracked either way, so opening a muted group still lands on its unread divider.
 */
fun aggregateUnreadUnmuted(
    relayUrl: String,
    rootId: String,
    childrenByParent: Map<String, Set<String>>,
    unreadByGroupKey: Map<String, Int>,
    isMuted: (String) -> Boolean,
): Int = if (isMuted(rootId)) {
    0
} else {
    channelTree(rootId, childrenByParent, emptyMap())
        .filterNot { isMuted(it.id) }
        .sumOf { unreadByGroupKey[groupKey(relayUrl, it.id)] ?: 0 }
}

/** A channel the user can see but not read/post without joining (private or closed, not a member). */
fun isLockedChannel(meta: GroupMetadata?, isJoined: Boolean): Boolean = !isJoined && meta != null && (!meta.isPublic || !meta.isOpen)

/**
 * Drag-reorder step: move [dragged] so it sits immediately BEFORE [target] (the drop
 * indicator renders at the target's top edge); a null [target] is the end-of-list drop
 * slot. Returns [order] unchanged when an id is missing or they are equal, so stray
 * drops are no-ops.
 */
fun moveChannelBefore(order: List<String>, dragged: String, target: String?): List<String> {
    if (dragged == target || dragged !in order) return order
    val without = order - dragged
    if (target == null) return without + dragged
    if (target !in order) return order
    val at = without.indexOf(target)
    return without.subList(0, at) + dragged + without.subList(at, without.size)
}
