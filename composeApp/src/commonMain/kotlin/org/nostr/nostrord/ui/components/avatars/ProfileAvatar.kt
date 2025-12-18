package org.nostr.nostrord.ui.components.avatars

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
fun ProfileAvatar(
    imageUrl: String?,
    displayName: String,
    pubkey: String,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current

    if (imageUrl.isNullOrBlank()) {
        AvatarPlaceholder(displayName, pubkey, modifier)
    } else {
        Box(modifier = modifier) {
            var imageLoadFailed by remember { mutableStateOf(false) }

            if (imageLoadFailed) {
                AvatarPlaceholder(displayName, pubkey, Modifier.size(40.dp))
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "$displayName's avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    onError = { imageLoadFailed = true }
                )
            }
        }
    }
}
