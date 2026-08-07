package org.nostr.nostrord

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.nostr.nostrord.auth.pomegranate.PomegranateGoogleSignIn
import org.nostr.nostrord.network.livekit.AvMediaBridge
import org.nostr.nostrord.network.upload.ShareMediaQueue
import org.nostr.nostrord.nostr.Nip55AndroidBridge
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.ui.components.media.FullscreenVideoController
import org.nostr.nostrord.ui.components.media.FullscreenVideoOverlay
import org.nostr.nostrord.ui.components.media.LocalFullscreenVideoController
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.parseGroupJoinInput
import org.nostr.nostrord.utils.toRelayUrl
import androidx.compose.ui.graphics.Color as ComposeColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // App is dark-only — pin system bar icons to light regardless of OS theme.
        val barStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)
        // NIP-55 (Amber) requests round-trip through this activity's result launcher.
        Nip55AndroidBridge.register(this)
        // Mic / camera grants for NIP-29 AV spaces round-trip through this activity too.
        AvMediaBridge.register(this)
        // The pomegranate Google account picker draws on this activity.
        PomegranateGoogleSignIn.register(this)
        handleDeepLink(intent)
        handleShareIntent(intent)
        setContent {
            val fullscreenVideoController = remember { FullscreenVideoController() }
            CompositionLocalProvider(LocalFullscreenVideoController provides fullscreenVideoController) {
                // Edge-to-edge: the app draws under the system bars and the rail / sidebar
                // backgrounds bleed to the screen edges (Discord-style). System-bar insets
                // are applied per-region inside AppFrame, and to the login / loading / onboarding
                // screens in App(), so content stays clear of the bars without leaving dead strips.
                Box(modifier = Modifier.fillMaxSize().background(NostrordColors.Background)) {
                    App()
                    FullscreenVideoOverlay(fullscreenVideoController)
                    // Solid scrim behind the status bar: the rail / sidebar / group-header
                    // backgrounds bleed up to the edge, so without this the status bar shows a
                    // patchwork of app colors. A single near-black bar also keeps the pinned
                    // light status icons legible in either theme.
                    Box(
                        modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(ComposeColor.Black),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        @Suppress("DEPRECATION")
        val uris: List<Uri> =
            when (intent.action) {
                Intent.ACTION_SEND ->
                    listOfNotNull(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM)
                        },
                    )
                Intent.ACTION_SEND_MULTIPLE ->
                    (
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                        }
                        )?.toList() ?: emptyList()
                else -> emptyList()
            }
        if (uris.isNotEmpty()) ShareMediaQueue.offer(uris)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        // nostr:naddr1... group link (NIP-19 kind 39000 with a relay hint). Other nostr:
        // entities (npub, nevent, ...) decode to null and are ignored.
        if (uri.scheme == "nostr") {
            val target = parseGroupJoinInput(uri.toString()) ?: return
            StartupResolver.postRuntimeLaunch(
                ExternalLaunchContext.OpenGroup(
                    groupId = target.groupId,
                    groupName = null,
                    relayUrl = target.relayUrl,
                    inviteCode = target.inviteCode,
                    messageId = null,
                ),
            )
            return
        }
        val relay = uri.getQueryParameter("relay")?.takeIf { it.isNotBlank() } ?: return
        val relayUrl = relay.toRelayUrl().takeIf { it.isNotBlank() } ?: return
        val groupId = uri.getQueryParameter("group")?.takeIf { it.isNotBlank() }
        val inviteCode = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
        val messageId = uri.getQueryParameter("e")?.takeIf { it.isNotBlank() }

        val context =
            if (groupId != null) {
                ExternalLaunchContext.OpenGroup(
                    groupId = groupId,
                    groupName = null,
                    relayUrl = relayUrl,
                    inviteCode = inviteCode,
                    messageId = messageId,
                )
            } else {
                ExternalLaunchContext.OpenRelay(relayUrl)
            }
        // Runtime event with replay: an already-running app (onNewIntent) navigates
        // immediately; at cold start AppFrame's collector picks it up on mount.
        StartupResolver.postRuntimeLaunch(context)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
