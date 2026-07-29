package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.forms.AppField
import org.nostr.nostrord.ui.components.upload.MessageUploadButton
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Compose-a-new-thread dialog (kind:11 root): native mirror of the web CreateThreadModal. Title is
 * the thread headline (required), then the body. Logic stays in [org.nostr.nostrord.ui.screens.group.ThreadsViewModel];
 * this is pure UI and reports back through [onCreate].
 */
@Composable
fun CreateThreadDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, content: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier =
                Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 480.dp)
                    .padding(Spacing.lg)
                    // Absorb clicks so tapping the card doesn't fall through to the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = NostrordShapes.shapeLarge,
                color = NostrordColors.Surface,
            ) {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    Text("New thread", color = NostrordColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(Spacing.xs))
                    Text("Start a new discussion in this group.", color = NostrordColors.TextMuted, fontSize = 13.sp)

                    Spacer(Modifier.height(Spacing.md))
                    Text("Title", color = NostrordColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(Spacing.xs))
                    AppField(value = title, onValueChange = { title = it }, placeholder = "Thread title", modifier = Modifier.fillMaxWidth())

                    Spacer(Modifier.height(Spacing.md))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Content", color = NostrordColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        // Attach media to the thread body (rendered inline like chat).
                        MessageUploadButton(
                            onUploadComplete = { result ->
                                body = if (body.isBlank()) result.url else "$body ${result.url}"
                            },
                        )
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    AppField(
                        value = body,
                        onValueChange = { body = it },
                        placeholder = "Start a discussion...",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 4,
                    )

                    Spacer(Modifier.height(Spacing.lg))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
                        AppButton(text = "Cancel", onClick = onDismiss, variant = AppButtonVariant.Ghost)
                        AppButton(
                            text = "Publish thread",
                            onClick = {
                                onCreate(title, body)
                                onDismiss()
                            },
                            enabled = title.isNotBlank() && body.isNotBlank(),
                        )
                    }
                }
            }
        }
    }
}
