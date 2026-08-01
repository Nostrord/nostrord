package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.isAndroid
import org.nostr.nostrord.isLinuxDesktop
import org.nostr.nostrord.network.CachedEvent
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip84
import org.nostr.nostrord.ui.components.avatars.OptimizedUserAvatar
import org.nostr.nostrord.ui.components.media.AudioPlayerContent
import org.nostr.nostrord.ui.components.media.PlatformVideoPlayer
import org.nostr.nostrord.ui.components.media.YouTubeLinkCard
import org.nostr.nostrord.ui.media.INLINE_MEDIA_MAX_HEIGHT
import org.nostr.nostrord.ui.media.INLINE_MEDIA_MAX_WIDTH
import org.nostr.nostrord.ui.media.INLINE_MEDIA_MIN_SIDE
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.GroupView
import org.nostr.nostrord.ui.navigation.LocalFrameNavigator
import org.nostr.nostrord.ui.screens.group.components.GroupHeaderIcon
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily
import org.nostr.nostrord.utils.formatDateTime
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.utils.getImageUrl
import org.nostr.nostrord.utils.isAnimatedImageUrl
import org.nostr.nostrord.utils.isBlockedImageHost
import org.nostr.nostrord.utils.proxyViaWeserv
import org.nostr.nostrord.utils.rememberClipboardWriter
import org.nostr.nostrord.utils.shortNpub

// Type alias to bridge new parser to existing rendering code
private typealias ContentPart = MessageContentParser.ParsedPart
private typealias TextPart = MessageContentParser.ParsedPart.Text
private typealias ImagePart = MessageContentParser.ParsedPart.Image
private typealias LinkPart = MessageContentParser.ParsedPart.Link
private typealias MentionPart = MessageContentParser.ParsedPart.Mention
private typealias CustomEmojiPart = MessageContentParser.ParsedPart.CustomEmoji

// New type aliases for rich text formatting
private typealias BoldPart = MessageContentParser.ParsedPart.Bold
private typealias ItalicPart = MessageContentParser.ParsedPart.Italic
private typealias MonospacePart = MessageContentParser.ParsedPart.Monospace
private typealias StrikethroughPart = MessageContentParser.ParsedPart.Strikethrough
private typealias SpoilerPart = MessageContentParser.ParsedPart.Spoiler
private typealias CodeBlockPart = MessageContentParser.ParsedPart.CodeBlock
private typealias BlockquotePart = MessageContentParser.ParsedPart.Blockquote
private typealias HashtagPart = MessageContentParser.ParsedPart.Hashtag
private typealias VideoPart = MessageContentParser.ParsedPart.Video
private typealias AudioPart = MessageContentParser.ParsedPart.Audio

// Nostr-specific type aliases
private typealias RelayPart = MessageContentParser.ParsedPart.Relay
private typealias CashuPart = MessageContentParser.ParsedPart.Cashu
private typealias CashuRequestPart = MessageContentParser.ParsedPart.CashuRequest

/**
 * Parses message content into parts using the robust MessageContentParser.
 */
private fun parseContent(
    content: String,
    emojiMap: Map<String, String> = emptyMap(),
): List<ContentPart> = MessageContentParser.parse(content, emojiMap)

// Regex matching emoji codepoints: emoticons, dingbats, symbols, supplemental, flags, etc.
private val emojiRegex =
    Regex(
        "[" +
            "\u00A9\u00AE" + // ©®
            "\u200D" + // ZWJ
            "\u203C\u2049" + // ‼⁉
            "\u2122\u2139" + // ™ℹ
            "\u2194-\u21AA" + // arrows
            "\u231A-\u23FF" + // misc technical
            "\u2460-\u24FF" + // enclosed alphanumerics
            "\u25AA-\u27BF" + // geometric shapes, misc symbols
            "\u2934-\u2935" + // arrows
            "\u2B05-\u2B55" + // misc symbols
            "\u3030\u303D\u3297\u3299" + // CJK symbols
            "\uD83C\uDC04-\uD83D\uDEFF" + // misc symbols & pictographs, emoticons, transport
            "\uD83E\uDD00-\uD83E\uDDFF" + // supplemental symbols
            "\uD83E\uDE00-\uD83E\uDEFF" + // extended-A
            "\uD83C\uDDE6-\uD83C\uDDFF" + // regional indicator symbols (flags)
            "\uD83C\uDF00-\uD83C\uDFFF" + // misc symbols
            "\uD83D\uDE00-\uD83D\uDE4F" + // emoticons
            "\uD83D\uDE80-\uD83D\uDEFF" + // transport & map
            "\uFE0E\uFE0F" + // variation selectors
            "\u20E3" + // combining enclosing keycap
            "]+",
    )

/**
 * Appends text to an AnnotatedString, applying [emojiFontFamily] only to emoji segments.
 * Non-emoji text is appended without any font override (inherits from the Text style).
 */
private fun AnnotatedString.Builder.appendWithEmojiFont(
    text: String,
    emojiFontFamily: FontFamily,
) {
    var lastEnd = 0
    emojiRegex.findAll(text).forEach { match ->
        // Append non-emoji text before this match
        if (match.range.first > lastEnd) {
            append(text.substring(lastEnd, match.range.first))
        }
        // Append emoji with NotoColorEmoji font
        withStyle(SpanStyle(fontFamily = emojiFontFamily)) {
            append(match.value)
        }
        lastEnd = match.range.last + 1
    }
    // Append remaining non-emoji text
    if (lastEnd < text.length) {
        append(text.substring(lastEnd))
    }
}

private fun isBlockPart(part: ContentPart): Boolean = when (part) {
    is ImagePart -> true
    is CodeBlockPart -> true
    is BlockquotePart -> true
    is VideoPart -> true
    is AudioPart -> true
    is RelayPart -> true
    is CashuPart -> true
    is CashuRequestPart -> true
    is MentionPart -> {
        // Quoted events (nevent, note, naddr) are block elements
        when (part.reference.entity) {
            is Nip19.Entity.Nevent, is Nip19.Entity.Note, is Nip19.Entity.Naddr -> true
            else -> false
        }
    }
    else -> false
}

/**
 * # MessageContent - Robust Inline Text Rendering
 *
 * This component uses a single AnnotatedString with InlineContent to render
 * mixed content (text, links, mentions, emojis) as truly inline text.
 *
 * ## Why AnnotatedString instead of FlowRow?
 *
 * FlowRow treats each child as a separate layout box, causing:
 * - Mentions to wrap as entire units instead of flowing with text
 * - Whitespace to become independent boxes with inconsistent spacing
 * - Baseline misalignment between text and styled mentions
 *
 * AnnotatedString keeps everything in a single Text composable where:
 * - Line breaking happens at character/word boundaries, not component boundaries
 * - Baseline alignment is consistent across all styled spans
 * - Whitespace flows naturally with surrounding text
 *
 * ## Architecture
 *
 * 1. Parse content into typed parts (Text, Link, Mention, CustomEmoji, Image)
 * 2. Group into inline sequences vs block elements
 * 3. For inline groups: Build single AnnotatedString with:
 *    - SpanStyle for visual styling (color, font weight)
 *    - LinkAnnotation for clickable URLs and mentions
 *    - InlineContent placeholders for custom emojis
 * 4. For block elements: Render as separate composables in Column
 */
@Composable
fun MessageContent(
    content: String,
    tags: List<List<String>> = emptyList(),
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    currentGroupId: String? = null,
    currentRelayUrl: String? = null,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit = { _, _, _, _ -> },
    // Base text color. Defaults to the neutral message color; DM bubbles override it (white on
    // the primary "mine" bubble). Inline accents (links, mentions) keep their own colors.
    textColor: Color = NostrordColors.TextContent,
) {
    // Extract custom emoji map from NIP-30 tags
    val emojiMap = remember(tags) { MessageContentParser.extractEmojiMap(tags) }
    val parts = remember(content, emojiMap) { parseContent(content, emojiMap) }
    // Extract NIP-68 imeta dimension hints for pre-sizing images (avoids layout shift)
    val imetaDimensions = remember(tags) { MessageContentParser.extractImetaDimensions(tags) }
    val imetaThumbnails = remember(tags) { MessageContentParser.extractImetaThumbnails(tags) }
    val uriHandler = LocalUriHandler.current

    // Image viewer modal state — shared from MessagesList so ALL animated images
    // are hidden when any image modal opens (not just this message's GIFs).
    val sharedImageViewerUrl = LocalImageViewerUrl.current
    var selectedImageUrl by sharedImageViewerUrl

    // Collect user metadata for mention display names
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()

    // Settings > Media: when off, inline media renders a tap-to-load placeholder
    // and fetches nothing until the user reveals each item.
    val autoLoadMedia by AppModule.mediaSettings.autoLoadMedia.collectAsState()

    // Group parts into inline sequences and block elements
    val groups =
        remember(parts) {
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

    // Request metadata for all mentions
    LaunchedEffect(parts) {
        val pubkeysToFetch =
            parts
                .filterIsInstance<MentionPart>()
                .mapNotNull { mention ->
                    when (val entity = mention.reference.entity) {
                        is Nip19.Entity.Npub -> entity.pubkey
                        is Nip19.Entity.Nprofile -> entity.pubkey
                        else -> null
                    }
                }.filter { !userMetadata.containsKey(it) }
                .toSet()

        if (pubkeysToFetch.isNotEmpty()) {
            AppModule.nostrRepository.requestUserMetadata(pubkeysToFetch)
        }
    }

    val parentHidden = LocalAnimatedImageHidden.current
    CompositionLocalProvider(LocalAnimatedImageHidden provides (parentHidden || selectedImageUrl != null)) {
        Column(modifier = modifier) {
            groups.forEach { group ->
                val firstPart = group.firstOrNull()

                // Check if this is a block element group (single block element)
                if (group.size == 1 && isBlockPart(firstPart!!)) {
                    when (firstPart) {
                        is ImagePart -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            GatedMedia(
                                autoLoad = autoLoadMedia,
                                label = "image",
                            ) {
                                ChatImage(
                                    imageUrl = firstPart.url,
                                    dimensions = imetaDimensions[firstPart.url],
                                    onClick = { selectedImageUrl = firstPart.url },
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        is CodeBlockPart -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            CodeBlockContent(
                                code = firstPart.code,
                                language = firstPart.language,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        is BlockquotePart -> {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                Box(
                                    modifier =
                                    Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(NostrordColors.TextMuted),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                // Recurse so inline formatting / links / mentions inside the quote
                                // still render; the stripped inner text has no `>` so it can't loop.
                                MessageContent(
                                    content = firstPart.content,
                                    tags = tags,
                                    onMentionClick = onMentionClick,
                                    onHashtagClick = onHashtagClick,
                                    currentGroupId = currentGroupId,
                                    currentRelayUrl = currentRelayUrl,
                                    onNavigateToGroup = onNavigateToGroup,
                                    textColor = textColor,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        is VideoPart -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            val dims = imetaDimensions[firstPart.url]
                            val videoAspect = if (dims != null) dims.first.toFloat() / dims.second.toFloat() else 16f / 9f
                            GatedMedia(
                                autoLoad = autoLoadMedia,
                                label = "video",
                            ) {
                                if (firstPart.videoId != null) {
                                    // YouTube — thumbnail preview, click opens externally
                                    YouTubeLinkCard(
                                        videoId = firstPart.videoId!!,
                                        url = firstPart.url,
                                        onClick = {
                                            try {
                                                uriHandler.openUri(firstPart.url)
                                            } catch (_: Exception) {
                                            }
                                        },
                                    )
                                } else {
                                    // Direct video file — inline platform player.
                                    // Bound to the same 360x300dp box as images and
                                    // the web's .msg-video: the player's aspectRatio
                                    // falls back to the height cap, so a portrait
                                    // video stays contained instead of filling the
                                    // column.
                                    PlatformVideoPlayer(
                                        url = firstPart.url,
                                        thumbnailUrl = imetaThumbnails[firstPart.url],
                                        aspectRatio = videoAspect,
                                        onFallbackClick = {
                                            try {
                                                uriHandler.openUri(firstPart.url)
                                            } catch (_: Exception) {
                                            }
                                        },
                                        modifier = Modifier
                                            .widthIn(max = 360.dp)
                                            .heightIn(max = 300.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        is AudioPart -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            GatedMedia(
                                autoLoad = autoLoadMedia,
                                label = "audio",
                            ) {
                                AudioPlayerContent(url = firstPart.url)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        is MentionPart -> {
                            Spacer(modifier = Modifier.height(4.dp))
                            QuotedEventBlock(
                                mention = firstPart,
                                onClick = {
                                    try {
                                        uriHandler.openUri(firstPart.reference.uri)
                                    } catch (_: Exception) {
                                    }
                                },
                                currentGroupId = currentGroupId,
                                currentRelayUrl = currentRelayUrl,
                                onNavigateToGroup = onNavigateToGroup,
                                onMentionClick = onMentionClick,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        is RelayPart -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            val apostrophe = firstPart.url.indexOf('\'')
                            if (apostrophe > 0) {
                                // NIP-29 group address relay'groupId: the full card only when the
                                // message IS the link; mixed with text it renders as the compact
                                // chip (web parity).
                                if (content.trim() == firstPart.url) {
                                    GroupLinkCard(
                                        groupId = firstPart.url.substring(apostrophe + 1),
                                        relayUrl = firstPart.url.substring(0, apostrophe),
                                        onNavigateToGroup = onNavigateToGroup,
                                    )
                                } else {
                                    GroupMentionChip(
                                        groupId = firstPart.url.substring(apostrophe + 1),
                                        relayUrl = firstPart.url.substring(0, apostrophe),
                                        onNavigateToGroup = onNavigateToGroup,
                                    )
                                }
                            } else {
                                RelayContent(
                                    url = firstPart.url,
                                    onClick = {
                                        try {
                                            val httpUrl =
                                                firstPart.url
                                                    .replace("wss://", "https://")
                                                    .replace("ws://", "http://")
                                            uriHandler.openUri(httpUrl)
                                        } catch (_: Exception) {
                                        }
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        is CashuPart -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            CashuContent(
                                token = firstPart.token,
                                isRequest = false,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        is CashuRequestPart -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            CashuContent(
                                token = firstPart.request,
                                isRequest = true,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        else -> {}
                    }
                } else {
                    // Render inline group as single AnnotatedString
                    InlineContentGroup(
                        parts = group,
                        userMetadata = userMetadata,
                        onMentionClick = onMentionClick,
                        onHashtagClick = onHashtagClick,
                        textColor = textColor,
                    )
                }
            }
        }
    } // CompositionLocalProvider
}

/**
 * Renders a group of inline parts as a single Text with AnnotatedString.
 *
 * ## Custom Emoji Strategy: Conditional Selection Disabling
 *
 * InlineTextContent with images causes crashes in Compose Desktop/Skiko when
 * text selection intersects with inline content placeholders. The crash occurs
 * in SkiaParagraph.getLineForOffset() which returns null for placeholder offsets.
 *
 * **Solution**: When a message contains custom emojis:
 * 1. Wrap the Text in DisableSelection to prevent the crash
 * 2. Use InlineTextContent to render actual emoji images
 *
 * When a message has NO custom emojis:
 * - Render normally with full text selection support
 *
 * This gives us the best of both worlds:
 * - Emoji images display correctly
 * - No crashes
 * - Text selection works for messages without emojis
 */
@Composable
private fun InlineContentGroup(
    parts: List<ContentPart>,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    textColor: Color = NostrordColors.TextContent,
) {
    // Check if this group contains any custom emojis
    val hasCustomEmojis =
        remember(parts) {
            parts.any { it is CustomEmojiPart }
        }

    if (hasCustomEmojis) {
        // Messages with emojis: disable selection to prevent crash, but show images
        DisableSelection {
            InlineContentWithEmojis(
                parts = parts,
                userMetadata = userMetadata,
                modifier = modifier,
                onMentionClick = onMentionClick,
                onHashtagClick = onHashtagClick,
                textColor = textColor,
            )
        }
    } else {
        // Messages without emojis: full selection support
        InlineContentTextOnly(
            parts = parts,
            userMetadata = userMetadata,
            modifier = modifier,
            onMentionClick = onMentionClick,
            onHashtagClick = onHashtagClick,
            textColor = textColor,
        )
    }
}

/**
 * Renders inline content WITH custom emoji images.
 * Must be wrapped in DisableSelection to prevent crashes.
 */
@Composable
private fun InlineContentWithEmojis(
    parts: List<ContentPart>,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    textColor: Color = NostrordColors.TextContent,
) {
    val context = LocalPlatformContext.current
    val emojiUrls =
        remember(parts) {
            parts.filterIsInstance<CustomEmojiPart>().map { it.imageUrl }.distinct()
        }
    LaunchedEffect(emojiUrls) {
        if (emojiUrls.isEmpty()) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        emojiUrls.forEach { url ->
            val request =
                ImageRequest
                    .Builder(context)
                    .data(getImageUrl(url))
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .size(Size(44, 44))
                    .build()
            loader.enqueue(request)
        }
    }

    // Build inline content map keyed by shortcode — one InlineTextContent per
    // unique emoji, reused for every occurrence.  This avoids creating N separate
    // SafeEmojiImage composables when the same emoji appears N times.
    val inlineContentMap =
        remember(parts) {
            parts
                .filterIsInstance<CustomEmojiPart>()
                .distinctBy { it.shortcode }
                .associate { emoji ->
                    emoji.shortcode to
                        InlineTextContent(
                            placeholder =
                            Placeholder(
                                width = 22.sp,
                                height = 22.sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                        ) {
                            SafeEmojiImage(
                                shortcode = emoji.shortcode,
                                imageUrl = emoji.imageUrl,
                            )
                        }
                }
        }

    // Build the annotated string — each emoji references its shortcode key
    val emojiFontFamily = rememberEmojiFontFamily()
    // Spoilers start hidden; tapping one toggles its index here (Discord-style).
    var revealedSpoilers by remember(parts) { mutableStateOf(emptySet<Int>()) }
    val annotatedString =
        remember(parts, userMetadata, emojiFontFamily, revealedSpoilers) {
            var spoilerIndex = 0
            buildAnnotatedString {
                parts.forEach { part ->
                    when (part) {
                        is TextPart -> appendWithEmojiFont(part.content, emojiFontFamily)
                        is LinkPart -> {
                            withLink(
                                LinkAnnotation.Url(
                                    url = part.url,
                                    styles =
                                    TextLinkStyles(
                                        style = SpanStyle(color = NostrordColors.TextLink),
                                    ),
                                ),
                            ) {
                                append(part.url)
                            }
                        }
                        is MentionPart -> {
                            val displayText = getMentionDisplayText(part, userMetadata)
                            val entity = part.reference.entity
                            // For user mentions (npub/nprofile), use clickable to open profile modal
                            // For other mentions (nevent, note), use URL to open in external handler
                            val pubkey =
                                when (entity) {
                                    is Nip19.Entity.Npub -> entity.pubkey
                                    is Nip19.Entity.Nprofile -> entity.pubkey
                                    else -> null
                                }
                            if (pubkey != null) {
                                withLink(
                                    LinkAnnotation.Clickable(
                                        tag = "mention_$pubkey",
                                        styles =
                                        TextLinkStyles(
                                            style =
                                            SpanStyle(
                                                color = NostrordColors.MentionText,
                                                fontWeight = FontWeight.Medium,
                                                // Tinted pill behind the @name approximates the web mention chip.
                                                // (No inline avatar: InlineTextContent images crash on Skiko.)
                                                background = NostrordColors.MentionText.copy(alpha = 0.15f),
                                            ),
                                        ),
                                        linkInteractionListener = { onMentionClick(pubkey) },
                                    ),
                                ) {
                                    append(displayText)
                                }
                            } else {
                                withLink(
                                    LinkAnnotation.Url(
                                        url = part.reference.uri,
                                        styles =
                                        TextLinkStyles(
                                            style =
                                            SpanStyle(
                                                color = NostrordColors.MentionText,
                                                fontWeight = FontWeight.Medium,
                                                // Tinted pill behind the @name approximates the web mention chip.
                                                // (No inline avatar: InlineTextContent images crash on Skiko.)
                                                background = NostrordColors.MentionText.copy(alpha = 0.15f),
                                            ),
                                        ),
                                    ),
                                ) {
                                    append(displayText)
                                }
                            }
                        }
                        is CustomEmojiPart -> {
                            appendInlineContent(
                                part.shortcode,
                                ":${part.shortcode}:",
                            )
                        }
                        // Text formatting
                        is BoldPart -> {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(part.content)
                            }
                        }
                        is ItalicPart -> {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(part.content)
                            }
                        }
                        is MonospacePart -> {
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = NostrordColors.CodeBackground.copy(alpha = 0.5f),
                                    color = NostrordColors.CodeText,
                                ),
                            ) {
                                append(part.content)
                            }
                        }
                        is StrikethroughPart -> {
                            withStyle(
                                SpanStyle(
                                    textDecoration = TextDecoration.LineThrough,
                                    color = NostrordColors.TextMuted,
                                ),
                            ) {
                                append(part.content)
                            }
                        }
                        is SpoilerPart -> {
                            appendSpoiler(part.content, spoilerIndex++, revealedSpoilers) { i ->
                                revealedSpoilers = revealedSpoilers.toggle(i)
                            }
                        }
                        is HashtagPart -> {
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "hashtag_${part.tag}",
                                    styles =
                                    TextLinkStyles(
                                        style = SpanStyle(color = NostrordColors.HashtagText),
                                    ),
                                    linkInteractionListener = { onHashtagClick(part.tag) },
                                ),
                            ) {
                                append("#${part.tag}")
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

    Text(
        text = annotatedString,
        color = textColor,
        style = NostrordTypography.MessageBody,
        inlineContent = inlineContentMap,
        modifier = modifier,
    )
}

private fun Set<Int>.toggle(i: Int): Set<Int> = if (i in this) this - i else this + i

/**
 * Append a spoiler span: hidden as a muted block with transparent text until tapped,
 * then revealed with a floating background. Tap toggles it (LinkAnnotation.Clickable,
 * same tap path as mentions). Mirrors the prototype Discord-style spoiler.
 */
private fun AnnotatedString.Builder.appendSpoiler(
    content: String,
    index: Int,
    revealed: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    val shown = index in revealed
    val style =
        if (shown) {
            SpanStyle(background = NostrordColors.BackgroundFloating, color = NostrordColors.TextContent)
        } else {
            SpanStyle(background = NostrordColors.TextMuted.copy(alpha = 0.3f), color = Color.Transparent)
        }
    withLink(
        LinkAnnotation.Clickable(
            tag = "spoiler_$index",
            styles = TextLinkStyles(style = style),
            linkInteractionListener = { onToggle(index) },
        ),
    ) {
        append(content)
    }
}

/**
 * Renders inline content WITHOUT custom emojis.
 * Supports full text selection.
 */
@Composable
private fun InlineContentTextOnly(
    parts: List<ContentPart>,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    textColor: Color = NostrordColors.TextContent,
) {
    val emojiFontFamily = rememberEmojiFontFamily()
    var revealedSpoilers by remember(parts) { mutableStateOf(emptySet<Int>()) }
    val annotatedString =
        remember(parts, userMetadata, emojiFontFamily, revealedSpoilers) {
            var spoilerIndex = 0
            buildAnnotatedString {
                parts.forEach { part ->
                    when (part) {
                        is TextPart -> appendWithEmojiFont(part.content, emojiFontFamily)
                        is LinkPart -> {
                            withLink(
                                LinkAnnotation.Url(
                                    url = part.url,
                                    styles =
                                    TextLinkStyles(
                                        style = SpanStyle(color = NostrordColors.TextLink),
                                    ),
                                ),
                            ) {
                                append(part.url)
                            }
                        }
                        is MentionPart -> {
                            val displayText = getMentionDisplayText(part, userMetadata)
                            val entity = part.reference.entity
                            // For user mentions (npub/nprofile), use clickable to open profile modal
                            // For other mentions (nevent, note), use URL to open in external handler
                            val pubkey =
                                when (entity) {
                                    is Nip19.Entity.Npub -> entity.pubkey
                                    is Nip19.Entity.Nprofile -> entity.pubkey
                                    else -> null
                                }
                            if (pubkey != null) {
                                withLink(
                                    LinkAnnotation.Clickable(
                                        tag = "mention_$pubkey",
                                        styles =
                                        TextLinkStyles(
                                            style =
                                            SpanStyle(
                                                color = NostrordColors.MentionText,
                                                fontWeight = FontWeight.Medium,
                                                // Tinted pill behind the @name approximates the web mention chip.
                                                // (No inline avatar: InlineTextContent images crash on Skiko.)
                                                background = NostrordColors.MentionText.copy(alpha = 0.15f),
                                            ),
                                        ),
                                        linkInteractionListener = { onMentionClick(pubkey) },
                                    ),
                                ) {
                                    append(displayText)
                                }
                            } else {
                                withLink(
                                    LinkAnnotation.Url(
                                        url = part.reference.uri,
                                        styles =
                                        TextLinkStyles(
                                            style =
                                            SpanStyle(
                                                color = NostrordColors.MentionText,
                                                fontWeight = FontWeight.Medium,
                                                // Tinted pill behind the @name approximates the web mention chip.
                                                // (No inline avatar: InlineTextContent images crash on Skiko.)
                                                background = NostrordColors.MentionText.copy(alpha = 0.15f),
                                            ),
                                        ),
                                    ),
                                ) {
                                    append(displayText)
                                }
                            }
                        }
                        // Text formatting
                        is BoldPart -> {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(part.content)
                            }
                        }
                        is ItalicPart -> {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(part.content)
                            }
                        }
                        is MonospacePart -> {
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = NostrordColors.CodeBackground.copy(alpha = 0.5f),
                                    color = NostrordColors.CodeText,
                                ),
                            ) {
                                append(part.content)
                            }
                        }
                        is StrikethroughPart -> {
                            withStyle(
                                SpanStyle(
                                    textDecoration = TextDecoration.LineThrough,
                                    color = NostrordColors.TextMuted,
                                ),
                            ) {
                                append(part.content)
                            }
                        }
                        is SpoilerPart -> {
                            appendSpoiler(part.content, spoilerIndex++, revealedSpoilers) { i ->
                                revealedSpoilers = revealedSpoilers.toggle(i)
                            }
                        }
                        is HashtagPart -> {
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "hashtag_${part.tag}",
                                    styles =
                                    TextLinkStyles(
                                        style = SpanStyle(color = NostrordColors.HashtagText),
                                    ),
                                    linkInteractionListener = { onHashtagClick(part.tag) },
                                ),
                            ) {
                                append("#${part.tag}")
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

    Text(
        text = annotatedString,
        color = textColor,
        style = NostrordTypography.MessageBody,
        modifier = modifier,
    )
}

/**
 * Get display text for a mention, using cached metadata if available.
 */
private fun getMentionDisplayText(
    mention: MentionPart,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
): String = when (val entity = mention.reference.entity) {
    is Nip19.Entity.Npub -> {
        val metadata = userMetadata[entity.pubkey]
        val name = metadata?.displayName ?: metadata?.name
        if (name != null) "@$name" else Nip19.getDisplayName(entity)
    }
    is Nip19.Entity.Nprofile -> {
        val metadata = userMetadata[entity.pubkey]
        val name = metadata?.displayName ?: metadata?.name
        if (name != null) "@$name" else Nip19.getDisplayName(entity)
    }
    else -> Nip19.getDisplayName(entity)
}

/**
 * Process content to replace nostr: mentions with @name format.
 * Used for plain text display in embedded events like reply previews.
 */
fun processMentionsInContent(
    content: String,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
): String = processMentionsInContent(content) { userMetadata[it] }

fun processMentionsInContent(
    content: String,
    resolveMetadata: (String) -> org.nostr.nostrord.network.UserMetadata?,
): String {
    // Regex to match nostr: URIs (npub, nprofile, note, nevent, naddr)
    val nostrUriRegex =
        Regex("""nostr:(npub1[a-z0-9]+|nprofile1[a-z0-9]+|note1[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+)""", RegexOption.IGNORE_CASE)

    return nostrUriRegex.replace(content) { matchResult ->
        val uri = matchResult.value
        val bech32 = matchResult.groupValues[1]

        try {
            val entity = Nip19.decode(bech32)
            when (entity) {
                is Nip19.Entity.Npub -> {
                    val metadata = resolveMetadata(entity.pubkey)
                    val name = metadata?.displayName ?: metadata?.name
                    if (name != null) "@$name" else "@" + shortNpub(entity.pubkey)
                }
                is Nip19.Entity.Nprofile -> {
                    val metadata = resolveMetadata(entity.pubkey)
                    val name = metadata?.displayName ?: metadata?.name
                    if (name != null) "@$name" else "@" + shortNpub(entity.pubkey)
                }
                is Nip19.Entity.Note -> "[note]"
                is Nip19.Entity.Nevent -> "[event]"
                is Nip19.Entity.Naddr -> if (entity.kind == 39000) "%${entity.identifier}" else "[article]"
                else -> uri
            }
        } catch (_: Exception) {
            uri // Return original if decoding fails
        }
    }
}

/**
 * Extract all pubkeys from nostr: mentions in content.
 * Used to request metadata for mentioned users.
 */
fun extractPubkeysFromContent(content: String): List<String> {
    val nostrUriRegex = Regex("""nostr:(npub1[a-z0-9]+|nprofile1[a-z0-9]+)""", RegexOption.IGNORE_CASE)

    return nostrUriRegex
        .findAll(content)
        .mapNotNull { matchResult ->
            val bech32 = matchResult.groupValues[1]
            try {
                when (val entity = Nip19.decode(bech32)) {
                    is Nip19.Entity.Npub -> entity.pubkey
                    is Nip19.Entity.Nprofile -> entity.pubkey
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }.toList()
}

// =============================================================================
// SAFE EMOJI IMAGE RENDERING
// =============================================================================
//
// Custom emojis are rendered as inline images using InlineTextContent.
// To prevent crashes from SelectionContainer + InlineTextContent interaction,
// messages containing emojis are wrapped in DisableSelection.
//
// Key safety measures:
// 1. URL validation before image loading
// 2. Size constraints to prevent OOM
// 3. Error state with text fallback
// 4. No exceptions can escape to parent composables
// =============================================================================

/**
 * Safe emoji image renderer with comprehensive error handling.
 *
 * Renders custom emoji as an inline image, or falls back to text on failure.
 * Size is fixed at 22dp to match line height.
 */
@Composable
private fun SafeEmojiImage(
    shortcode: String,
    imageUrl: String,
    modifier: Modifier = Modifier,
) {
    // Validate and sanitize inputs
    val safeShortcode =
        remember(shortcode) {
            shortcode
                .take(32)
                .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                .ifEmpty { "emoji" }
        }

    val isValidUrl =
        remember(imageUrl) {
            imageUrl.isNotBlank() &&
                imageUrl.length <= 2048 &&
                (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) &&
                !imageUrl.lowercase().contains("javascript:") &&
                !imageUrl.lowercase().contains("data:") &&
                !isBlockedImageHost(imageUrl)
        }

    // Track error state and CORS proxy retry
    var useProxy by remember(imageUrl) { mutableStateOf(false) }
    var showFallback by remember(imageUrl) { mutableStateOf(!isValidUrl) }

    val effectiveUrl =
        if (useProxy) {
            proxyViaWeserv(imageUrl, width = 44, height = 44)
        } else {
            imageUrl
        }

    if (showFallback) {
        // Text fallback - always safe
        Box(
            modifier = modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ":$safeShortcode:",
                color = NostrordColors.Primary,
                style = NostrordTypography.Caption,
                maxLines = 1,
            )
        }
    } else {
        // EmojiImage is an expect/actual:
        // - JVM Desktop: AnimatedImage (frame-by-frame decode, direct HTTP fetch)
        // - Android/iOS/Web: Coil AsyncImage (platform handles animated formats)
        Box(
            modifier = modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            EmojiImage(
                url = effectiveUrl,
                contentDescription = ":$safeShortcode:",
                modifier = Modifier.size(22.dp),
                contentScale = ContentScale.Fit,
                onError = {
                    if (!useProxy) {
                        useProxy = true
                    } else {
                        showFallback = true
                    }
                },
            )
        }
    }
}

/**
 * Wraps inline chat media with the Settings > Media "auto-load" gate. When
 * [autoLoad] is true (default) [content] renders immediately; when off, a
 * tap-to-load placeholder is shown until the user reveals this single item.
 */
@Composable
private fun GatedMedia(
    autoLoad: Boolean,
    label: String,
    content: @Composable () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    if (autoLoad || revealed) {
        content()
    } else {
        MediaPlaceholder(label = label) { revealed = true }
    }
}

/**
 * Compact tap-to-load placeholder used by [GatedMedia], mirroring the web's
 * pill-sized button (icon + label) rather than a full media-sized box. The icon
 * differs per [label] so image / video / audio are distinguishable at a glance.
 */
@Composable
private fun MediaPlaceholder(
    label: String,
    onClick: () -> Unit,
) {
    val icon =
        when (label) {
            "video" -> Icons.Default.Movie
            "audio" -> Icons.Default.MusicNote
            else -> Icons.Default.Image
        }
    Row(
        modifier =
        Modifier
            .clip(NostrordShapes.imageShape)
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NostrordColors.TextContent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Tap to load $label",
            color = NostrordColors.TextContent,
            style = NostrordTypography.Caption,
        )
    }
}

/**
 * @param dimensions NIP-68 imeta (width, height) hint. When present, the image
 *                   container is pre-sized with the correct aspect ratio so the
 *                   layout doesn't shift when the image finishes loading.
 */
@Composable
private fun ChatImage(
    imageUrl: String,
    dimensions: Pair<Int, Int>? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showError by remember(imageUrl) { mutableStateOf(false) }

    if (showError) {
        Text(
            text = imageUrl,
            color = NostrordColors.TextLink,
            style = NostrordTypography.Link,
            modifier = Modifier.clickable(onClick = onClick),
        )
        return
    }

    // Bound every image to the same 360x300dp box the web uses (.msg-image:
    // max-width 360 / max-height 300), so a tall portrait stays contained instead
    // of filling the column. When NIP-68 dimensions are known we also fix the
    // aspect ratio so the slot is reserved before the load resolves; portrait
    // images match the height cap first, landscape the width cap.
    val sizeModifier =
        if (dimensions != null) {
            val (w, h) = dimensions
            val ratio = w.toFloat() / h.toFloat()
            Modifier
                .widthIn(max = INLINE_MEDIA_MAX_WIDTH.dp)
                .heightIn(max = INLINE_MEDIA_MAX_HEIGHT.dp)
                .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
        } else {
            // No imeta hint: floor the slot so the loading skeleton is visible and the row
            // doesn't grow from nothing when the bitmap resolves.
            Modifier
                .widthIn(max = INLINE_MEDIA_MAX_WIDTH.dp)
                .heightIn(max = INLINE_MEDIA_MAX_HEIGHT.dp)
                .defaultMinSize(minWidth = INLINE_MEDIA_MIN_SIDE.dp, minHeight = INLINE_MEDIA_MIN_SIDE.dp)
        }

    if (isAnimatedImageUrl(imageUrl)) {
        AnimatedImage(
            url = imageUrl,
            modifier =
            modifier
                .then(sizeModifier)
                .clip(NostrordShapes.imageShape),
            onClick = onClick,
            onError = { showError = true },
        )
    } else {
        StaticImage(
            url = imageUrl,
            modifier =
            modifier
                .then(sizeModifier)
                .clip(NostrordShapes.imageShape),
            // With known dimensions the slot already matches the aspect ratio, so
            // fill it. Without them, fit the loaded image inside the cap (preserving
            // aspect, no crop) — same as the web's max-width/max-height scaling.
            contentScale = if (dimensions != null) ContentScale.FillWidth else ContentScale.Fit,
            onClick = onClick,
            onError = { showError = true },
        )
    }
}

/**
 * Block-level quoted event display (for nevent/note mentions).
 */
@Composable
private fun QuotedEventBlock(
    mention: MentionPart,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    currentGroupId: String? = null,
    currentRelayUrl: String? = null,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit = { _, _, _, _ -> },
    onMentionClick: (String) -> Unit = {},
) {
    val entity = mention.reference.entity

    when (entity) {
        is Nip19.Entity.Nevent -> {
            QuotedEvent(
                eventId = entity.eventId,
                relayHints = entity.relays,
                author = entity.author,
                kind = entity.kind,
                onClick = onClick,
                modifier = modifier,
                currentGroupId = currentGroupId,
                currentRelayUrl = currentRelayUrl,
                onNavigateToGroup = onNavigateToGroup,
                onMentionClick = onMentionClick,
            )
        }
        is Nip19.Entity.Note -> {
            QuotedEvent(
                eventId = entity.eventId,
                relayHints = emptyList(),
                author = null,
                kind = null,
                onClick = onClick,
                modifier = modifier,
                currentGroupId = currentGroupId,
                currentRelayUrl = currentRelayUrl,
                onNavigateToGroup = onNavigateToGroup,
                onMentionClick = onMentionClick,
            )
        }
        is Nip19.Entity.Naddr -> {
            if (entity.kind == 39000) {
                GroupLinkCard(
                    groupId = entity.identifier,
                    relayUrl = entity.relays.firstOrNull(),
                    onNavigateToGroup = onNavigateToGroup,
                    modifier = modifier,
                )
            } else {
                AddressableEvent(
                    identifier = entity.identifier,
                    pubkey = entity.pubkey,
                    kind = entity.kind,
                    relayHints = entity.relays,
                    onClick = onClick,
                    modifier = modifier,
                )
            }
        }
        else -> {}
    }
}

/**
 * Extracts source group info from an event's h tag.
 * Returns (groupId, relayUrl) if found, null otherwise.
 */
private fun extractGroupFromEvent(event: CachedEvent): Pair<String, String?>? {
    val hTag = event.tags.find { it.firstOrNull() == "h" } ?: return null
    val groupId = hTag.getOrNull(1) ?: return null
    val relayUrl = hTag.getOrNull(2)
    return groupId to relayUrl
}

/**
 * A quoted kind:11 thread root, rendered as a tappable thread card ("Thread" header + title +
 * snippet) that opens the thread page via [LocalFrameNavigator]. Used by [QuotedEvent] ahead of
 * the forwarded-event rendering; mirrors the web `.thread-quote-card`.
 */
@Composable
private fun ThreadQuoteCard(
    event: CachedEvent,
    eventId: String,
    relayHint: String?,
    currentGroupId: String?,
    modifier: Modifier = Modifier,
) {
    val groupId = extractGroupFromEvent(event)?.first ?: currentGroupId
    val navigate = LocalFrameNavigator.current
    val groupsByRelay by AppModule.nostrRepository.groupsByRelay.collectAsState()
    val groupName = groupId?.let { gid -> groupsByRelay.values.flatten().firstOrNull { it.id == gid }?.name }
        ?.takeIf { it.isNotBlank() }

    val title = event.tags.firstOrNull { it.size >= 2 && (it[0] == "subject" || it[0] == "title") }
        ?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        ?: event.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80)
        ?: "Thread"
    val snippet = event.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        ?.take(140)?.takeIf { it != title }

    val openThread: (() -> Unit)? =
        if (navigate != null && groupId != null && relayHint != null) {
            { navigate(GroupRoute(relayHint, groupId, view = GroupView.Threads, threadRootId = eventId)) }
        } else {
            null
        }

    DisableSelection {
        Column(
            modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(NostrordColors.Surface)
                .then(
                    if (openThread != null) {
                        Modifier.clickable(onClick = openThread).pointerHoverIcon(PointerIcon.Hand)
                    } else {
                        Modifier
                    },
                ).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Forum,
                    contentDescription = null,
                    tint = NostrordColors.Primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    "Thread",
                    color = NostrordColors.Primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (groupName != null) {
                    Text(
                        "· $groupName",
                        color = NostrordColors.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                title,
                color = NostrordColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (snippet != null) {
                Text(
                    snippet,
                    color = NostrordColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Forwarded event card - displays an event that originated from a different group.
 * Shows "forwarded from [group name]" header with group info, author info,
 * optional reply preview (if q tag exists), and the message content.
 *
 * Clicking the header navigates to the source group:
 * - If on same relay: navigates directly to the group
 * - If on different relay: switches relay first, then navigates to group
 */
@Composable
fun ForwardedEventCard(
    event: CachedEvent,
    sourceGroupId: String,
    sourceGroupName: String?,
    sourceGroupPicture: String?,
    sourceRelayUrl: String?,
    onClick: () -> Unit,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
) {
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val cachedEvents by AppModule.nostrRepository.cachedEvents.collectAsState()
    val copyToClipboard = rememberClipboardWriter()
    var showMenu by remember { mutableStateOf(false) }

    val authorMetadata = userMetadata[event.pubkey]
    val authorName = authorMetadata?.displayName ?: authorMetadata?.name ?: shortNpub(event.pubkey)

    // Check for reply (q tag) - indicates this event is a reply to another
    val replyEventId = event.tags.find { it.firstOrNull() == "q" }?.getOrNull(1)
    val replyEvent = replyEventId?.let { cachedEvents[it] }

    // Request reply event if we have a q tag but don't have the event cached
    LaunchedEffect(replyEventId) {
        if (replyEventId != null && !cachedEvents.containsKey(replyEventId)) {
            // Extract relay hint from q tag if available
            val qTag = event.tags.find { it.firstOrNull() == "q" }
            val relayHint = qTag?.getOrNull(2)?.let { listOf(it) } ?: emptyList()
            AppModule.nostrRepository.requestEventById(replyEventId, relayHint, null)
        }
    }

    // Request author metadata
    LaunchedEffect(event.pubkey) {
        if (!userMetadata.containsKey(event.pubkey)) {
            AppModule.nostrRepository.requestUserMetadata(setOf(event.pubkey))
        }
    }

    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick),
    ) {
        // Forwarded header - clickable to navigate to source group
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    onNavigateToGroup(sourceGroupId, sourceGroupName, sourceRelayUrl, event.id)
                }.pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            // Forward arrow icon
            Text(
                text = "↪",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption,
            )
            Text(
                text = "forwarded from",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption,
            )
            // Group avatar (small). Seeded-gradient fallback when there's no picture, so the
            // group always shows an avatar (parity with the web card / GroupLinkCard).
            GroupHeaderIcon(
                pictureUrl = sourceGroupPicture,
                groupId = sourceGroupId,
                displayName = sourceGroupName ?: sourceGroupId,
                size = 16.dp,
                cornerRadius = 4.dp,
            )
            // Group name (with underline on hover style)
            Text(
                text = sourceGroupName ?: sourceGroupId.take(12) + "...",
                color = NostrordColors.Primary,
                style = NostrordTypography.Caption,
                fontWeight = FontWeight.Medium,
            )
            // Relay indicator if available
            if (sourceRelayUrl != null) {
                Text(
                    text = "@ ${sourceRelayUrl.removePrefix("wss://").removePrefix("ws://").take(20)}",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption,
                )
            }
        }

        // Divider
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NostrordColors.Background),
        )

        // Main content area
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
        ) {
            // Author row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OptimizedUserAvatar(
                    imageUrl = authorMetadata?.picture,
                    pubkey = event.pubkey,
                    displayName = authorName,
                    size = 24.dp,
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = authorName,
                    color = NostrordColors.TextSecondary,
                    style = NostrordTypography.Caption,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatTime(event.createdAt),
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption,
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                // 3-dot menu button
                DisableSelection {
                    Box {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "More options",
                            tint = NostrordColors.TextMuted,
                            modifier =
                            Modifier
                                .size(20.dp)
                                .clickable { showMenu = true }
                                .pointerHoverIcon(PointerIcon.Hand),
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Event JSON") },
                                onClick = {
                                    val json =
                                        buildJsonObject {
                                            put("id", event.id)
                                            put("pubkey", event.pubkey)
                                            put("created_at", event.createdAt)
                                            put("kind", event.kind)
                                            put(
                                                "tags",
                                                buildJsonArray {
                                                    event.tags.forEach { tag ->
                                                        add(
                                                            buildJsonArray {
                                                                tag.forEach { add(JsonPrimitive(it)) }
                                                            },
                                                        )
                                                    }
                                                },
                                            )
                                            put("content", event.content)
                                        }.toString()
                                    copyToClipboard(json)
                                    showMenu = false
                                },
                            )
                        }
                    }
                }
            }

            // Reply preview (if q tag exists and we have the parent event)
            if (replyEvent != null) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                ReplyPreview(
                    parentEvent = replyEvent,
                    userMetadata = userMetadata,
                )
            } else if (replyEventId != null) {
                // Show loading state for reply
                Spacer(modifier = Modifier.height(Spacing.sm))
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(NostrordShapes.radiusSmall))
                        .background(NostrordColors.Background)
                        .padding(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "↳",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = "Loading reply...",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                    )
                }
            }

            // Message content
            Spacer(modifier = Modifier.height(Spacing.sm))
            QuotedEventContent(content = event.content, tags = event.tags, onMentionClick = onMentionClick)
        }
    }
}

/**
 * Reply preview bar - shows the parent message being replied to.
 */
@Composable
private fun ReplyPreview(
    parentEvent: CachedEvent,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    modifier: Modifier = Modifier,
) {
    val parentAuthorMetadata = userMetadata[parentEvent.pubkey]
    val parentAuthorName =
        parentAuthorMetadata?.displayName
            ?: parentAuthorMetadata?.name
            ?: shortNpub(parentEvent.pubkey)

    // Request metadata for any pubkeys mentioned in the content
    LaunchedEffect(parentEvent.content) {
        val pubkeysToFetch =
            extractPubkeysFromContent(parentEvent.content)
                .filter { !userMetadata.containsKey(it) }
                .toSet()
        if (pubkeysToFetch.isNotEmpty()) {
            AppModule.nostrRepository.requestUserMetadata(pubkeysToFetch)
        }
    }

    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NostrordShapes.radiusSmall))
            .background(NostrordColors.Background)
            .padding(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        // Reply arrow
        Text(
            text = "↳",
            color = NostrordColors.TextMuted,
            style = NostrordTypography.Caption,
        )
        Spacer(modifier = Modifier.width(Spacing.xs))

        // Small avatar
        OptimizedUserAvatar(
            imageUrl = parentAuthorMetadata?.picture,
            pubkey = parentEvent.pubkey,
            displayName = parentAuthorName,
            size = 16.dp,
        )
        Spacer(modifier = Modifier.width(Spacing.xs))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = parentAuthorName,
                color = NostrordColors.TextSecondary,
                style = NostrordTypography.Caption,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            // Process mentions in content to show @name instead of nostr:npub...
            val processedContent =
                remember(parentEvent.content, userMetadata) {
                    processMentionsInContent(parentEvent.content, userMetadata)
                }
            Text(
                text = processedContent.take(100) + if (processedContent.length > 100) "..." else "",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun QuotedEvent(
    eventId: String,
    relayHints: List<String> = emptyList(),
    author: String? = null,
    kind: Int? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    currentGroupId: String? = null,
    currentRelayUrl: String? = null,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit = { _, _, _, _ -> },
    onMentionClick: (String) -> Unit = {},
) {
    val cachedEvents by AppModule.nostrRepository.cachedEvents.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val groups by AppModule.nostrRepository.groups.collectAsState()
    val groupsByRelay by AppModule.nostrRepository.groupsByRelay.collectAsState()
    val event = cachedEvents[eventId]

    // Track if event was not found after timeout
    var eventNotFound by remember { mutableStateOf(false) }

    // Bumped by the not-found card's Retry button to re-run the fetch effect: the relay the event
    // lives on is often connected only after the card first rendered.
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(eventId, relayHints, author, retryTick) {
        if (!cachedEvents.containsKey(eventId)) {
            eventNotFound = false
            AppModule.nostrRepository.requestEventById(eventId, relayHints, author)
            // Poll every 500ms up to 5s — yields the coroutine instead of blocking 5s solid
            repeat(10) {
                kotlinx.coroutines.delay(500)
                if (cachedEvents.containsKey(eventId)) return@LaunchedEffect
            }
            eventNotFound = true
        }
    }

    // Reset not found state when event arrives
    LaunchedEffect(event) {
        if (event != null) {
            eventNotFound = false
        }
    }

    LaunchedEffect(event?.pubkey) {
        val pubkey = event?.pubkey
        if (pubkey != null && !userMetadata.containsKey(pubkey)) {
            AppModule.nostrRepository.requestUserMetadata(setOf(pubkey))
        }
    }

    // Check if this is a kind 30040 (book) event - render as BookCard
    if (event?.kind == 30040) {
        val metadata = userMetadata[event.pubkey]
        val authorName = metadata?.displayName ?: metadata?.name ?: shortNpub(event.pubkey)

        BookCard(
            event = event,
            authorName = authorName,
            authorPicture = metadata?.picture,
            pubkey = event.pubkey,
            onClick = onClick,
            modifier = modifier,
        )
        return
    }

    // A quoted kind:11 renders as a thread card that opens the thread page - both the
    // "Share to chat" announcement and any pasted thread nevent land here. Takes precedence
    // over the forwarded-event rendering (a thread is a destination, not a chat quote).
    if (event?.kind == 11) {
        ThreadQuoteCard(
            event = event,
            eventId = eventId,
            relayHint = extractGroupFromEvent(event)?.second ?: relayHints.firstOrNull() ?: currentRelayUrl,
            currentGroupId = currentGroupId,
            modifier = modifier,
        )
        return
    }

    // Check if this event is from a different group (forwarded)
    if (event != null) {
        val sourceGroupInfo = extractGroupFromEvent(event)
        if (sourceGroupInfo != null) {
            val (sourceGroupId, sourceRelayUrl) = sourceGroupInfo

            // Determine if this is a forwarded event
            // It's forwarded if: we have a current group context AND the source group is different
            // OR if the source relay is different from current relay
            val isFromDifferentGroup =
                when {
                    currentGroupId != null && sourceGroupId != currentGroupId -> true
                    currentRelayUrl != null &&
                        sourceRelayUrl != null &&
                        sourceRelayUrl != currentRelayUrl -> true
                    // If no current context provided, assume it's a quote from same group (not forwarded)
                    else -> false
                }

            if (isFromDifferentGroup) {
                // Look up the source group across ALL relays (not just the current one) so a
                // cross-relay forwarded group still shows its name + avatar; fetch a preview from
                // the relay hint when we don't know it yet (parity with web / GroupLinkCard).
                val sourceGroup =
                    groupsByRelay.values.flatten().find { it.id == sourceGroupId }
                        ?: groups.find { it.id == sourceGroupId }
                val previewRelay = sourceRelayUrl ?: relayHints.firstOrNull()
                LaunchedEffect(sourceGroupId, previewRelay, sourceGroup?.name) {
                    if (sourceGroup?.name == null && previewRelay != null) {
                        AppModule.nostrRepository.fetchGroupPreview(sourceGroupId, previewRelay)
                    }
                }

                ForwardedEventCard(
                    event = event,
                    sourceGroupId = sourceGroupId,
                    sourceGroupName = sourceGroup?.name,
                    sourceGroupPicture = sourceGroup?.picture,
                    // The `h` tag rarely carries a relay; fall back to the nevent's relay hint so
                    // the click opens the group on the relay the event actually lives on (the nav
                    // effect connects it on demand), matching web.
                    sourceRelayUrl = sourceRelayUrl ?: relayHints.firstOrNull(),
                    onClick = onClick,
                    onNavigateToGroup = onNavigateToGroup,
                    modifier = modifier,
                    onMentionClick = onMentionClick,
                )
                return
            }
        }
    }

    // Event not found - non-clickable with selectable text showing full parsed content
    if (event == null && eventNotFound) {
        val copyToClipboard = rememberClipboardWriter()
        var showMenu by remember { mutableStateOf(false) }

        Row(
            modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
                .background(NostrordColors.Surface)
                .padding(Spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        text = "⚠",
                        style = NostrordTypography.MessageBody,
                        color = NostrordColors.TextMuted,
                    )
                    Text(
                        // Deleted and unreachable look identical from here (the relay just does
                        // not serve it), so the copy must not claim either one.
                        text = "Event removed or unavailable",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                    )
                    RetryChip(onClick = { retryTick++ })
                }
                // Show full parsed content
                if (kind != null) {
                    Text(
                        text = "kind: $kind",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                    )
                }
                Text(
                    text = "id: $eventId",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption,
                )
                if (author != null) {
                    Text(
                        text = "author: $author",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                    )
                }
                if (relayHints.isNotEmpty()) {
                    Text(
                        text = "relays: ${relayHints.joinToString(", ")}",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                    )
                }
            }
            // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict with parent SelectionContainer
            DisableSelection {
                Box {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "More options",
                        tint = NostrordColors.TextMuted,
                        modifier =
                        Modifier
                            .size(20.dp)
                            .clickable { showMenu = true }
                            .pointerHoverIcon(PointerIcon.Hand),
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy Parsed JSON") },
                            onClick = {
                                val json =
                                    buildJsonObject {
                                        put("type", if (kind != null) "nevent" else "note")
                                        put("event_id", eventId)
                                        if (kind != null) put("kind", kind)
                                        if (author != null) put("author", author)
                                        put(
                                            "relays",
                                            buildJsonArray {
                                                relayHints.forEach { add(JsonPrimitive(it)) }
                                            },
                                        )
                                    }.toString()
                                copyToClipboard(json)
                                showMenu = false
                            },
                        )
                    }
                }
            }
        }
        return
    }

    // Still loading
    if (event == null) {
        Row(
            modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
                .background(NostrordColors.Surface)
                .padding(Spacing.md),
        ) {
            Text(
                text = "Loading event...",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption,
            )
        }
        return
    }

    // Default rendering for other event kinds
    val metadata = userMetadata[event.pubkey]
    val authorName = metadata?.displayName ?: metadata?.name ?: shortNpub(event.pubkey)
    val uriHandler = LocalUriHandler.current
    val copyToClipboard = rememberClipboardWriter()
    // Linux desktop (GNOME/Wayland) can't reliably raise the browser to the front, so copy the
    // link there instead of opening it. macOS, Windows, mobile and web all focus fine and open.

    Row(
        modifier =
        modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(NostrordShapes.radiusSmall))
            .background(NostrordColors.InputBackground),
    ) {
        Column(
            modifier =
            Modifier
                .weight(1f)
                .padding(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OptimizedUserAvatar(
                    imageUrl = metadata?.picture,
                    pubkey = event.pubkey,
                    displayName = authorName,
                    size = 24.dp,
                    modifier =
                    Modifier
                        .clickable { onMentionClick(event.pubkey) }
                        .pointerHoverIcon(PointerIcon.Hand),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = authorName,
                        color = NostrordColors.TextPrimary,
                        style = NostrordTypography.Caption,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .clickable { onMentionClick(event.pubkey) }
                            .pointerHoverIcon(PointerIcon.Hand),
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = formatDateTime(event.createdAt),
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.sm))
                DisableSelection {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Android only: open via the nostr: URI so the OS offers the user's
                        // installed Nostr clients (desktop has no such handler).
                        if (isAndroid) {
                            Icon(
                                imageVector = Icons.Filled.Apps,
                                contentDescription = "Open in installed client",
                                tint = NostrordColors.TextMuted,
                                modifier =
                                Modifier
                                    .size(16.dp)
                                    .clickable { onClick() }
                                    .pointerHoverIcon(PointerIcon.Hand),
                            )
                            Spacer(modifier = Modifier.width(Spacing.md))
                        }
                        // Open the quoted event on the web (touch/web), or copy the link on
                        // desktop where the browser can't be reliably brought to the front.
                        Icon(
                            imageVector = if (isLinuxDesktop) Icons.Filled.ContentCopy else Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = if (isLinuxDesktop) "Copy link" else "Open on the web",
                            tint = NostrordColors.TextMuted,
                            modifier =
                            Modifier
                                .size(16.dp)
                                .clickable {
                                    val url = "https://jumble.social/notes/${Nip19.encodeNote(event.id)}"
                                    if (isLinuxDesktop) {
                                        copyToClipboard(url)
                                        AppModule.postSystemMessage("Link copied")
                                    } else {
                                        try {
                                            uriHandler.openUri(url)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                                .pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            // A NIP-84 highlight is comment + quoted excerpt + source, not one body of text.
            if (Nip84.isHighlight(event.kind)) {
                HighlightBody(
                    highlight = remember(event.id) { Nip84.parse(event.content, event.tags) },
                    tags = event.tags,
                    onMentionClick = onMentionClick,
                )
            } else {
                QuotedEventContent(content = event.content, tags = event.tags, onMentionClick = onMentionClick)
            }
        }
    }
}

/**
 * NIP-84 highlight body: the highlighter's comment, the excerpt as a quote block, and a link to
 * the source it was taken from.
 */
@Composable
private fun HighlightBody(
    highlight: Nip84.Highlight,
    tags: List<List<String>>,
    onMentionClick: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val copyToClipboard = rememberClipboardWriter()

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        highlight.comment?.let { comment ->
            QuotedEventContent(content = comment, tags = tags, onMentionClick = onMentionClick)
        }
        if (highlight.excerpt.isNotEmpty()) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    modifier =
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(NostrordShapes.radiusSmall))
                        .background(NostrordColors.Primary),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = highlight.excerpt,
                    color = NostrordColors.TextPrimary,
                    style = NostrordTypography.MessageBody,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
        val label = highlight.sourceLabel
        val url = highlight.sourceUrl
        if (label != null && url != null) {
            DisableSelection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "From ",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                    )
                    Text(
                        text = label,
                        color = NostrordColors.Primary,
                        style = NostrordTypography.Caption,
                        textDecoration = TextDecoration.Underline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                        Modifier
                            .clickable {
                                // Linux desktop can't raise the browser reliably, same as the card's open action.
                                if (isLinuxDesktop) {
                                    copyToClipboard(url)
                                    AppModule.postSystemMessage("Link copied")
                                } else {
                                    try {
                                        uriHandler.openUri(url)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            .pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            }
        }
    }
}

/** Retry affordance on a reference card whose event never resolved. */
@Composable
private fun RetryChip(onClick: () -> Unit) {
    DisableSelection {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            modifier =
            Modifier
                .clip(RoundedCornerShape(NostrordShapes.radiusSmall))
                .clickable { onClick() }
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Retry",
                tint = NostrordColors.Primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Retry",
                color = NostrordColors.Primary,
                style = NostrordTypography.Caption,
            )
        }
    }
}

/**
 * Content inside a quoted event - uses same AnnotatedString approach.
 */
@Composable
private fun QuotedEventContent(
    content: String,
    tags: List<List<String>> = emptyList(),
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
) {
    // Extract custom emoji map from NIP-30 tags
    val emojiMap = remember(tags) { MessageContentParser.extractEmojiMap(tags) }
    val parts = remember(content, emojiMap) { parseContent(content, emojiMap) }
    val uriHandler = LocalUriHandler.current
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()

    // Resolve names for pubkeys mentioned inside the quoted text (nostr:npub / nprofile),
    // so they render as @name instead of a truncated key (matches the web card). Keyed on
    // content only: re-keying on userMetadata would re-request forever for keys that never resolve.
    LaunchedEffect(content) {
        val pubkeysToFetch =
            extractPubkeysFromContent(content)
                .filter { !userMetadata.containsKey(it) }
                .toSet()
        if (pubkeysToFetch.isNotEmpty()) {
            AppModule.nostrRepository.requestUserMetadata(pubkeysToFetch)
        }
    }

    // Image viewer modal state — use shared state from MessagesList
    val sharedImageViewerUrl = LocalImageViewerUrl.current
    var selectedImageUrl by sharedImageViewerUrl

    // Group parts into inline sequences and block elements
    val groups =
        remember(parts) {
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
                    is ImagePart -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        QuotedImage(
                            imageUrl = firstPart.url,
                            onClick = { selectedImageUrl = firstPart.url },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is MentionPart -> {
                        // Nested quoted event - show as simple link
                        Text(
                            text = Nip19.getDisplayName(firstPart.reference.entity),
                            color = NostrordColors.MentionText,
                            style = NostrordTypography.Caption,
                            fontWeight = FontWeight.Medium,
                            modifier =
                            Modifier.clickable {
                                try {
                                    uriHandler.openUri(firstPart.reference.uri)
                                } catch (_: Exception) {
                                }
                            },
                        )
                    }
                    is VideoPart -> {
                        // Quoted event with a video: same players as the main message
                        // (these were silently dropped before, so a nevent pointing at a
                        // video rendered as an empty card).
                        Spacer(modifier = Modifier.height(6.dp))
                        if (firstPart.videoId != null) {
                            YouTubeLinkCard(
                                videoId = firstPart.videoId!!,
                                url = firstPart.url,
                                onClick = {
                                    try {
                                        uriHandler.openUri(firstPart.url)
                                    } catch (_: Exception) {
                                    }
                                },
                            )
                        } else {
                            PlatformVideoPlayer(
                                url = firstPart.url,
                                thumbnailUrl = null,
                                aspectRatio = 16f / 9f,
                                onFallbackClick = {
                                    try {
                                        uriHandler.openUri(firstPart.url)
                                    } catch (_: Exception) {
                                    }
                                },
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .heightIn(max = 220.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is AudioPart -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        AudioPlayerContent(url = firstPart.url)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is CodeBlockPart -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        CodeBlockContent(code = firstPart.code, language = firstPart.language)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is RelayPart -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        RelayContent(
                            url = firstPart.url,
                            onClick = {
                                try {
                                    uriHandler.openUri(
                                        firstPart.url.replace("wss://", "https://").replace("ws://", "http://"),
                                    )
                                } catch (_: Exception) {
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is CashuPart -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        CashuContent(token = firstPart.token, isRequest = false)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is CashuRequestPart -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        CashuContent(token = firstPart.request, isRequest = true)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    else -> {}
                }
            } else {
                // Render inline group using AnnotatedString
                QuotedInlineContentGroup(
                    parts = group,
                    userMetadata = userMetadata,
                    onLinkClick = { url ->
                        try {
                            uriHandler.openUri(url)
                        } catch (_: Exception) {
                        }
                    },
                    onMentionClick = onMentionClick,
                )
            }
        }
    }
}

/**
 * Inline content group for quoted events - uses Caption style.
 * Supports custom emojis via InlineTextContent.
 */
@Composable
private fun QuotedInlineContentGroup(
    parts: List<ContentPart>,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
) {
    // Check if this group contains any custom emojis
    val hasCustomEmojis =
        remember(parts) {
            parts.any { it is CustomEmojiPart }
        }

    val quotedContext = LocalPlatformContext.current
    val quotedEmojiUrls =
        remember(parts) {
            if (!hasCustomEmojis) {
                emptyList()
            } else {
                parts.filterIsInstance<CustomEmojiPart>().map { it.imageUrl }.distinct()
            }
        }
    LaunchedEffect(quotedEmojiUrls) {
        if (quotedEmojiUrls.isEmpty()) return@LaunchedEffect
        val loader = SingletonImageLoader.get(quotedContext)
        quotedEmojiUrls.forEach { url ->
            val request =
                ImageRequest
                    .Builder(quotedContext)
                    .data(getImageUrl(url))
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .size(Size(36, 36))
                    .build()
            loader.enqueue(request)
        }
    }

    // Build inline content map for custom emojis with unique sequential IDs
    val inlineContentMap =
        remember(parts) {
            if (!hasCustomEmojis) return@remember emptyMap()
            var emojiIndex = 0
            parts
                .filterIsInstance<CustomEmojiPart>()
                .associate { emoji ->
                    val id = "quoted_emoji_${emojiIndex++}_${emoji.shortcode}"
                    id to
                        InlineTextContent(
                            placeholder =
                            Placeholder(
                                width = 18.sp, // Smaller for caption style
                                height = 18.sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                        ) {
                            SafeEmojiImage(
                                shortcode = emoji.shortcode,
                                imageUrl = emoji.imageUrl,
                            )
                        }
                }
        }

    val emojiFontFamily = rememberEmojiFontFamily()
    val annotatedString =
        remember(parts, userMetadata, emojiFontFamily) {
            var emojiIndex = 0
            buildAnnotatedString {
                parts.forEach { part ->
                    when (part) {
                        is TextPart -> {
                            appendWithEmojiFont(part.content, emojiFontFamily)
                        }
                        is LinkPart -> {
                            withLink(
                                LinkAnnotation.Url(
                                    url = part.url,
                                    styles =
                                    TextLinkStyles(
                                        style = SpanStyle(color = NostrordColors.Primary),
                                    ),
                                ),
                            ) {
                                append(part.url)
                            }
                        }
                        is MentionPart -> {
                            val displayText = getMentionDisplayText(part, userMetadata)
                            // User mentions (npub/nprofile) open the profile; event/group refs have
                            // no profile, so they keep the URL handler.
                            val mentionPubkey =
                                when (val mentionEntity = part.reference.entity) {
                                    is Nip19.Entity.Npub -> mentionEntity.pubkey
                                    is Nip19.Entity.Nprofile -> mentionEntity.pubkey
                                    else -> null
                                }
                            if (mentionPubkey != null) {
                                withLink(
                                    LinkAnnotation.Clickable(
                                        tag = "mention_$mentionPubkey",
                                        styles =
                                        TextLinkStyles(
                                            style =
                                            SpanStyle(
                                                color = NostrordColors.MentionText,
                                                fontWeight = FontWeight.Medium,
                                                // Tinted pill behind the @name approximates the web mention chip.
                                                // (No inline avatar: InlineTextContent images crash on Skiko.)
                                                background = NostrordColors.MentionText.copy(alpha = 0.15f),
                                            ),
                                        ),
                                        linkInteractionListener = { onMentionClick(mentionPubkey) },
                                    ),
                                ) {
                                    append(displayText)
                                }
                            } else {
                                withLink(
                                    LinkAnnotation.Url(
                                        url = part.reference.uri,
                                        styles =
                                        TextLinkStyles(
                                            style =
                                            SpanStyle(
                                                color = NostrordColors.MentionText,
                                                fontWeight = FontWeight.Medium,
                                                // Tinted pill behind the @name approximates the web mention chip.
                                                // (No inline avatar: InlineTextContent images crash on Skiko.)
                                                background = NostrordColors.MentionText.copy(alpha = 0.15f),
                                            ),
                                        ),
                                    ),
                                ) {
                                    append(displayText)
                                }
                            }
                        }
                        is CustomEmojiPart -> {
                            appendInlineContent(
                                "quoted_emoji_${emojiIndex++}_${part.shortcode}",
                                ":${part.shortcode}:",
                            )
                        }
                        else -> {}
                    }
                }
            }
        }

    if (hasCustomEmojis) {
        // Wrap in DisableSelection to prevent crashes with InlineTextContent
        DisableSelection {
            Text(
                text = annotatedString,
                color = NostrordColors.TextContent,
                style = NostrordTypography.Caption,
                inlineContent = inlineContentMap,
                modifier = modifier,
            )
        }
    } else {
        Text(
            text = annotatedString,
            color = NostrordColors.TextContent,
            style = NostrordTypography.Caption,
            modifier = modifier,
        )
    }
}

@Composable
private fun QuotedImage(
    imageUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var showError by remember { mutableStateOf(isBlockedImageHost(imageUrl)) }

    if (showError) {
        Text(
            text = imageUrl,
            color = NostrordColors.Primary,
            style = NostrordTypography.Caption,
            modifier = Modifier.clickable(onClick = onClick),
        )
    } else {
        Box(
            modifier =
            modifier
                .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
                .background(NostrordColors.BackgroundDark)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model =
                ImageRequest
                    .Builder(context)
                    .data(getImageUrl(imageUrl))
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = "Image",
                contentScale = ContentScale.FillWidth,
                filterQuality = FilterQuality.Medium,
                modifier =
                Modifier
                    .widthIn(max = 280.dp)
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(NostrordShapes.radiusMedium)),
                onState = { state ->
                    imageState = state
                    if (state is AsyncImagePainter.State.Error) {
                        showError = true
                    }
                },
            )

            if (imageState is AsyncImagePainter.State.Loading) {
                Box(
                    modifier =
                    Modifier
                        .widthIn(min = 150.dp)
                        .heightIn(min = 80.dp)
                        .background(NostrordColors.BackgroundDark, RoundedCornerShape(NostrordShapes.radiusMedium)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = NostrordColors.Primary,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

// ============================================================================
// BLOCK COMPOSABLES FOR NEW RICH TEXT TYPES
// ============================================================================

/**
 * Renders a fenced code block with optional language label.
 */
@Composable
private fun CodeBlockContent(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(NostrordColors.CodeBackground),
    ) {
        // Language badge (if present)
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        // Code content (horizontally scrollable for long lines)
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = code,
                style = NostrordTypography.MessageBody.copy(fontFamily = FontFamily.Monospace),
                color = NostrordColors.CodeText,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/**
 * Renders a Nostr relay URL with a relay icon.
 */
@Composable
private fun RelayContent(
    url: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Relay icon
        Box(
            modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NostrordColors.Primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⚡",
                style = NostrordTypography.MessageBody,
                color = NostrordColors.Primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        // Relay URL
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = url,
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextContent,
                maxLines = 1,
            )
            Text(
                text = "Nostr Relay",
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
            )
        }
    }
}

/**
 * Renders a Cashu ecash token or payment request.
 */
@Composable
private fun CashuContent(
    token: String,
    isRequest: Boolean,
    modifier: Modifier = Modifier,
) {
    val copyToClipboard = rememberClipboardWriter()

    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .clickable {
                // Copy token to clipboard
                copyToClipboard(token)
            }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cashu icon
        Box(
            modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    if (isRequest) {
                        NostrordColors.MentionText.copy(alpha = 0.2f)
                    } else {
                        NostrordColors.Primary.copy(alpha = 0.2f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🥜",
                style = NostrordTypography.MessageBody,
            )
        }
        Spacer(Modifier.width(12.dp))
        // Token preview
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isRequest) "Cashu Request" else "Cashu Token",
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextContent,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = token.take(32) + "...",
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
                maxLines = 1,
            )
            Text(
                text = "Tap to copy",
                style = NostrordTypography.Caption,
                color = NostrordColors.TextLink,
            )
        }
    }
}

/**
 * Renders a NIP-29 group link (kind 39000 naddr) as a clickable card.
 * Connects to the relay in background to fetch group metadata.
 */
@Composable
private fun GroupLinkCard(
    groupId: String,
    relayUrl: String?,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = AppModule.nostrRepository
    val groups by repo.groups.collectAsState()
    val groupsByRelay by repo.groupsByRelay.collectAsState()

    val groupMeta =
        groups.find { it.id == groupId }
            ?: groupsByRelay.values.flatten().find { it.id == groupId }

    LaunchedEffect(groupId, relayUrl) {
        if (relayUrl != null && groupMeta?.name == null) {
            repo.fetchGroupPreview(groupId, relayUrl)
        }
    }

    val displayName = groupMeta?.name ?: groupId
    val relayDisplay = relayUrl?.removePrefix("wss://")?.removePrefix("ws://")

    DisableSelection {
        Row(
            modifier =
            modifier
                .fillMaxWidth()
                // Match the web group-link-card cap (.group-link-card max-width: 380px).
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(NostrordColors.Surface)
                .clickable { onNavigateToGroup(groupId, groupMeta?.name, relayUrl, null) }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Seeded-gradient group avatar (or the group picture), same object as the web card.
            GroupHeaderIcon(
                pictureUrl = groupMeta?.picture,
                groupId = groupId,
                displayName = displayName,
                size = 36.dp,
                cornerRadius = 8.dp,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = NostrordColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (groupMeta?.about != null) {
                    Text(
                        text = groupMeta.about,
                        color = NostrordColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (relayDisplay != null) {
                    Text(
                        text = relayDisplay,
                        color = NostrordColors.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Compact chip for a group reference mixed with running text (group avatar + name); the full
 * [GroupLinkCard] is reserved for a message that is only the group link. Mirrors the web
 * `.msg-mention-chip` rendering of inline group refs.
 */
@Composable
private fun GroupMentionChip(
    groupId: String,
    relayUrl: String?,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?, messageId: String?) -> Unit,
) {
    val repo = AppModule.nostrRepository
    val groups by repo.groups.collectAsState()
    val groupsByRelay by repo.groupsByRelay.collectAsState()
    val groupMeta =
        groups.find { it.id == groupId }
            ?: groupsByRelay.values.flatten().find { it.id == groupId }

    LaunchedEffect(groupId, relayUrl) {
        if (relayUrl != null && groupMeta?.name == null) {
            repo.fetchGroupPreview(groupId, relayUrl)
        }
    }

    val displayName = groupMeta?.name?.takeIf { it.isNotBlank() } ?: groupId
    DisableSelection {
        Row(
            modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(NostrordColors.PrimarySubtle)
                .clickable { onNavigateToGroup(groupId, groupMeta?.name, relayUrl, null) }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GroupHeaderIcon(
                pictureUrl = groupMeta?.picture,
                groupId = groupId,
                displayName = displayName,
                size = 16.dp,
                cornerRadius = 4.dp,
            )
            Text(
                text = displayName,
                color = NostrordColors.Primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Renders an addressable event (naddr) as a card.
 * Addressable events include profiles, articles, live streams, etc.
 */
@Composable
private fun AddressableEvent(
    identifier: String,
    pubkey: String,
    kind: Int,
    relayHints: List<String> = emptyList(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cachedEvents by AppModule.nostrRepository.cachedEvents.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()

    // Create a composite key for addressable events: kind:pubkey:identifier
    val addressKey = "$kind:$pubkey:$identifier"
    val event = cachedEvents[addressKey]

    // Track if event was not found after timeout
    var eventNotFound by remember { mutableStateOf(false) }

    // Bumped by the not-found card's Retry button to re-run the fetch effect.
    var retryTick by remember { mutableStateOf(0) }

    // Request the addressable event
    LaunchedEffect(addressKey, relayHints, retryTick) {
        if (!cachedEvents.containsKey(addressKey)) {
            eventNotFound = false
            AppModule.nostrRepository.requestAddressableEvent(
                kind = kind,
                pubkey = pubkey,
                identifier = identifier,
                relays = relayHints,
            )
            // Poll every 500ms up to 5s — yields the coroutine instead of blocking 5s solid
            repeat(10) {
                kotlinx.coroutines.delay(500)
                if (cachedEvents.containsKey(addressKey)) return@LaunchedEffect
            }
            eventNotFound = true
        }
    }

    // Reset not found state when event arrives
    LaunchedEffect(event) {
        if (event != null) {
            eventNotFound = false
        }
    }

    // Request author metadata
    LaunchedEffect(pubkey) {
        if (!userMetadata.containsKey(pubkey)) {
            AppModule.nostrRepository.requestUserMetadata(setOf(pubkey))
        }
    }

    // If event not found after timeout, show error card with full parsed content (non-clickable, selectable)
    if (event == null && eventNotFound) {
        val copyToClipboard = rememberClipboardWriter()
        var showMenu by remember { mutableStateOf(false) }

        Row(
            modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
                .background(NostrordColors.Surface)
                .padding(Spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        text = "⚠",
                        style = NostrordTypography.MessageBody,
                        color = NostrordColors.TextMuted,
                    )
                    Text(
                        // Deleted and unreachable look identical from here (the relay just does
                        // not serve it), so the copy must not claim either one.
                        text = "Event removed or unavailable",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                    )
                    RetryChip(onClick = { retryTick++ })
                }
                // Show full parsed naddr content
                Text(
                    text = "kind: $kind",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption,
                )
                Text(
                    text = "pubkey: $pubkey",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption,
                )
                Text(
                    text = "d: $identifier",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption,
                )
                if (relayHints.isNotEmpty()) {
                    Text(
                        text = "relays: ${relayHints.joinToString(", ")}",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                    )
                }
            }
            // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict
            DisableSelection {
                Box {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "More options",
                        tint = NostrordColors.TextMuted,
                        modifier =
                        Modifier
                            .size(20.dp)
                            .clickable { showMenu = true }
                            .pointerHoverIcon(PointerIcon.Hand),
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy Parsed JSON") },
                            onClick = {
                                val json =
                                    buildJsonObject {
                                        put("type", "naddr")
                                        put("identifier", identifier)
                                        put("pubkey", pubkey)
                                        put("kind", kind)
                                        put(
                                            "relays",
                                            buildJsonArray {
                                                relayHints.forEach { add(JsonPrimitive(it)) }
                                            },
                                        )
                                    }.toString()
                                copyToClipboard(json)
                                showMenu = false
                            },
                        )
                    }
                }
            }
        }
        return
    }

    val metadata = userMetadata[pubkey]
    val authorName = metadata?.displayName ?: metadata?.name ?: shortNpub(pubkey)

    // Render different card styles based on kind
    when (kind) {
        0 -> {
            // Kind 0: User profile metadata
            ProfileCard(
                metadata = metadata,
                pubkey = pubkey,
                onClick = onClick,
                modifier = modifier,
            )
        }
        30023 -> {
            // Kind 30023: Long-form content (articles)
            ArticleCard(
                event = event,
                authorName = authorName,
                authorPicture = metadata?.picture,
                onClick = onClick,
                modifier = modifier,
            )
        }
        30040 -> {
            // Kind 30040: Books/publications
            BookCard(
                event = event,
                authorName = authorName,
                authorPicture = metadata?.picture,
                pubkey = pubkey,
                onClick = onClick,
                modifier = modifier,
            )
        }
        else -> {
            // Generic addressable event card
            GenericAddressableCard(
                event = event,
                authorName = authorName,
                authorPicture = metadata?.picture,
                kind = kind,
                identifier = identifier,
                onClick = onClick,
                modifier = modifier,
            )
        }
    }
}

/**
 * Profile card for kind 0 (user metadata) naddr
 */
@Composable
private fun ProfileCard(
    metadata: org.nostr.nostrord.network.UserMetadata?,
    pubkey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick)
            .padding(Spacing.md),
    ) {
        // Avatar
        OptimizedUserAvatar(
            imageUrl = metadata?.picture,
            pubkey = pubkey,
            displayName = metadata?.displayName ?: metadata?.name ?: "Unknown",
            size = 64.dp,
        )

        Spacer(modifier = Modifier.width(Spacing.md))

        // Profile info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = metadata?.displayName ?: metadata?.name ?: shortNpub(pubkey),
                color = NostrordColors.TextPrimary,
                style = NostrordTypography.MessageBody,
                fontWeight = FontWeight.Bold,
            )

            if (metadata?.nip05 != null) {
                Text(
                    text = metadata.nip05,
                    color = NostrordColors.TextSecondary,
                    style = NostrordTypography.Caption,
                )
            }

            if (metadata?.about != null) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = metadata.about,
                    color = NostrordColors.TextContent,
                    style = NostrordTypography.Caption,
                    maxLines = 3,
                )
            }
        }
    }
}

/**
 * Article card for kind 30023 (long-form content)
 */
@Composable
private fun ArticleCard(
    event: CachedEvent?,
    authorName: String,
    authorPicture: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copyToClipboard = rememberClipboardWriter()
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier =
            Modifier
                .weight(1f)
                .padding(Spacing.md),
        ) {
            if (event != null) {
                // Extract title from tags
                val title = event.tags.find { it.firstOrNull() == "title" }?.getOrNull(1)
                val summary = event.tags.find { it.firstOrNull() == "summary" }?.getOrNull(1)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptimizedUserAvatar(
                        imageUrl = authorPicture,
                        pubkey = event.pubkey,
                        displayName = authorName,
                        size = 20.dp,
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = authorName,
                        color = NostrordColors.TextSecondary,
                        style = NostrordTypography.Caption,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict
                    DisableSelection {
                        Box {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "More options",
                                tint = NostrordColors.TextMuted,
                                modifier =
                                Modifier
                                    .size(20.dp)
                                    .clickable { showMenu = true }
                                    .pointerHoverIcon(PointerIcon.Hand),
                            )
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy Event JSON") },
                                    onClick = {
                                        val json =
                                            buildJsonObject {
                                                put("id", event.id)
                                                put("pubkey", event.pubkey)
                                                put("created_at", event.createdAt)
                                                put("kind", event.kind)
                                                put(
                                                    "tags",
                                                    buildJsonArray {
                                                        event.tags.forEach { tag ->
                                                            add(
                                                                buildJsonArray {
                                                                    tag.forEach { add(JsonPrimitive(it)) }
                                                                },
                                                            )
                                                        }
                                                    },
                                                )
                                                put("content", event.content)
                                            }.toString()
                                        copyToClipboard(json)
                                        showMenu = false
                                    },
                                )
                            }
                        }
                    }
                }

                if (title != null) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = title,
                        color = NostrordColors.TextPrimary,
                        style = NostrordTypography.MessageBody,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                    )
                }

                if (summary != null) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = summary,
                        color = NostrordColors.TextContent,
                        style = NostrordTypography.Caption,
                        maxLines = 3,
                    )
                }
            } else {
                Text(
                    text = "Loading article...",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption,
                )
            }
        }
    }
}

/**
 * Book card for kind 30040 (books/publications)
 * Displays title, author credit, and publication metadata.
 */
@Composable
private fun BookCard(
    event: CachedEvent?,
    authorName: String,
    authorPicture: String?,
    pubkey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copyToClipboard = rememberClipboardWriter()
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier =
            Modifier
                .weight(1f)
                .padding(Spacing.md),
        ) {
            if (event != null) {
                // Extract title and author from tags
                val title = event.tags.find { it.firstOrNull() == "title" }?.getOrNull(1)
                val bookAuthor = event.tags.find { it.firstOrNull() == "author" }?.getOrNull(1)

                // Header row with avatar, name, date, and menu
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Author info
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        OptimizedUserAvatar(
                            imageUrl = authorPicture,
                            pubkey = pubkey,
                            displayName = authorName,
                            size = 24.dp,
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            text = authorName,
                            color = NostrordColors.TextSecondary,
                            style = NostrordTypography.Caption,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    // Date
                    Text(
                        text = formatTime(event.createdAt),
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption,
                    )

                    Spacer(modifier = Modifier.width(Spacing.xs))

                    // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict
                    DisableSelection {
                        Box {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "More options",
                                tint = NostrordColors.TextMuted,
                                modifier =
                                Modifier
                                    .size(20.dp)
                                    .clickable { showMenu = true }
                                    .pointerHoverIcon(PointerIcon.Hand),
                            )
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy Event JSON") },
                                    onClick = {
                                        val json =
                                            buildJsonObject {
                                                put("id", event.id)
                                                put("pubkey", event.pubkey)
                                                put("created_at", event.createdAt)
                                                put("kind", event.kind)
                                                put(
                                                    "tags",
                                                    buildJsonArray {
                                                        event.tags.forEach { tag ->
                                                            add(
                                                                buildJsonArray {
                                                                    tag.forEach { add(JsonPrimitive(it)) }
                                                                },
                                                            )
                                                        }
                                                    },
                                                )
                                                put("content", event.content)
                                            }.toString()
                                        copyToClipboard(json)
                                        showMenu = false
                                    },
                                )
                            }
                        }
                    }
                }

                // Book content
                if (title != null) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = title,
                        color = NostrordColors.TextPrimary,
                        style = NostrordTypography.MessageBody.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                    )
                }

                if (bookAuthor != null) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "by $bookAuthor",
                        color = NostrordColors.TextSecondary,
                        style = NostrordTypography.Caption,
                    )
                }
            } else {
                Text(
                    text = "Loading book...",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption,
                )
            }
        }
    }
}

/**
 * Generic card for other addressable event kinds
 */
@Composable
private fun GenericAddressableCard(
    event: CachedEvent?,
    authorName: String,
    authorPicture: String?,
    kind: Int,
    identifier: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copyToClipboard = rememberClipboardWriter()
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier =
            Modifier
                .weight(1f)
                .padding(Spacing.md),
        ) {
            if (event != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptimizedUserAvatar(
                        imageUrl = authorPicture,
                        pubkey = event.pubkey,
                        displayName = authorName,
                        size = 24.dp,
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = authorName,
                            color = NostrordColors.TextSecondary,
                            style = NostrordTypography.Caption,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Kind $kind event",
                            color = NostrordColors.TextMuted,
                            style = NostrordTypography.Caption,
                        )
                    }
                    // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict
                    DisableSelection {
                        Box {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "More options",
                                tint = NostrordColors.TextMuted,
                                modifier =
                                Modifier
                                    .size(20.dp)
                                    .clickable { showMenu = true }
                                    .pointerHoverIcon(PointerIcon.Hand),
                            )
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy Event JSON") },
                                    onClick = {
                                        val json =
                                            buildJsonObject {
                                                put("id", event.id)
                                                put("pubkey", event.pubkey)
                                                put("created_at", event.createdAt)
                                                put("kind", event.kind)
                                                put(
                                                    "tags",
                                                    buildJsonArray {
                                                        event.tags.forEach { tag ->
                                                            add(
                                                                buildJsonArray {
                                                                    tag.forEach { add(JsonPrimitive(it)) }
                                                                },
                                                            )
                                                        }
                                                    },
                                                )
                                                put("content", event.content)
                                            }.toString()
                                        copyToClipboard(json)
                                        showMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = event.content,
                    color = NostrordColors.TextContent,
                    style = NostrordTypography.Caption,
                    maxLines = 6,
                )
            } else {
                Text(
                    text = "Loading event (kind $kind, id: ${identifier.take(12)}...)",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption,
                )
            }
        }
    }
}
