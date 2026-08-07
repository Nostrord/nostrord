package org.nostr.nostrord.ui.components.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/** How long a system message stays up before it fades out. */
private const val TOAST_VISIBLE_MS = 4_000L

/**
 * Renders [AppModule.systemMessages] as a transient toast at the bottom of the app.
 *
 * Mount once, at the app root: the flow carries one-shot notices the user did not
 * explicitly trigger (a revoked signer, a sync that could not be signed) as well as
 * action confirmations, and without a host every one of them is dropped.
 *
 * Web parity: SystemMessageHost in web/components/SystemMessageHost.kt.
 */
@Composable
fun SystemMessageHost() {
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        AppModule.systemMessages.collect { message = it }
    }
    // Keyed on the message so a second notice restarts the timer rather than
    // inheriting the first one's remaining time.
    LaunchedEffect(message) {
        if (message != null) {
            delay(TOAST_VISIBLE_MS)
            message = null
        }
    }

    // Own full-size Box so the host can be dropped anywhere without the caller
    // having to provide a BoxScope to align against.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.padding(Spacing.lg),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .shadow(12.dp, NostrordShapes.shapeMedium, ambientColor = Color.Black.copy(alpha = 0.4f))
                    .clip(NostrordShapes.shapeMedium)
                    .background(NostrordColors.Surface)
                    .border(Spacing.dividerThickness, NostrordColors.Divider, NostrordShapes.shapeMedium)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            ) {
                Text(
                    text = message.orEmpty(),
                    color = NostrordColors.TextContent,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
