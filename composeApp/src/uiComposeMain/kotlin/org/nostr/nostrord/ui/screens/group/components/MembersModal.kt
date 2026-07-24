package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.ModalTitleBar
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Read-only member roster (port of the web [org.nostr.nostrord.web.modals.MembersModal]):
 * avatar + name with an ADMIN badge, tapping a row opens that member's profile. Opened from
 * the group sidebar's Members row.
 */
@Composable
fun MembersModal(
    groupId: String,
    onDismiss: () -> Unit,
    // Relay hosting the group; scopes the roster (same-id groups elsewhere stay out).
    relayUrl: String? = null,
) {
    val repo = AppModule.nostrRepository
    val vm = viewModel(key = "members:$relayUrl|$groupId") { GroupViewModel(repo, groupId, relayUrl) }
    val members = vm.groupMembers.collectAsState().value[groupId].orEmpty()
    val admins = vm.groupAdmins.collectAsState().value[groupId].orEmpty().toSet()
    val userMetadata by repo.userMetadata.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }

    fun nameOf(pubkey: String): String {
        val meta = userMetadata[pubkey]
        return meta?.displayName?.takeIf { it.isNotBlank() }
            ?: meta?.name?.takeIf { it.isNotBlank() }
            ?: (Nip19.encodeNpub(pubkey).take(12) + "…")
    }

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
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
            ) {
                Column {
                    ModalTitleBar(
                        title = if (members.isNotEmpty()) "Members · ${members.size}" else "Members",
                        onClose = onDismiss,
                    )
                    if (members.isEmpty()) {
                        Text(
                            "Member list unavailable.",
                            color = NostrordColors.TextMuted,
                            fontSize = 14.sp,
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    } else {
                        Column(
                            modifier =
                            Modifier
                                .heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                        ) {
                            members.forEach { pubkey ->
                                MemberRow(
                                    name = nameOf(pubkey),
                                    pictureUrl = userMetadata[pubkey]?.picture,
                                    pubkey = pubkey,
                                    isAdmin = pubkey in admins,
                                    onClick = { selected = pubkey },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { pubkey ->
        UserProfileModal(
            pubkey = pubkey,
            metadata = userMetadata[pubkey],
            userMetadata = userMetadata,
            // Read-only roster (parity with web MembersModal): admins manage from elsewhere.
            iAmAdmin = false,
            targetIsAdmin = pubkey in admins,
            onUserClick = { selected = it },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun MemberRow(
    name: String,
    pictureUrl: String?,
    pubkey: String,
    isAdmin: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OptimizedSmallAvatar(
            imageUrl = pictureUrl,
            identifier = pubkey,
            displayName = name,
            size = 32.dp,
            shape = CircleShape,
        )
        Text(
            name,
            color = NostrordColors.TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isAdmin) {
            Box(
                modifier =
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(NostrordColors.Primary.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    "ADMIN",
                    color = NostrordColors.Primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
