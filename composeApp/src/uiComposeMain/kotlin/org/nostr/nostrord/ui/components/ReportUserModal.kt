package org.nostr.nostrord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.forms.AppField
import org.nostr.nostrord.ui.screens.report.REPORT_REASONS
import org.nostr.nostrord.ui.screens.report.ReportUserViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * NIP-56 report modal (prototype ReportModal): reason radio cards, optional note,
 * the "also mute" toggle and an in-modal success state. [eventId] pins the report
 * to a specific message.
 */
@Composable
fun ReportUserModal(
    pubkey: String,
    metadata: UserMetadata?,
    eventId: String? = null,
    onDismiss: () -> Unit,
) {
    val vm = viewModel(key = "report:$pubkey:$eventId") {
        ReportUserViewModel(AppModule.nostrRepository, pubkey, eventId)
    }
    // The VM outlives the closed modal in the owner's store; clear the form on close
    // so reopening doesn't show the previous run's Sent state.
    DisposableEffect(Unit) { onDispose { vm.reset() } }
    val selected by vm.selected.collectAsState()
    val note by vm.note.collectAsState()
    val alsoMute by vm.alsoMute.collectAsState()
    val phase by vm.phase.collectAsState()
    val error by vm.error.collectAsState()
    val alreadyMuted by vm.targetAlreadyMuted.collectAsState()
    val didMute by vm.didMute.collectAsState()

    val npub = remember(pubkey) { Nip19.encodeNpub(pubkey) }
    val displayName =
        metadata?.displayName?.takeIf { it.isNotBlank() }
            ?: metadata?.name?.takeIf { it.isNotBlank() }
            ?: (npub.take(12) + "…")

    Dialog(
        onDismissRequest = onDismiss,
        properties =
        DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier =
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ModalTitleBar(
                        title = if (phase == ReportUserViewModel.Phase.Sent) "Report sent" else "Report",
                        onClose = onDismiss,
                    )

                    Column(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                            .padding(top = Spacing.md, bottom = Spacing.lg),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            ProfileAvatar(
                                imageUrl = metadata?.picture,
                                displayName = displayName,
                                pubkey = pubkey,
                                size = 44.dp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayName,
                                    color = NostrordColors.TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (eventId != null) "Report this message" else "Report user",
                                    color = NostrordColors.TextMuted,
                                    fontSize = 12.sp,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.md))

                        if (phase == ReportUserViewModel.Phase.Sent) {
                            Column(
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(NostrordColors.BackgroundFloating)
                                    .border(1.dp, NostrordColors.Divider, RoundedCornerShape(12.dp))
                                    .padding(Spacing.lg),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("🛡️", fontSize = 28.sp)
                                Spacer(modifier = Modifier.height(Spacing.sm))
                                Text(
                                    text = "Report submitted",
                                    color = NostrordColors.TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text =
                                    if (didMute) {
                                        "The report was published to your relays (kind:1984 event). $displayName was also muted."
                                    } else {
                                        "The report was published to your relays (kind:1984 event). Thank you."
                                    },
                                    color = NostrordColors.TextMuted,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.height(Spacing.md))
                                AppButton(text = "Done", onClick = onDismiss)
                            }
                        } else {
                            Text(
                                text = "REASON",
                                color = NostrordColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                REPORT_REASONS.forEach { reason ->
                                    val isSelected = selected == reason.type
                                    Row(
                                        modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(
                                                1.dp,
                                                if (isSelected) NostrordColors.Primary else NostrordColors.Divider,
                                                RoundedCornerShape(8.dp),
                                            )
                                            .background(
                                                if (isSelected) NostrordColors.PrimarySubtle else NostrordColors.Background,
                                            )
                                            .clickable { vm.select(reason.type) }
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .padding(horizontal = Spacing.md, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                    ) {
                                        Box(
                                            modifier =
                                            Modifier
                                                .size(16.dp)
                                                .clip(CircleShape)
                                                .border(
                                                    1.5.dp,
                                                    if (isSelected) NostrordColors.Primary else NostrordColors.TextMuted,
                                                    CircleShape,
                                                )
                                                .background(if (isSelected) NostrordColors.Primary else Color.Transparent),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (isSelected) {
                                                Box(
                                                    modifier =
                                                    Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(Color.White),
                                                )
                                            }
                                        }
                                        Column {
                                            Text(
                                                text = reason.label,
                                                color = if (isSelected) NostrordColors.TextPrimary else NostrordColors.TextSecondary,
                                                fontSize = 14.sp,
                                            )
                                            Text(
                                                text = reason.hint,
                                                color = NostrordColors.TextMuted,
                                                fontSize = 11.sp,
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(Spacing.md))
                            AppField(
                                value = note,
                                onValueChange = vm::setNote,
                                placeholder = "Details (optional)",
                                modifier = Modifier.fillMaxWidth(),
                            )

                            if (!alreadyMuted) {
                                Spacer(modifier = Modifier.height(Spacing.md))
                                Row(
                                    modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(NostrordColors.HoverBackground)
                                        .clickable { vm.toggleAlsoMute() }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(horizontal = Spacing.md, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier =
                                        Modifier
                                            .size(18.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .border(
                                                1.5.dp,
                                                if (alsoMute) NostrordColors.Primary else NostrordColors.TextMuted,
                                                RoundedCornerShape(4.dp),
                                            )
                                            .background(if (alsoMute) NostrordColors.Primary else Color.Transparent),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (alsoMute) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        text =
                                        buildAnnotatedString {
                                            append("Also mute ")
                                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = NostrordColors.TextPrimary)) {
                                                append(displayName)
                                            }
                                            append(" (recommended)")
                                        },
                                        color = NostrordColors.TextSecondary,
                                        fontSize = 13.sp,
                                    )
                                }
                            }

                            error?.let {
                                Spacer(modifier = Modifier.height(Spacing.sm))
                                Text(text = it, color = NostrordColors.Error, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(Spacing.md))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                            ) {
                                AppButton(
                                    text = "Cancel",
                                    onClick = onDismiss,
                                    variant = AppButtonVariant.Ghost,
                                )
                                AppButton(
                                    text = if (phase == ReportUserViewModel.Phase.Sending) "Sending…" else "Send report",
                                    onClick = { vm.send() },
                                    enabled = selected != null && phase == ReportUserViewModel.Phase.Editing,
                                    variant = AppButtonVariant.Danger,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
