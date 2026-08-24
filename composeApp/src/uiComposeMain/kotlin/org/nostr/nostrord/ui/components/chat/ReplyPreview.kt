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
import org.nostr.nostrord.ui.chat.messageBody
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.shortNpub

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
    // messageBody keeps a parent that is itself a NIP-C7 reply from opening the snippet with a pointer.
    val processedContent =
        remember(parentMessage.content) {
            processMentionsInContent(messageBody(parentMessage), resolveMetadata)
                .replace('\n', ' ')
        }

    ReplyQuote(
        authorName = authorName,
        snippet = processedContent,
        onClick = onReplyClick,
        onLongClick = onReplyLongClick,
        modifier = modifier,
    )
}

/**
 * The quote box itself, over already-resolved strings. Callers that hold something other than a
 * group message (a DM being replied to, say) render through this rather than restating the box.
 */
@Composable
fun ReplyQuote(
    authorName: String,
    snippet: String,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ReplyPreviewContainer(
        onClick = onClick,
        onLongClick = onLongClick,
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
            text = snippet.take(100),
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
