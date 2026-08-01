package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.shortNpub

// Regex to match nostr:nevent, nostr:note, or nostr:naddr references
private val NOSTR_EVENT_REGEX = Regex("""nostr:(nevent1[a-zA-Z0-9]+|note1[a-zA-Z0-9]+|naddr1[a-zA-Z0-9]+)""")

/**
 * Extract event IDs/coordinates embedded in content via nostr:nevent, nostr:note, or nostr:naddr.
 * Used to avoid showing duplicate reply preview when event is already quoted inline.
 *
 * Returns both hex event IDs and naddr coordinates (kind:pubkey:identifier format).
 */
fun extractEmbeddedEventIds(content: String): Set<String> = NOSTR_EVENT_REGEX
    .findAll(content)
    .mapNotNull { match ->
        val bech32 = match.groupValues[1]
        when (val entity = Nip19.decode(bech32)) {
            is Nip19.Entity.Nevent -> entity.eventId
            is Nip19.Entity.Note -> entity.eventId
            is Nip19.Entity.Naddr -> "${entity.kind}:${entity.pubkey}:${entity.identifier}"
            else -> null
        }
    }.toSet()

/**
 * Extract the parent event ID from a message's tags for REPLY threading.
 *
 * For NIP-29 group messages (kind 9), replies are detected via:
 * 1. "q" tag with hex event ID or naddr coordinate (quoted reply)
 * 2. "e" tag with "reply" marker (legacy format)
 * 3. Plain "e" tag (fallback)
 *
 * If the reply target is already embedded in content (via nostr:nevent/naddr),
 * returns null to avoid duplicate display.
 *
 * Returns the parent event ID/coordinate or null if not a reply (or already embedded).
 */
fun getReplyParentId(message: NostrGroupClient.NostrMessage): String? {
    // Only kind 9 messages can have replies in group context
    if (message.kind != 9) {
        return null
    }

    // Extract event IDs/coordinates already embedded in content
    val embeddedEventIds = extractEmbeddedEventIds(message.content)

    // 1. Check for "q" tag (quoted reply)
    // Can be either 64-char hex event ID or naddr coordinate (kind:pubkey:identifier)
    val qTag =
        message.tags.find { tag ->
            tag.size >= 2 && tag[0] == "q" && tag[1].isNotBlank()
        }
    if (qTag != null) {
        val reference = qTag[1]
        // Skip if already embedded in content (will be shown as inline quote)
        if (reference in embeddedEventIds) {
            return null
        }
        // Only return if it's a valid hex event ID (64 chars)
        // naddr coordinates are handled inline via content rendering
        if (reference.length == 64 && reference.all { it.isLetterOrDigit() }) {
            return reference
        }
        // For naddr-style coordinates in q tag, skip if content has matching naddr
        // These are rendered inline, not as reply headers
        if (reference.contains(":")) {
            return null
        }
    }

    // 2. Check for "e" tag with "reply" marker (legacy format)
    val replyMarkerTag =
        message.tags.find { tag ->
            tag.size >= 4 && tag[0] == "e" && tag[3] == "reply"
        }
    if (replyMarkerTag != null) {
        val eventId = replyMarkerTag[1]
        if (eventId in embeddedEventIds) {
            return null
        }
        if (eventId.length == 64) {
            return eventId
        }
    }

    // 3. Check for plain "e" tag (fallback)
    val eTag =
        message.tags.find { tag ->
            tag.size >= 2 && tag[0] == "e" && tag[1].length == 64
        }
    if (eTag != null) {
        val eventId = eTag[1]
        if (eventId in embeddedEventIds) {
            return null
        }
        return eventId
    }

    return null
}

/**
 * Compact reply preview shown above a message that is replying to another message.
 * Shows the parent message author and a truncated preview of the content.
 */
@Composable
fun ReplyPreview(
    parentMessage: NostrGroupClient.NostrMessage?,
    parentMetadata: UserMetadata?,
    resolveMetadata: (String) -> UserMetadata? = { null },
    // Null when the quote is not tappable (the parent is not in this thread / chat page). Do NOT
    // pass an empty lambda for that: a no-op clickable still consumes the gesture, so a
    // long-press on the quote would never reach the row's context-menu detector.
    onReplyClick: (() -> Unit)? = null,
    // Keeps the row's context menu reachable from inside the quote: a plain clickable consumes
    // the long-press, so the tappable quote has to offer the gesture itself.
    onReplyLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (parentMessage == null) {
        // Parent message not found - show placeholder
        ReplyPreviewContainer(
            onClick = onReplyClick,
            onLongClick = onReplyLongClick,
            modifier = modifier,
        ) {
            Text(
                text = "Replying to a message...",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption,
                maxLines = 1,
            )
        }
        return
    }

    val authorName =
        parentMetadata?.displayName
            ?: parentMetadata?.name
            ?: shortNpub(parentMessage.pubkey)

    // Request metadata for any pubkeys mentioned in the content
    LaunchedEffect(parentMessage.content) {
        val pubkeysToFetch =
            extractPubkeysFromContent(parentMessage.content)
                .filter { resolveMetadata(it) == null }
                .toSet()
        if (pubkeysToFetch.isNotEmpty()) {
            AppModule.nostrRepository.requestUserMetadata(pubkeysToFetch)
        }
    }

    // Process mentions in content to show @name instead of nostr:npub...
    val processedContent =
        remember(parentMessage.content) {
            processMentionsInContent(parentMessage.content, resolveMetadata)
                .replace('\n', ' ')
        }

    ReplyPreviewContainer(
        onClick = onReplyClick,
        onLongClick = onReplyLongClick,
        modifier = modifier,
    ) {
        Text(
            text = authorName,
            color = NostrordColors.Primary,
            style = NostrordTypography.Caption,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = processedContent.take(100),
            color = NostrordColors.TextSecondary,
            style = NostrordTypography.Caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Container for reply preview with left accent bar.
 */
@Composable
private fun ReplyPreviewContainer(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            // Reply background, matching the web .msg-reply: keep the good translucent dark
            // (rgba(47,49,54,0.5)) in the dark theme, and the lighter floating surface in light.
            .background(
                if (NostrordColors.IsDark) {
                    Color(0xFF2F3136).copy(alpha = 0.5f)
                } else {
                    NostrordColors.BackgroundFloating
                },
            )
            .then(
                if (onClick != null) {
                    Modifier
                        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                        .pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true)
                } else {
                    Modifier
                },
            )
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
            Modifier
                .width(3.dp)
                .height(32.dp)
                .background(
                    color = NostrordColors.Primary,
                    shape = RoundedCornerShape(1.5.dp),
                ),
        )

        Spacer(modifier = Modifier.width(Spacing.sm))

        Column(
            modifier =
            Modifier
                .weight(1f)
                .padding(end = Spacing.sm),
        ) {
            content()
        }
    }
}
