package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip27
import org.nostr.nostrord.ui.theme.NostrordColors

// Supported image extensions
private val imageExtensions = listOf(
    ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".bmp", ".ico"
)

// URL regex pattern
private val urlRegex = Regex(
    """https?://[^\s<>"{}|\\^`\[\]]+""",
    RegexOption.IGNORE_CASE
)

/**
 * Checks if a URL points to an image
 */
private fun isImageUrl(url: String): Boolean {
    val lowercaseUrl = url.lowercase()
    // Check file extension
    if (imageExtensions.any { lowercaseUrl.contains(it) }) {
        return true
    }
    // Check common image hosting patterns
    if (lowercaseUrl.contains("imgur.com") ||
        lowercaseUrl.contains("i.redd.it") ||
        lowercaseUrl.contains("pbs.twimg.com") ||
        lowercaseUrl.contains("cdn.discordapp.com") ||
        lowercaseUrl.contains("media.tenor.com") ||
        lowercaseUrl.contains("giphy.com") ||
        lowercaseUrl.contains("nostr.build") ||
        lowercaseUrl.contains("void.cat") ||
        lowercaseUrl.contains("imgproxy") ||
        lowercaseUrl.contains("image")) {
        return true
    }
    return false
}

/**
 * Represents a part of message content - text, image, link, or nostr mention
 */
private sealed class ContentPart {
    data class TextPart(val text: String) : ContentPart()
    data class ImagePart(val url: String) : ContentPart()
    data class LinkPart(val url: String) : ContentPart()
    data class NostrMention(val reference: Nip27.NostrReference) : ContentPart()
}

/**
 * Represents a match found during content parsing
 */
private data class ContentMatch(
    val range: IntRange,
    val part: ContentPart
)

/**
 * Parses message content into parts (text, images, links, nostr mentions)
 */
private fun parseContent(content: String): List<ContentPart> {
    val matches = mutableListOf<ContentMatch>()

    // Find all URL matches
    urlRegex.findAll(content).forEach { match ->
        val url = match.value
        val part = if (isImageUrl(url)) {
            ContentPart.ImagePart(url)
        } else {
            ContentPart.LinkPart(url)
        }
        matches.add(ContentMatch(match.range, part))
    }

    // Find all nostr: URI matches (NIP-27)
    Nip27.findReferenceMatches(content).forEach { (range, reference) ->
        matches.add(ContentMatch(range, ContentPart.NostrMention(reference)))
    }

    // Sort matches by start position
    matches.sortBy { it.range.first }

    // Remove overlapping matches (keep first one)
    val filteredMatches = mutableListOf<ContentMatch>()
    var lastEnd = -1
    for (match in matches) {
        if (match.range.first > lastEnd) {
            filteredMatches.add(match)
            lastEnd = match.range.last
        }
    }

    // Build parts list with text between matches
    val parts = mutableListOf<ContentPart>()
    var lastIndex = 0

    for (match in filteredMatches) {
        // Add text before match if any
        if (match.range.first > lastIndex) {
            val text = content.substring(lastIndex, match.range.first)
            if (text.isNotBlank()) {
                parts.add(ContentPart.TextPart(text))
            }
        }
        parts.add(match.part)
        lastIndex = match.range.last + 1
    }

    // Add remaining text after last match
    if (lastIndex < content.length) {
        val text = content.substring(lastIndex)
        if (text.isNotBlank()) {
            parts.add(ContentPart.TextPart(text))
        }
    }

    // If no matches found, return the whole content as text
    if (parts.isEmpty() && content.isNotBlank()) {
        parts.add(ContentPart.TextPart(content))
    }

    return parts
}

/**
 * Check if a content part should be rendered as a block element (on its own line)
 */
private fun isBlockPart(part: ContentPart): Boolean {
    return when (part) {
        is ContentPart.ImagePart -> true
        is ContentPart.NostrMention -> {
            // Quoted events (nevent, note) are block elements
            when (part.reference.entity) {
                is Nip19.Entity.Nevent, is Nip19.Entity.Note -> true
                else -> false
            }
        }
        else -> false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageContent(
    content: String,
    modifier: Modifier = Modifier
) {
    val parts = remember(content) { parseContent(content) }
    val uriHandler = LocalUriHandler.current

    // Group parts into inline sequences and block elements
    val groups = remember(parts) {
        val result = mutableListOf<List<ContentPart>>()
        var currentInlineGroup = mutableListOf<ContentPart>()

        parts.forEach { part ->
            if (isBlockPart(part)) {
                // Flush current inline group if not empty
                if (currentInlineGroup.isNotEmpty()) {
                    result.add(currentInlineGroup.toList())
                    currentInlineGroup = mutableListOf()
                }
                // Add block element as its own group
                result.add(listOf(part))
            } else {
                currentInlineGroup.add(part)
            }
        }
        // Flush remaining inline group
        if (currentInlineGroup.isNotEmpty()) {
            result.add(currentInlineGroup.toList())
        }
        result
    }

    Column(modifier = modifier) {
        groups.forEach { group ->
            val firstPart = group.firstOrNull()

            // Check if this is a block element group (single block element)
            if (group.size == 1 && isBlockPart(firstPart!!)) {
                when (firstPart) {
                    is ContentPart.ImagePart -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        ChatImage(
                            imageUrl = firstPart.url,
                            onClick = {
                                try {
                                    uriHandler.openUri(firstPart.url)
                                } catch (_: Exception) {}
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is ContentPart.NostrMention -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        NostrMentionChip(
                            mention = firstPart,
                            onClick = {
                                try {
                                    uriHandler.openUri(firstPart.reference.uri)
                                } catch (_: Exception) {}
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    else -> {}
                }
            } else {
                // Render inline group with FlowRow
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    group.forEach { part ->
                        when (part) {
                            is ContentPart.TextPart -> {
                                SelectionContainer {
                                    Text(
                                        text = part.text,
                                        color = NostrordColors.TextContent,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            is ContentPart.LinkPart -> {
                                Text(
                                    text = part.url,
                                    color = NostrordColors.Primary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.clickable {
                                        try {
                                            uriHandler.openUri(part.url)
                                        } catch (_: Exception) {}
                                    }
                                )
                            }
                            is ContentPart.NostrMention -> {
                                // Inline mention (npub, nprofile, etc.)
                                NostrMentionChip(
                                    mention = part,
                                    onClick = {
                                        try {
                                            uriHandler.openUri(part.reference.uri)
                                        } catch (_: Exception) {}
                                    }
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatImage(
    imageUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current
    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var showError by remember { mutableStateOf(false) }

    if (showError) {
        // Show URL as link if image fails to load
        Text(
            text = imageUrl,
            color = NostrordColors.Primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable(onClick = onClick)
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(NostrordColors.Surface)
                .clickable(onClick = onClick)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Image",
                contentScale = ContentScale.FillWidth,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(8.dp)),
                onState = { state ->
                    imageState = state
                    if (state is AsyncImagePainter.State.Error) {
                        showError = true
                    }
                }
            )

            // Show loading placeholder
            if (imageState is AsyncImagePainter.State.Loading) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 200.dp)
                        .heightIn(min = 100.dp)
                        .background(NostrordColors.Surface, RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

@Composable
private fun NostrMentionChip(
    mention: ContentPart.NostrMention,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entity = mention.reference.entity

    when (entity) {
        is Nip19.Entity.Nevent -> {
            QuotedEvent(
                eventId = entity.eventId,
                relayHints = entity.relays,  // Pass relay hints from nevent
                author = entity.author,       // Pass author for outbox model
                onClick = onClick,
                modifier = modifier
            )
        }
        is Nip19.Entity.Note -> {
            QuotedEvent(
                eventId = entity.eventId,
                relayHints = emptyList(),  // note doesn't have relay hints
                author = null,
                onClick = onClick,
                modifier = modifier
            )
        }
        else -> {
            UserMentionChip(
                entity = entity,
                onClick = onClick,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun UserMentionChip(
    entity: Nip19.Entity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val userMetadata by NostrRepository.userMetadata.collectAsState()

    val displayText = when (entity) {
        is Nip19.Entity.Npub -> {
            val metadata = userMetadata[entity.pubkey]
            metadata?.displayName ?: metadata?.name ?: Nip19.getDisplayName(entity)
        }
        is Nip19.Entity.Nprofile -> {
            val metadata = userMetadata[entity.pubkey]
            metadata?.displayName ?: metadata?.name ?: Nip19.getDisplayName(entity)
        }
        else -> Nip19.getDisplayName(entity)
    }

    LaunchedEffect(entity) {
        when (entity) {
            is Nip19.Entity.Npub -> {
                if (!userMetadata.containsKey(entity.pubkey)) {
                    NostrRepository.requestUserMetadata(setOf(entity.pubkey))
                }
            }
            is Nip19.Entity.Nprofile -> {
                if (!userMetadata.containsKey(entity.pubkey)) {
                    NostrRepository.requestUserMetadata(setOf(entity.pubkey))
                }
            }
            else -> {}
        }
    }

    Text(
        text = displayText,
        color = NostrordColors.Primary,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(NostrordColors.Primary.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun QuotedEvent(
    eventId: String,
    relayHints: List<String> = emptyList(),
    author: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cachedEvents by NostrRepository.cachedEvents.collectAsState()
    val userMetadata by NostrRepository.userMetadata.collectAsState()
    val event = cachedEvents[eventId]

    LaunchedEffect(eventId, relayHints, author) {
        if (!cachedEvents.containsKey(eventId)) {
            NostrRepository.requestEventById(eventId, relayHints, author)
        }
    }

    LaunchedEffect(event?.pubkey) {
        val pubkey = event?.pubkey
        if (pubkey != null && !userMetadata.containsKey(pubkey)) {
            NostrRepository.requestUserMetadata(setOf(pubkey))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        if (event != null) {
            val metadata = userMetadata[event.pubkey]
            val authorName = metadata?.displayName ?: metadata?.name ?: event.pubkey.take(8)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NostrordColors.Primary.copy(alpha = 0.3f))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = authorName,
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            QuotedEventContent(content = event.content)
        } else {
            Text(
                text = "Loading event ${eventId.take(8)}...",
                color = NostrordColors.TextMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuotedEventContent(
    content: String,
    modifier: Modifier = Modifier
) {
    val parts = remember(content) { parseContent(content) }
    val uriHandler = LocalUriHandler.current

    // Group parts into inline sequences and block elements
    val groups = remember(parts) {
        val result = mutableListOf<List<ContentPart>>()
        var currentInlineGroup = mutableListOf<ContentPart>()

        parts.forEach { part ->
            if (isBlockPart(part)) {
                if (currentInlineGroup.isNotEmpty()) {
                    result.add(currentInlineGroup.toList())
                    currentInlineGroup = mutableListOf()
                }
                result.add(listOf(part))
            } else {
                currentInlineGroup.add(part)
            }
        }
        if (currentInlineGroup.isNotEmpty()) {
            result.add(currentInlineGroup.toList())
        }
        result
    }

    Column(modifier = modifier) {
        groups.forEach { group ->
            val firstPart = group.firstOrNull()

            if (group.size == 1 && isBlockPart(firstPart!!)) {
                when (firstPart) {
                    is ContentPart.ImagePart -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        QuotedImage(
                            imageUrl = firstPart.url,
                            onClick = {
                                try {
                                    uriHandler.openUri(firstPart.url)
                                } catch (_: Exception) {}
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is ContentPart.NostrMention -> {
                        // Nested quoted event - show as simple link
                        Text(
                            text = Nip19.getDisplayName(firstPart.reference.entity),
                            color = NostrordColors.Primary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NostrordColors.Primary.copy(alpha = 0.1f))
                                .clickable {
                                    try {
                                        uriHandler.openUri(firstPart.reference.uri)
                                    } catch (_: Exception) {}
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    else -> {}
                }
            } else {
                // Render inline group with FlowRow
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    group.forEach { part ->
                        when (part) {
                            is ContentPart.TextPart -> {
                                Text(
                                    text = part.text,
                                    color = NostrordColors.TextContent,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 6
                                )
                            }
                            is ContentPart.LinkPart -> {
                                Text(
                                    text = part.url,
                                    color = NostrordColors.Primary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.clickable {
                                        try {
                                            uriHandler.openUri(part.url)
                                        } catch (_: Exception) {}
                                    }
                                )
                            }
                            is ContentPart.NostrMention -> {
                                // Inline mention in quoted content
                                Text(
                                    text = Nip19.getDisplayName(part.reference.entity),
                                    color = NostrordColors.Primary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(NostrordColors.Primary.copy(alpha = 0.1f))
                                        .clickable {
                                            try {
                                                uriHandler.openUri(part.reference.uri)
                                            } catch (_: Exception) {}
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotedImage(
    imageUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current
    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var showError by remember { mutableStateOf(false) }

    if (showError) {
        Text(
            text = imageUrl,
            color = NostrordColors.Primary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable(onClick = onClick)
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(NostrordColors.BackgroundDark)
                .clickable(onClick = onClick)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Image",
                contentScale = ContentScale.FillWidth,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(6.dp)),
                onState = { state ->
                    imageState = state
                    if (state is AsyncImagePainter.State.Error) {
                        showError = true
                    }
                }
            )

            if (imageState is AsyncImagePainter.State.Loading) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 150.dp)
                        .heightIn(min = 80.dp)
                        .background(NostrordColors.BackgroundDark, RoundedCornerShape(6.dp))
                )
            }
        }
    }
}
