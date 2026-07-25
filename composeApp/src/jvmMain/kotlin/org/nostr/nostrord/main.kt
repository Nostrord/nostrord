package org.nostr.nostrord

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import okio.Path.Companion.toOkioPath
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.UnlockState
import org.nostr.nostrord.ui.PassphraseGate
import org.nostr.nostrord.ui.util.ImageLoadEventListener
import org.nostr.nostrord.ui.window.DesktopWindowControls
import org.nostr.nostrord.ui.window.LocalAwtWindow
import org.nostr.nostrord.ui.window.LocalDesktopWindowControls
import org.nostr.nostrord.utils.SingleInstance
import org.nostr.nostrord.utils.WindowsProtocolRegistrar
import org.nostr.nostrord.utils.parseGroupJoinInput
import org.nostr.nostrord.utils.toRelayUrl
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

/**
 * Parse a nostrord:// or https:// URL into an ExternalLaunchContext.
 * Supports: nostrord://open?relay=X&group=Y&code=Z
 *           https://nostrord.com/open/?relay=X&group=Y&code=Z
 *           nostr:naddr1... / naddr1... (NIP-19 kind 39000 with a relay hint)
 */
private fun parseDeepLinkUrl(url: String): ExternalLaunchContext? {
    if (url.startsWith("nostr:") || url.startsWith("naddr1")) {
        val target = parseGroupJoinInput(url) ?: return null
        return ExternalLaunchContext.OpenGroup(
            groupId = target.groupId,
            groupName = null,
            relayUrl = target.relayUrl,
            inviteCode = target.inviteCode,
            messageId = null,
        )
    }
    val uri =
        try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
    val query = uri.query ?: return null
    val params =
        query.split("&").associate { param ->
            val idx = param.indexOf("=")
            if (idx >= 0) {
                param.substring(0, idx) to java.net.URLDecoder.decode(param.substring(idx + 1), "UTF-8")
            } else {
                param to ""
            }
        }
    val relay = params["relay"]?.takeIf { it.isNotBlank() } ?: return null
    val relayUrl = relay.toRelayUrl().takeIf { it.isNotBlank() } ?: return null
    val groupId = params["group"]?.takeIf { it.isNotBlank() }
    val inviteCode = params["code"]?.takeIf { it.isNotBlank() }
    val messageId = params["e"]?.takeIf { it.isNotBlank() }

    return if (groupId != null) {
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
}

// Second-instance arrivals (deep link or plain relaunch) raise the existing window.
private val focusRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

fun main(args: Array<String> = emptyArray()) {
    // Without this, AWT EDT / coroutine crashes on Linux JBR surface as native
    // GTK error dialogs whose body is just the offending class name (truncated
    // LinkageError / NoClassDefFoundError / NPE-with-null-message), leaving no
    // way to diagnose. The handler also covers EDT once we set it as the
    // default — JBR routes EDT exceptions through Thread.UncaughtExceptionHandler.
    Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
        System.err.println("[nostrord] uncaught on ${thread.name}:")
        ex.printStackTrace(System.err)
    }
    // EDT has its own per-thread handler set by EventDispatchThread that ignores
    // the default. Pre-install ours on the EDT explicitly so Compose / Swing
    // recomposition crashes also land in stderr instead of vanishing into a
    // native dialog.
    java.awt.EventQueue.invokeLater {
        Thread.currentThread().uncaughtExceptionHandler =
            Thread.UncaughtExceptionHandler { thread, ex ->
                System.err.println("[nostrord] uncaught on EDT (${thread.name}):")
                ex.printStackTrace(System.err)
            }
    }

    // Windows resolves nostrord:// through HKCU registry keys the app writes itself
    // (jpackage/WiX cannot register URI schemes); macOS uses CFBundleURLTypes instead.
    WindowsProtocolRegistrar.registerIfNeeded()

    // With the app already open, the OS protocol handler starts a second process;
    // it forwards its argv to the running instance (which routes the deep link and
    // raises its window) and exits before any UI comes up.
    val isPrimary =
        SingleInstance.acquireOrForward(args) { message ->
            if (message.isNotBlank()) {
                parseDeepLinkUrl(message)?.let { StartupResolver.postRuntimeLaunch(it) }
            }
            focusRequests.tryEmit(Unit)
        }
    if (!isPrimary) return

    // Parse deep link from CLI args (e.g., OS protocol handler passes URL as first arg)
    args.firstOrNull()?.let { url ->
        parseDeepLinkUrl(url)?.let { StartupResolver.setExternalLaunchContext(it) }
    }
    // Configure Coil before the window opens so the first frame is already using the
    // persistent cache. Without this, Coil defaults to a temp-dir disk cache (wiped on
    // reboot) and a short-lived Ktor client — causing relay icons to flicker on cold starts.
    SingletonImageLoader.setSafe { context ->
        val cacheDir = File(System.getProperty("user.home"), ".nostrord/image-cache")
        val httpClient =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 5_000
                    requestTimeoutMillis = 15_000
                    socketTimeoutMillis = 10_000
                }
                install(HttpRedirect) {
                    checkHttpMethod = false
                }
            }
        ImageLoader
            .Builder(context)
            // Logs load failures (NOSTRORD_IMG) so avatar/photo regressions are visible in logs.
            .eventListener(ImageLoadEventListener)
            .components {
                add(KtorNetworkFetcherFactory(httpClient))
                // Decodes data:image/svg+xml avatars (the data: fetcher is built in since 3.1).
                add(SvgDecoder.Factory())
            }.memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizeBytes(128L * 1024 * 1024) // 128 MB — desktop has more RAM
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(cacheDir.toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024) // 256 MB persistent cache
                    .build()
            }.build()
    }

    application {
        val windowState =
            rememberWindowState(
                width = 1392.dp,
                height = 900.dp,
                position = WindowPosition.Aligned(Alignment.Center),
            )

        // Background threads (java-keyring's DBus connection, Ktor/Coil pools) keep the
        // JVM alive after exitApplication(). A daemon "watchdog" gives clean shutdown a
        // brief window, then halts — Runtime.halt skips shutdown hooks (which is what we
        // want: java-keyring's DBus close hook deadlocks on shutdown). Safe here because
        // every save*() in SecureStorage already flushes prefs inline.
        val quit: () -> Unit = quit@{
            if (SecureStorage.unlockState.value == UnlockState.NeedsPassphraseSetup) return@quit
            exitApplication()
            Thread {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                }
                Runtime.getRuntime().halt(0)
            }.apply {
                isDaemon = true
                name = "nostrord-exit-watchdog"
                start()
            }
        }

        Window(
            onCloseRequest = quit,
            title = "Nostrord",
            icon =
            ImageIO
                .read(
                    Thread
                        .currentThread()
                        .contextClassLoader
                        .getResourceAsStream("icon-512.png"),
                ).toPainter(),
            state = windowState,
            undecorated = true,
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown) {
                    // Ctrl+Q (Windows/Linux) or Cmd+Q (macOS) - quit application
                    if (event.key == Key.Q && (event.isCtrlPressed || event.isMetaPressed)) {
                        quit()
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            },
        ) {
            val controls =
                remember(windowState) {
                    object : DesktopWindowControls {
                        override fun minimize() {
                            windowState.isMinimized = true
                        }

                        override fun toggleMaximize() {
                            windowState.placement =
                                if (windowState.placement == WindowPlacement.Maximized) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Maximized
                                }
                        }

                        override fun close() {
                            quit()
                        }

                        override val isMaximized: Boolean
                            get() = windowState.placement == WindowPlacement.Maximized
                    }
                }

            // Desktop's window LifecycleOwner stays RESUMED for the whole process, so
            // the ON_STOP/ON_RESUME wiring in App.kt is inert here — AWT window focus
            // is the desktop foreground signal. Regaining focus probes for zombie
            // sockets (sleep/resume leaves half-open TCP that never fires
            // onConnectionLost) and refreshes subs; losing focus persists cursors and
            // lets notifications fire for the active group. Listener only — never
            // toFront/requestFocus here (GNOME FileDialog ordering).
            DisposableEffect(Unit) {
                var wasUnfocused = false
                val focusListener =
                    object : java.awt.event.WindowFocusListener {
                        override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                            AppModule.focusTracker.setFocused(true)
                            if (wasUnfocused) AppModule.nostrRepository.onForeground()
                        }

                        override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                            wasUnfocused = true
                            AppModule.focusTracker.setFocused(false)
                            AppModule.nostrRepository.onBackground()
                        }
                    }
                window.addWindowFocusListener(focusListener)
                onDispose { window.removeWindowFocusListener(focusListener) }
            }

            // Deep link / relaunch while running: un-minimize and raise the window.
            // Compose Desktop's Main dispatcher is the EDT, so touching AWT here is safe.
            LaunchedEffect(Unit) {
                focusRequests.collect {
                    windowState.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }

            CompositionLocalProvider(
                LocalDesktopWindowControls provides controls,
                LocalAwtWindow provides window,
            ) {
                PassphraseGate {
                    App()
                }
            }
        }
    }
}
