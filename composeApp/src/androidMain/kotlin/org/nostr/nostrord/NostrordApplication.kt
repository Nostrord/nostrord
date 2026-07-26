package org.nostr.nostrord

import android.app.Application
import android.os.Build.VERSION.SDK_INT
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.svg.SvgDecoder
import okio.Path.Companion.toOkioPath
import org.nostr.nostrord.network.managers.AndroidNetworkMonitorInit
import org.nostr.nostrord.nostr.Nip55AndroidBridge
import org.nostr.nostrord.notifications.AndroidNotificationSoundInit
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.cache.CacheStoreAndroid
import org.nostr.nostrord.ui.components.media.VideoCache
import org.nostr.nostrord.ui.util.ImageLoadEventListener

/**
 * Custom Application class that registers Coil's animated GIF decoders.
 *
 * WHY this is required:
 * Coil 3 does not include GIF animation support by default. The `coil-gif` artifact
 * provides two decoders:
 *   - AnimatedImageDecoder: uses Android's native ImageDecoder API (API 28+, most efficient)
 *   - GifDecoder:           pure-Coil software decoder for API < 28 devices
 *
 * Without this registration, Coil decodes GIFs using its generic BitmapDecoder,
 * which only reads the first frame — producing a static image.
 *
 * By implementing SingletonImageLoader.Factory here, we ensure the animated decoders
 * are wired into every AsyncImage call across the entire app, including chat images,
 * the full-screen viewer, avatars, and any future image usage.
 */
class NostrordApplication :
    Application(),
    SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        SecureStorage.initialize(applicationContext)
        Nip55AndroidBridge.initialize(applicationContext)
        CacheStoreAndroid.initialize(applicationContext)
        AndroidNetworkMonitorInit.initialize(applicationContext)
        AndroidNotificationSoundInit.initialize(applicationContext)
        VideoCache.initialize(applicationContext)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader
        .Builder(context)
        // Logs load failures (NOSTRORD_IMG) so avatar/photo regressions are visible in logs.
        .eventListener(ImageLoadEventListener)
        .components {
            // AnimatedImageDecoder is hardware-accelerated via the platform ImageDecoder
            // API. GifDecoder is a pure-software fallback for older API levels.
            // Both are provided by the coil-gif artifact.
            if (SDK_INT >= 28) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            // Decodes data:image/svg+xml avatars (the data: fetcher is built in since 3.1).
            add(SvgDecoder.Factory())
        }
        // Memory cache: 20% of available app heap. Adapts to device capability
        // without a fixed floor that could starve low-RAM devices (128 MB heap
        // → ~26 MB cache). Sized so frequently-shown avatars stay resident
        // rather than being refetched over a long session, since each refetch
        // is a chance to hit a transient load failure that shows a placeholder.
        .memoryCache {
            MemoryCache
                .Builder()
                .maxSizePercent(context, percent = 0.20)
                .build()
        }
        // Persistent disk cache so images survive app restarts without re-downloading.
        .diskCache {
            DiskCache
                .Builder()
                .directory(cacheDir.resolve("image_cache").toOkioPath())
                .maxSizeBytes(150L * 1024 * 1024) // 150 MB
                .build()
        }.build()
}
