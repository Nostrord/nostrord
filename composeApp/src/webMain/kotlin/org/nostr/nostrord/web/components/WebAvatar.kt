package org.nostr.nostrord.web.components

import js.objects.unsafeJso
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import org.nostr.nostrord.ui.theme.AvatarGradients
import org.nostr.nostrord.ui.theme.Hsl
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.useEffect
import react.useRef
import react.useState
import web.cssom.Background
import web.cssom.ClassName
import web.html.HTMLImageElement
import kotlin.math.abs

/** What the avatar represents — drives the fallback style and shape (mirrors native). */
enum class AvatarKind { USER, GROUP, RELAY }

external interface WebAvatarProps : Props {
    /** Image URL; when null/blank or it fails to load, the fallback stays visible. */
    var url: String?
    var name: String

    /**
     * Stable identity used to seed the fallback (pubkey for users, group/relay id), so it matches
     * native. Falls back to [name] when null/blank.
     */
    var seed: String?

    /**
     * USER → duotone gradient + circle; GROUP → initial letter on a conic-swirl gradient +
     * rounded square; RELAY → initial letter on a colour (shape left to [cls]). Defaults to USER.
     */
    var kind: AvatarKind?

    /** Size/shape classes applied alongside `avatar-tile` (e.g. "msg-avatar"). */
    var cls: String
    var onClick: (() -> Unit)?
}

/**
 * Avatar that always shows the fallback first and overlays the real picture once it loads (fading
 * in); if the image is missing or fails, the fallback stays. Users fall back to a seeded duotone
 * gradient (circle); groups to an initial letter on a seeded conic gradient, forced to a rounded
 * square; relays to an initial letter on a deterministic colour — mirroring the native scheme
 * (see AvatarGradients in commonMain).
 */
val WebAvatar =
    FC<WebAvatarProps> { props ->
        val (loaded, setLoaded) = useState { false }
        // `errored` is the current load's failure; `attempts` counts retries. A transient failure
        // self-heals (remount + backoff) up to MAX_AVATAR_RETRIES, only then giving up to the
        // fallback, so avatars no longer latch to their placeholder for the rest of the session.
        val (errored, setErrored) = useState { false }
        val (attempts, setAttempts) = useState { 0 }
        val imgRef = useRef<HTMLImageElement>(null)
        val lastUrlRef = useRef<String>(null)
        val lastSeedRef = useRef<String>(null)
        val gaveUp = errored && attempts >= MAX_AVATAR_RETRIES

        val seed = props.seed?.takeIf { it.isNotBlank() } ?: props.name
        val kind = props.kind ?: AvatarKind.USER
        // Drop the retained URL AND load state when this component is reused for a DIFFERENT
        // identity (list slot reuse, or the DM header switching peers): without this, the
        // previous identity's photo stays at opacity 1 (loaded == true) and paints for a frame
        // before the url effect recomputes, leaking the old avatar. Resetting during render (a
        // React-sanctioned prop-change adjustment, guarded by the ref so it runs once) discards
        // this render before it commits, so the stale picture never shows.
        if (lastSeedRef.current != seed) {
            lastSeedRef.current = seed
            // Same non-blank url across the seed change is the same picture — e.g. the
            // create-group modal reseeds per Group Name keystroke while the uploaded
            // preview url stays. Resetting there would hide the photo FOREVER: the url
            // effect below is keyed on the (unchanged) url and never re-runs to set
            // `loaded` back. Only a different/absent url is a real identity swap that
            // must drop the previous photo.
            val samePicture = lastUrlRef.current != null && props.url?.takeIf { it.isNotBlank() } == lastUrlRef.current
            if (!samePicture) {
                lastUrlRef.current = null
                setLoaded(false)
                setErrored(false)
                setAttempts(0)
            }
        }
        // Keep the last good picture if the URL transiently drops to null/blank for the SAME
        // identity (metadata churn, an LRU eviction that briefly removes the entry, or a parent
        // remount): a once-loaded avatar must not blank out mid-session. Only fall back when we
        // never had a picture for this identity.
        val incoming = props.url?.takeIf { it.isNotBlank() }
        if (incoming != null) lastUrlRef.current = incoming
        // RELAY: when NIP-11 didn't publish an icon, fall back to a bundled brand asset for
        // the relays the native UI ships (mirrors ui/util/RelayFallbacks.kt). Other kinds use
        // the letter/gradient fallback below — only relays have a brand image worth seeding.
        val url = incoming ?: lastUrlRef.current ?: if (kind == AvatarKind.RELAY) bundledRelayFallback(seed) else null

        useEffect(url) {
            // A browser-cached image can already be `complete` before React attaches onLoad —
            // common when a second component mounts with a URL an earlier avatar already fetched.
            // onLoad then never fires, leaving the photo at opacity:0 with only the fallback
            // visible (the "shows here but not there" inconsistency). Detect the cached hit
            // synchronously so the photo is shown immediately.
            val el = imgRef.current
            val cached = el != null && el.complete && el.naturalWidth > 0
            setErrored(false)
            setAttempts(0)
            setLoaded(cached)
        }

        // Retry pump: when the current load errors and retries remain, wait an exponential backoff
        // (2s, 4s, 8s) then bump `attempts`, which changes the <img> key and remounts it to re-issue
        // the fetch. Mirrors the native rememberAvatarImageState helper. The timer is cleared on
        // unmount / dep change via awaitCancellation + finally.
        useEffect(errored, attempts) {
            if (errored && attempts < MAX_AVATAR_RETRIES) {
                val timer = window.setTimeout({
                    setErrored(false)
                    setAttempts { it + 1 }
                }, 2_000 shl attempts)
                try {
                    awaitCancellation()
                } finally {
                    window.clearTimeout(timer)
                }
            }
        }

        div {
            className = ClassName("avatar-tile ${props.cls} avatar-stack" + if (kind == AvatarKind.GROUP) " group" else "")
            // An avatar with its own handler opens the profile without also triggering the
            // container it sits in (message row, quoted-event card).
            props.onClick?.let { cb ->
                onClick = { ev ->
                    ev.stopPropagation()
                    cb()
                }
            }

            // Fallback underneath while the photo loads. Removed once it has loaded so a
            // transparent avatar shows the tile's solid background instead of the
            // gradient/letter bleeding through. Stays if the photo is missing or fails.
            if (!loaded) {
                when (kind) {
                    AvatarKind.USER ->
                        div {
                            className = ClassName("avatar-gradient")
                            style = unsafeJso { background = userGradientCss(seed).unsafeCast<Background>() }
                        }
                    AvatarKind.GROUP -> letterAvatar(seed, props.name, background = groupGradientCss(seed))
                    AvatarKind.RELAY -> letterAvatar(seed, props.name, background = relayGradientCss(seed))
                }
            }

            // Real picture on top: hidden until it loads, removed only once retries are exhausted
            // so the fallback shows. Each retry changes `key` to remount and re-fetch.
            if (!url.isNullOrBlank() && !gaveUp) {
                // White backdrop for transparent user/group pictures (PNG with alpha) so they
                // sit on white, not the surface colour. Relays keep a transparent backdrop: a
                // white-on-transparent relay logo would vanish on white.
                val whiteBg = if (kind != AvatarKind.RELAY) " avatar-photo-white" else ""
                img {
                    key = "$url#$attempts"
                    ref = imgRef
                    className = ClassName((if (loaded) "avatar-photo loaded" else "avatar-photo") + whiteBg)
                    src = url
                    alt = props.name
                    // The browser's native image drag would swallow the pointer stream an
                    // avatar sits in (rail chip reorder), leaving a ghost image instead.
                    draggable = false
                    onLoad = {
                        setErrored(false)
                        setLoaded(true)
                    }
                    onError = { setErrored(true) }
                }
            }
        }
    }

/** Self-healing avatar load: at most this many retries per URL before giving up to the fallback. */
private const val MAX_AVATAR_RETRIES = 3

// Number of colours in the native NostrordColors.AvatarColors palette (see .avatar-color-N CSS).
private const val AVATAR_COLOR_COUNT = 8

/**
 * Bundled icon for the well-known NIP-29 relays that publish no NIP-11 icon. Mirrors
 * native's `ui/util/RelayFallbacks.kt` — same PNGs (copied to webMain/resources/), same
 * substring rules. Returns null for any unrecognised relay; the regular letter fallback
 * runs in that case.
 */
private fun bundledRelayFallback(relayUrl: String): String? = when {
    relayUrl.contains("0xchat.com") -> "relay_0xchat.png"
    relayUrl.contains("nip29.com") -> "relay_nip29.png"
    else -> null
}

/**
 * Initial-letter fallback. With [background] (a CSS gradient) the letter sits on it (groups);
 * otherwise on a deterministic palette colour (relays).
 */
private fun ChildrenBuilder.letterAvatar(seed: String, name: String, background: String? = null) {
    val index = abs(seed.hashCode()) % AVATAR_COLOR_COUNT
    // Skip whitespace / invisible chars (some NIP-29 groups have a leading space or
    // zero-width char in `name`, which made `.take(1)` produce an empty pill); fall
    // through to seed if `name` carries no printable char, then a `?` as last resort.
    val letter = (
        name.firstOrNull { !it.isWhitespace() }
            ?: seed.firstOrNull { !it.isWhitespace() }
            ?: '?'
        ).uppercaseChar().toString()
    div {
        className = ClassName(if (background != null) "avatar-letter" else "avatar-letter avatar-color-$index")
        if (background != null) {
            style = unsafeJso { this.background = background.unsafeCast<Background>() }
        }
        +letter
    }
}

private fun hsl(c: Hsl): String = "hsl(${c.hue} ${c.saturation}% ${c.lightness}%)"

/**
 * Prototype groupGradient: the group avatar's hue pair, darkened, at 135deg.
 * Shared by every banner/cover surface (group sidebar banner, info modal cover).
 */
internal fun bannerGradientCss(seed: String): String {
    val g = AvatarGradients.banner(seed)
    return "linear-gradient(135deg, ${hsl(g.start)}, ${hsl(g.end)})"
}

/** Prototype gradientAvatar: diagonal duotone + soft top sheen (math in AvatarGradients). */
private fun userGradientCss(seed: String): String {
    val g = AvatarGradients.user(seed)
    return "radial-gradient(circle at ${g.sheenX}% 12%, hsl(0 0% 100% / 0.28) 0%, hsl(0 0% 100% / 0) 42%), " +
        "linear-gradient(${g.angleDeg}deg, ${hsl(g.start)}, ${hsl(g.end)})"
}

/** Prototype gradientGroupAvatar: conic swirl seeded by the group id. */
private fun groupGradientCss(seed: String): String {
    val g = AvatarGradients.group(seed)
    return "conic-gradient(from ${g.fromDeg}deg, ${hsl(g.c1)}, ${hsl(g.c2)}, ${hsl(g.c3)}, ${hsl(g.c1)})"
}

/** Relay fallback: a three-stop diagonal band seeded by the relay URL (gradientRelayAvatar). */
private fun relayGradientCss(seed: String): String {
    val g = AvatarGradients.relay(seed)
    return "linear-gradient(${g.angleDeg}deg, ${hsl(g.start)} 0%, ${hsl(g.mid)} 50%, ${hsl(g.end)} 100%)"
}
