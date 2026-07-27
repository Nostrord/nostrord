package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.isAndroid
import org.nostr.nostrord.ui.components.QrCode
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonSize
import org.nostr.nostrord.ui.components.forms.AppSegmentedTabs
import org.nostr.nostrord.ui.components.forms.AppTextField
import org.nostr.nostrord.ui.components.forms.SegmentedTab
import org.nostr.nostrord.ui.components.forms.appFieldColors
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

private enum class BunkerMode { QR, URL }

@Composable
fun BunkerLoginTab(onLoginSuccess: () -> Unit) {
    val vm = viewModel { LoginViewModel(AppModule.nostrRepository) }
    var mode by remember { mutableStateOf(BunkerMode.QR) }

    Column {
        // Description
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.shapeSmall,
            color = NostrordColors.Primary.copy(alpha = 0.1f),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = NostrordColors.Primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "Connect to a remote signer for secure key management",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Mode toggle: QR Code vs Bunker URL
        AppSegmentedTabs(
            tabs =
            listOf(
                SegmentedTab("QR Code", Icons.Default.QrCode),
                SegmentedTab("Bunker URL", Icons.Default.TextFields),
            ),
            selectedIndex = if (mode == BunkerMode.QR) 0 else 1,
            onSelect = { mode = if (it == 0) BunkerMode.QR else BunkerMode.URL },
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (mode) {
            BunkerMode.QR -> QrCodeLoginContent(vm, onLoginSuccess)
            BunkerMode.URL -> BunkerUrlLoginContent(vm, onLoginSuccess)
        }

        Spacer(modifier = Modifier.height(20.dp))

        BenefitsCard(
            title = "Why use a Bunker?",
            icon = Icons.Default.Lock,
            items =
            listOf(
                "Your private key never leaves the signer",
                "Approve each signing request",
                "Works with hardware signers",
                "Revoke access anytime",
            ),
        )
    }
}

@Composable
private fun QrCodeLoginContent(
    vm: LoginViewModel,
    onLoginSuccess: () -> Unit,
) {
    val nostrConnectUri by vm.qrUri.collectAsState()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var sessionKey by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { vm.cancelQrSession() }
    }

    LaunchedEffect(sessionKey) {
        errorMessage = null
        connectionStatus = null
        vm.startQrSession(
            onConnected = {
                connectionStatus = "Connected!"
                onLoginSuccess()
            },
            onError = { msg ->
                errorMessage = msg
                if (msg == null) connectionStatus = null
            },
        )
    }

    LaunchedEffect(nostrConnectUri) {
        if (nostrConnectUri != null && connectionStatus == null) {
            connectionStatus = "Waiting for signer..."
        }
    }

    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Scan with your signer app",
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "(Amber, nsec.app, etc.)",
            color = NostrordColors.TextMuted,
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // QR Code
        nostrConnectUri?.let { uri ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                QrCode(
                    data = uri,
                    size = 220.dp,
                    quietZone = 12.dp,
                    modifier =
                    Modifier.clickable {
                        try {
                            uriHandler.openUri(uri)
                        } catch (_: Exception) {
                        }
                    },
                )
            }
            if (isAndroid) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Tap to open in signer app",
                    color = NostrordColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } ?: run {
            if (errorMessage == null) {
                Box(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(244.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = NostrordColors.Primary,
                        strokeWidth = 3.dp,
                    )
                }
            }
        }

        // nostrconnect:// URI with copy button
        nostrConnectUri?.let { uri ->
            Spacer(modifier = Modifier.height(12.dp))
            var copied by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = uri,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = NostrordColors.TextMuted, fontSize = 14.sp),
                trailingIcon = {
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(uri))
                        copied = true
                    }) {
                        Icon(
                            imageVector = if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                            contentDescription = "Copy URI",
                            tint = if (copied) NostrordColors.Success else NostrordColors.Primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                shape = NostrordShapes.inputShape,
                colors = appFieldColors(),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Advanced: per-session, user-overridable nostrconnect relays.
        val relays by vm.nostrConnectRelays.collectAsState()
        NostrConnectRelaysSection(
            relays = relays,
            onApply = { newRelays ->
                vm.setNostrConnectRelays(newRelays)
                sessionKey++
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Waiting status
        if (connectionStatus != null && errorMessage == null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = NostrordShapes.shapeMedium,
                color = NostrordColors.Primary.copy(alpha = 0.1f),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (connectionStatus == "Connected!") {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = NostrordColors.Success,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = NostrordColors.Primary,
                            strokeWidth = 2.dp,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        connectionStatus ?: "",
                        color = if (connectionStatus == "Connected!") NostrordColors.Success else NostrordColors.Primary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Error message with retry
        errorMessage?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = NostrordShapes.shapeSmall,
                color = NostrordColors.Error.copy(alpha = 0.1f),
            ) {
                Text(
                    text = it,
                    color = NostrordColors.Error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            AppButton(
                text = "Try Again",
                onClick = { sessionKey++ },
                size = AppButtonSize.Large,
                fullWidth = true,
                icon = Icons.Default.QrCode,
            )
        }
    }
}

/**
 * Collapsible editor for the nostrconnect:// relays. Collapsed by default so the
 * common case stays clean; expanding lets the user fix a flaky relay. "Apply"
 * persists the list and the caller regenerates the QR with it.
 */
@Composable
private fun NostrConnectRelaysSection(
    relays: List<String>,
    onApply: (List<String>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Local editable copy, re-seeded whenever the applied list changes.
    var editable by remember(relays) { mutableStateOf(relays) }
    var newRelay by remember { mutableStateOf("") }
    val dirty = editable != relays

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(NostrordShapes.shapeSmall)
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = NostrordColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "Advanced signer relays",
                color = NostrordColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (expanded) {
            Text(
                "The signer connects through these relays. Change them if the QR won't connect.",
                color = NostrordColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))

            editable.forEachIndexed { index, relay ->
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        relay,
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            editable = editable.toMutableList().also { it.removeAt(index) }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove relay",
                            tint = NostrordColors.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = newRelay,
                onValueChange = { newRelay = it },
                placeholder = {
                    Text("wss://relay.example.com", color = NostrordColors.TextMuted, fontSize = 14.sp)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = NostrordColors.TextContent, fontSize = 14.sp),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val r = newRelay.trim()
                            if (r.startsWith("wss://") && r !in editable) {
                                editable = editable + r
                                newRelay = ""
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add relay",
                            tint = NostrordColors.Primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                shape = NostrordShapes.inputShape,
                colors = appFieldColors(),
            )

            Spacer(modifier = Modifier.height(8.dp))
            AppButton(
                text = "Apply & regenerate QR",
                onClick = { onApply(editable) },
                enabled = dirty && editable.isNotEmpty(),
                fullWidth = true,
                icon = Icons.Default.QrCode,
            )
        }
    }
}

@Composable
private fun BunkerUrlLoginContent(
    vm: LoginViewModel,
    onLoginSuccess: () -> Unit,
) {
    var bunkerUrl by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    val authUrl by vm.authUrl.collectAsState()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(authUrl) {
        authUrl?.let { url ->
            connectionStatus = "Opening browser for approval..."
            try {
                uriHandler.openUri(url)
            } catch (e: Exception) {
                errorMessage = "Could not open browser. Please open this URL manually."
            }
        }
    }

    fun connect() {
        if (bunkerUrl.isBlank() || isConnecting) return
        isConnecting = true
        errorMessage = null
        connectionStatus = "Connecting..."
        vm.clearAuthUrl()
        vm.loginWithBunker(bunkerUrl) { result ->
            isConnecting = false
            if (result.isSuccess) {
                connectionStatus = "Connected!"
                onLoginSuccess()
            } else {
                errorMessage = "Connection failed: ${result.exceptionOrNull()?.message}"
                connectionStatus = null
            }
        }
    }

    Column {
        // Input field (shared form component); Enter connects
        AppTextField(
            value = bunkerUrl,
            onValueChange = {
                bunkerUrl = it
                errorMessage = null
            },
            placeholder = "bunker://<pubkey>?relay=wss://...",
            hint = "Get your bunker URL from nsec.app, Amber, or other NIP-46 signers",
            leadingIcon = Icons.Default.Link,
            maxLines = 3,
            enabled = !isConnecting,
            onDone = { connect() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Connect button
        AppButton(
            text = if (isConnecting) "Connecting..." else "Connect to Bunker",
            onClick = { connect() },
            enabled = bunkerUrl.isNotBlank() && !isConnecting,
            size = AppButtonSize.Large,
            fullWidth = true,
            loading = isConnecting,
            icon = Icons.Default.Link,
        )

        // Show auth URL if waiting for approval
        authUrl?.let { url ->
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = NostrordShapes.shapeMedium,
                color = NostrordColors.WarningOrange.copy(alpha = 0.1f),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = NostrordColors.WarningOrange,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Approval Required",
                            color = NostrordColors.WarningOrange,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "A browser window should have opened. Please approve the connection there, then wait...",
                        color = NostrordColors.TextContent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        url,
                        color = NostrordColors.TextLink,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        connectionStatus?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (it == "Connected!") {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = NostrordColors.Success,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    it,
                    color = if (it == "Connected!") NostrordColors.Success else NostrordColors.Primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = NostrordShapes.shapeSmall,
                color = NostrordColors.Error.copy(alpha = 0.1f),
            ) {
                Text(
                    text = it,
                    color = NostrordColors.Error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
