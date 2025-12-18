package org.nostr.nostrord.ui.screens.backup

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.NostrRepository

@Composable
fun BackupScreen(onNavigateBack: () -> Unit) {
    val privateKey = NostrRepository.getPrivateKey()
    val publicKey = NostrRepository.getPublicKey()
    val clipboardManager = LocalClipboardManager.current
    var showCopiedMessage by remember { mutableStateOf(false) }

    LaunchedEffect(showCopiedMessage) {
        if (showCopiedMessage) {
            kotlinx.coroutines.delay(2000)
            showCopiedMessage = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp

        if (isCompact) {
            BackupScreenMobile(
                privateKey = privateKey,
                publicKey = publicKey,
                showCopiedMessage = showCopiedMessage,
                onCopyPublicKey = {
                    publicKey?.let {
                        clipboardManager.setText(AnnotatedString(it))
                        showCopiedMessage = true
                    }
                },
                onCopyPrivateKey = {
                    privateKey?.let {
                        clipboardManager.setText(AnnotatedString(it))
                        showCopiedMessage = true
                    }
                },
                onNavigateBack = onNavigateBack
            )
        } else {
            BackupScreenDesktop(
                privateKey = privateKey,
                publicKey = publicKey,
                showCopiedMessage = showCopiedMessage,
                onCopyPublicKey = {
                    publicKey?.let {
                        clipboardManager.setText(AnnotatedString(it))
                        showCopiedMessage = true
                    }
                },
                onCopyPrivateKey = {
                    privateKey?.let {
                        clipboardManager.setText(AnnotatedString(it))
                        showCopiedMessage = true
                    }
                },
                onNavigateBack = onNavigateBack
            )
        }
    }
}
