# CLAUDE.md — Nostrord KMP

Nostr NIP-29 group messaging client. Kotlin Multiplatform targeting Android, JVM Desktop, iOS, and the web (JS).

Native targets (Android / Desktop / iOS) render with **Compose Multiplatform**. The **web target renders with React/DOM** via kotlin-wrappers (no Compose, no Skia/Canvas) — all targets share the same `commonMain` business logic. Web is plain JS (Kotlin/JS); there is no WebAssembly target.

## First-time setup

```bash
./scripts/install-hooks.sh
```

Points `core.hooksPath` at the repo-tracked hooks (covers all worktrees of this clone). The `pre-commit` hook runs `spotlessApply` on staged Kotlin files and then `spotlessCheck` to guarantee CI won't fail on formatting.

## Build Commands

```bash
# Quick check (use this after any commonMain change)
./gradlew compileKotlinJvm

# Android
./gradlew compileDebugKotlinAndroid

# Desktop (run)
./gradlew :composeApp:run

# Web (React/DOM, JS only)
./gradlew compileKotlinJs                          # compile check
./gradlew :composeApp:jsBrowserDevelopmentRun      # dev server (hot reload)
./gradlew :composeApp:jsBrowserDistribution        # production bundle (version-stamped)

# Tests
./gradlew :composeApp:jvmTest       # fast, runs all commonTest on JVM
./gradlew :composeApp:allTests      # all platforms

# Full build
./gradlew build
```

**Rule**: Always run `compileKotlinJvm` before finishing any task. It is the fastest cross-platform check.

**Fast iteration**: incremental compilation is reliable — just run plain `compileKotlinJvm` /
`compileKotlinJs`. Do NOT routinely add `--rerun-tasks` or `--no-build-cache` (they force slow
full rebuilds). A second run reporting `UP-TO-DATE` means the prior run already compiled your
change, not that it was missed. The Gradle build cache is on, but `compileKotlinJs` is excluded
from it (build.gradle.kts) because packing its incremental IR intermittently failed and broke the
build after a successful compile; if you hit that "Could not pack tree" error on another JS task,
exclude it the same way — it's the cache, not your code.

## Module Map

```
composeApp/src/
├── commonMain/    Shared business logic, NIP implementations, models (NO heavy Compose UI)
├── uiComposeMain/ Compose Multiplatform UI — intermediate source set shared ONLY by
│                  android / jvm / ios. The web (js) target is excluded via the hierarchy
│                  template, so Skiko/Compose UI never enters the web bundle.
├── webMain/       Web React/DOM UI (see "Web frontend" below): main.kt + web/ tree
├── androidMain/   EncryptedSharedPreferences, Android-specific crypto
├── jvmMain/       Desktop crypto (secp256k1-kmp), JVM Preferences storage
├── jsMain/        JS platform actuals (Crypto/Nip04/Nip44/Nip07/Nip46Client.js.kt,
│                  SecureStorage, notifications, Ktor JS client) — the actuals webMain calls into
└── iosMain/       Darwin Ktor engine, iOS-specific crypto and storage (kept current alongside the others)
```

Platform targets: `androidTarget`, `jvm`, `js`, `iosArm64`, `iosSimulatorArm64`. `commonMain`
carries only `compose.runtime` (for `@Immutable`/`@Stable`); the Skiko-pulling Compose UI libs
live in `uiComposeMain` so DCE keeps Skia out of the JS bundle.

## Source organization (commonMain)

```
org.nostr.nostrord/
├── auth/          Multi-account: Account, AccountId, AccountStore,
│                  AccountManager, ActiveAccountManager, AccountSession,
│                  AccountSessionFactory, NostrSigner, FallbackChain
├── di/            AppModule (singleton DI container)
├── model/         Shared data models
├── network/
│   ├── managers/  GroupManager, OutboxManager, MetadataManager,
│   │              UnreadManager, ConnectionManager, SessionManager,
│   │              PendingEventManager, LiveCursorStore, AdaptiveConfig,
│   │              RelayMetadataManager, RelayReconnectScheduler,
│   │              EventOrderingBuffer, MuxSubscriptionTracker, etc.
│   ├── outbox/    NIP-65 (Nip65Relay) + event deduplication
│   └── upload/    Blossom / nostr.build uploaders
├── nostr/         Event, KeyPair, Crypto, Bech32, Nip04, Nip07,
│                  Nip11, Nip19, Nip27, Nip44, Nip46Client
├── notifications/ Cross-platform notification dispatch
├── settings/      User-facing feature flags and preferences
├── startup/       Cold-start sequencing
├── storage/       SecureStorage (expect/actual) + per-account helpers
├── ui/            Only platform-agnostic UI bits: Screen.kt (nav sealed class), components/emoji
└── utils/         AppError, epoch helpers, LruCache, etc.
```

The Compose UI proper lives in **`uiComposeMain`** (android / jvm / ios only):

```
org.nostr.nostrord/
├── App.kt            Compose app entry + navigation switch
├── AppViewModel.kt   Top-level Compose ViewModel
├── ui/
│   ├── components/    Reusable composables (avatars, buttons, chat, accounts, layout, sidebars)
│   ├── navigation/    NavigationHistory
│   ├── screens/       home, group, relay, backup, login, notifications, onboarding, profile, settings
│   ├── theme/         NostrordColors
│   ├── util/          UI helpers
│   └── window/        Window / surface adapters
├── network/upload/   Compose-aware upload helpers
└── utils/            ByteBoundedImageCache, ClipboardUtils, ShareUtils
```

## Architecture

**Public facade for the UI layer:**
```
NostrRepository  (use this from Compose / ViewModels)
```

**Auth and signing (post multi-account):**
```
AccountManager  (add / switch / remove accounts)
    │
    ├─ AuthManager (class)  ──── credential load, NIP-42 AUTH, session invalidation
    │
    ├─ ActiveAccountManager  ─── atomic session swap, mutex-protected
    │       │
    │       └─ AccountSession  ─ per-account scope + signer (zeroed on dispose)
    │
    └─ AccountStore  ─────────── persisted account registry + active id

NostrRepository  ──────────────  reads from AccountSession, publishes to relays
SessionManager  ───────────────  NIP-42 AUTH dedup, post-AUTH resubscribe
```

**DI entry point:** `AppModule` singleton.
```kotlin
// Correct
val repo = AppModule.nostrRepository
val groupManager = AppModule.groupManager
val accountManager = AppModule.accountManager

// Wrong — managers depend on shared scope and other managers
GroupManager(connectionManager, scope)
```

**Coroutine scopes:**
- `AppModule.appScope` is **private**. It is passed into each manager at construction time.
- Long-running per-account work runs on `AccountSession.scope`. Cancellation of that scope tears down all per-account jobs at once.
- New code that wants a scope should be a manager (constructor-injected) or run inside the active `AccountSession.scope`.

**State pattern:** Everything is `StateFlow`. Collect in Compose with `.collectAsState()`; on the web, collect with the `useStateFlow(flow)` React hook (see Web frontend below). One-shot events (snackbars) use `SharedFlow` (see `AppModule.systemMessages`).

**Errors:** `Result<T>` + `AppError` sealed class (in `utils/Result.kt`). Never `throw` across module boundaries.

## Web frontend (React / DOM)

The web UI is **React via kotlin-wrappers**, written in Kotlin — not TypeScript, not Compose. It renders real DOM (no Skia/Canvas) and lives in `composeApp/src/webMain/`. It calls the exact same `AppModule` repository/managers as the native targets; there is no serialization boundary.

```
webMain/kotlin/org/nostr/nostrord/
├── main.kt              Entry: createRoot(#composeApplication).render(WebApp). Parses deep-link query params.
└── web/
    ├── WebApp.kt        Root: runs repo.initialize(), gates on isLoggedIn → LoginScreen or AppShell
    ├── AppShell.kt      Logged-in layout (sidebars + active screen)
    ├── auth/            WebAuth.kt — web login methods (NIP-07 extension, nsec, bunker)
    ├── bridge/          Kotlin↔React glue:
    │                      FlowHooks.kt  → useStateFlow(flow), useFlow(flow, initial)
    │                      AppScope.kt   → launchApp{} for fire-and-forget suspend work that outlives a component
    ├── components/      Reusable: Icon, WebAvatar, Jdenticon, QrCode, EmojiPicker, ModalHooks (useEscClose), …
    ├── screens/         LoginScreen, HomeScreen, ChatScreen, SettingsScreen, NotificationsScreen, …
    ├── modals/          CreateGroupModal, JoinGroupModal, MemberManagementModal, … + ModalShared.kt builders
    └── theme/           WebColors.kt (mirrors NostrordColors)

webMain/resources/
├── index.html          HTML shell + cold-start spinner; mount point #composeApplication; SW registration
├── styles.css          All web styling — plain CSS with custom-property design tokens. NO emotion/styled.
├── sw.js               Service worker: network-first HTML+app JS, cache-first WASM/fonts
└── (favicons, PWA manifest, crypto JS libs, sounds, relay icons)
```

Rules for web work:
- **Components are `val Name = FC<Props> { ... }`** using the `react` / `react-dom` DSL (`div {}`, `button {}`, `className = ClassName("…")`). One component per concern; match existing files.
- **Consume StateFlow with `useStateFlow(flow)`** — the web analogue of Compose's `collectAsState()`. Never collect a flow ad-hoc inside a component body.
- **Suspend calls from event handlers** go through `launchApp { … }` (sends, joins, etc. must outlive the click).
- **Styling is in `styles.css`** with CSS variables — no inline style objects for theming. CSS is served network-first and version-stamped (`?v=__BUILD_VERSION__`, replaced at build time) so deploys bust the cache.
- Web platform `actual`s (crypto, NIP-07, storage, notifications) live in `jsMain`, not `webMain`.
- No em-dash in any user-visible web string (same rule as native).
- **Composer focus vs mobile keyboard:** Chrome re-shows the on-screen keyboard whenever a tap
  ends with an editable element still focused. Never `preventDefault()` a control's mousedown
  unconditionally to keep composer focus; gate it with
  `VirtualKeyboardPolicy.keepComposerFocusOnTap(VirtualKeyboard.isOpen, touch)` (rules in
  commonMain `ui/keyboard/VirtualKeyboardPolicy.kt` + test, tracker in
  `web/bridge/VirtualKeyboard.kt`). index.html sets `interactive-widget=resizes-content`, so
  keyboard state can NOT be derived from `innerHeight - visualViewport.height`; don't hand-roll
  detection. Device matrix for any focus/keyboard-adjacent change: keyboard open + tap control
  → stays open; keyboard closed (back button, field still focused) + tap control → stays
  closed; tap the field itself → opens; desktop click → composer keeps focus.

## Never Do This

```kotlin
// java.* imports in commonMain — Kotlin types only
import java.lang.Integer
import java.util.UUID                 // use kotlin.uuid.Uuid

// GlobalScope — use AppModule manager scopes or AccountSession.scope
GlobalScope.launch { ... }

// AppModule.scope does not exist; appScope is private
AppModule.scope.launch { ... }

// Nip46Client.handleMessage is NOT suspend; wrap suspend work in launch
suspend fun handleMessage() { ... }

// Don't call legacy static companion accessors in new code
NostrRepository.sendMessage(...)      // old pattern, prefer instance methods via AppModule

// Don't write to legacy global storage slots in new code
SecureStorage.saveRelayList(...)      // use saveRelayListFor(pubkey, ...) instead

// Don't include the primary NIP-29 relay in a kind:0 REQ
// (NIP-29 relays do not serve profile metadata)

// Don't catch CancellationException with a bare catch (_: Throwable)
// — rethrow it explicitly first

// Don't add a wasmJs target or *.wasmjs.kt files — web is Kotlin/JS only

// Don't import or write Compose UI in webMain — the web bundle has no Skiko.
// Compose UI belongs in uiComposeMain (android/jvm/ios); web UI is React in webMain.
```

## Multi-account rules

- Use per-account `*For(pubkey)` extension functions in `SecureStorage` (e.g., `saveRelayListFor`, `loadKind10009Timestamp`, `saveCurrentRelayUrlFor`). Legacy global slots remain only for one-shot migration.
- `ActiveAccountManager.activate(session)` is the only correct way to install a new session; it cancels the previous scope and disposes the previous signer under a mutex.
- `NostrSigner.dispose()` zeros private key bytes for `Local` and `Guest`. Don't keep extra copies of `KeyPair.privateKey` around — the dispose-and-zero hardening only covers the in-flight signer.
- Cross-account fallback selection (when a session dies or an active account is removed) goes through `pickFirstSuccess` in `auth/FallbackChain.kt`.

## Platform Restrictions

| Capability | Android | JVM | JS | iOS |
|---|---|---|---|---|
| secp256k1 / Schnorr signing | yes | yes | yes | yes |
| NIP-46 bunker client | yes | yes | yes | yes |
| NIP-07 browser extension (`Nip07.isAvailable()` true) | no | no | yes | no |
| NIP-55 signer app (`Nip55.isAvailable()` true) | yes | no | no | no |
| EncryptedSharedPreferences | yes | no | no | no |
| `java.security.*` | yes | yes | no | no |
| Keychain (iOS secure storage) | no | no | no | yes |

All NIP `expect`s have `actual` declarations on every platform (some are stubs that throw or report unavailable). Compilation succeeds across all four targets; runtime availability is what differs.

**Rule:** If a feature requires platform code, add `expect` in `commonMain` and implement `actual` on **all four targets** (android, jvm, js, ios) in the same change. Do not defer any platform: accumulated TODOs become release-blocking work later. If a platform genuinely cannot support the feature (e.g. NIP-07 only exists in browsers), make the `actual` a documented stub that returns `false`/throws, not a missing declaration.

## expect/actual Locations

| Concern | File pattern |
|---|---|
| Crypto (keygen, sign, verify) | `nostr/Crypto.kt` → `*.android.kt`, `*.jvm.kt`, `*.js.kt`, `*.ios.kt` |
| NIP-04 / NIP-44 encryption | `nostr/Nip04.kt`, `nostr/Nip44.kt` |
| NIP-46 bunker client | `nostr/Nip46Client.kt` |
| NIP-07 browser extension | `nostr/Nip07.kt` (stub on Android/JVM/iOS, real on JS) |
| NIP-55 Android signer | `nostr/Nip55.kt` (real on Android, stub on JVM/JS/iOS) |
| Secure storage | `storage/SecureStorage.kt` |

## NIP support

| NIP | Where | Purpose |
|---|---|---|
| 01 | `nostr/Event.kt`, `nostr/KeyPair.kt` | Event signing and verification (secp256k1) |
| 04 | `nostr/Nip04.kt` | Legacy encryption |
| 07 | `nostr/Nip07.kt` | Browser extension signer (JS / web only at runtime) |
| 11 | `nostr/Nip11.kt` | Relay information document |
| 19 | `nostr/Nip19.kt`, `nostr/Bech32.kt` | Bech32 entities (npub, nsec, naddr, etc.) |
| 27 | `nostr/Nip27.kt` | Text-note references / mentions |
| 29 | `network/managers/GroupManager.kt` + NIP-29 relays | Groups (core feature) |
| 42 | connection layer | AUTH for restricted groups |
| 44 | `nostr/Nip44.kt` | Modern encryption |
| 46 | `nostr/Nip46Client.kt` | Remote signer / bunker |
| 55 | `nostr/Nip55.kt` | Android external signer app (Amber); real actual on Android only |
| 65 | `network/outbox/Nip65Relay.kt` | Outbox relay metadata |

Detailed NIP-29 expertise lives in `.claude/skills/nip29-expert/SKILL.md`.

## Common Patterns

**Sign and send an event:**
```kotlin
val signer = ActiveAccountManager.session.value?.signer
    ?: return Result.Error(AppError.Auth.NotSignedIn)
val signed = signer.signEvent(unsignedEvent)
val message = buildJsonArray { add("EVENT"); add(signed.toJsonObject()) }.toString()
val client = connectionManager.getPrimaryClient()
    ?: return Result.Error(AppError.Network.Disconnected(relayUrl))
client.send(message)  // suspend
```

**Adding a new group operation:**
1. Add the method to `GroupManager` (domain logic).
2. Delegate from `NostrRepository` (pass `signEvent` and `publishJoinedGroups` as lambdas).
3. Expose in the UI via `AppModule.nostrRepository`.

**Parity workflow — every UI feature (the default; do NOT do one side only):**

Screen logic lives in a ViewModel in **commonMain**; each UI is only layout over it. This
is what keeps web and Compose from drifting — behavior is written and tested once, not
reimplemented per render tree.

1. **commonMain** — put the state + actions in a `*ViewModel` under
   `commonMain/ui/screens/<screen>/` (extends `androidx.lifecycle.ViewModel`, exposes
   `StateFlow`s from the repo/managers, wraps actions in `viewModelScope.launch`). Add a
   test in `commonTest` (it runs under jvmTest and now covers both UIs).
2. **Compose** (`uiComposeMain`) — `Screen` sealed class entry (`commonMain/ui/Screen.kt`),
   a `YourScreen.kt` that consumes the VM via `viewModel { }` + `collectAsState()`, wired
   into `App.kt` (desktop / mobile variants if layout differs).
3. **Web** (`webMain`) — a `*Screen` FC under `web/screens/` that consumes the SAME VM via
   `useViewModel { }` + `useStateFlow(...)`, wired into `AppShell.kt`.
4. **Tokens** — colors from `ColorTokens`, spacing/radius from `DimenTokens` (both in
   `commonMain/ui/theme/`). Compose reads them via `NostrordColors` / `Spacing` /
   `NostrordShapes`; web via `WebColors` and the injected `--color-*` / `--space-*` /
   `--radius-*` vars. Never hardcode a hex or an off-scale px. (Typography is NOT a shared
   token: the web type scale diverges by design and is per-platform.)
5. Run `compileKotlinJvm` + `compileKotlinJs` + `:composeApp:jvmTest` before finishing.

**Definition of done = two thin screens + one ViewModel + one test.** The two render trees
are separate (Compose Skia vs React DOM) and both consume the same `AppModule`; adding a
native screen does NOT add the web one, and vice versa. Never "I'll do the web later" —
that is how the two diverge. Lifting a pre-existing Compose-only ViewModel into commonMain
when you next touch its screen is in-scope, not a separate task.

**Adding a new persisted setting (per-account):**
1. Add the key + `saveXxxFor(pubkey, ...)` / `loadXxxFor(pubkey, ...)` extension in `storage/SecureStorage.kt`.
2. If migrating from a legacy global slot, write the per-account slot **first**, then set `*_legacy_migrated = true` so a crash mid-migration doesn't lose the legacy data.

**Responsive breakpoints:**
- Compact `< 600dp` — mobile, bottom nav / drawer
- Medium `600–840dp` — tablet, sidebar + 2-column
- Large `> 840dp` — desktop, sidebar + 3-column
- Use `BoxWithConstraints` to switch layouts

## Naming Conventions

| Type | Pattern | Example |
|---|---|---|
| Manager | `*Manager` | `GroupManager`, `SessionManager`, `AccountManager` |
| Repository | `*Repository` | `NostrRepository` |
| Network client | `*Client` | `NostrGroupClient`, `Nip46Client` |
| Screen (Compose or React) | `*Screen` | `GroupScreen`, `HomeScreen`, `LoginScreen` |
| Modal (Compose or React) | `*Modal` | `CreateGroupModal`, `AddAccountSheet` |
| Platform file | `Name.platform.kt` | `Crypto.android.kt`, `Nip07.js.kt` |
| Web React component | `val X = FC<Props> {}` | `LoginScreen`, `WebApp`, `AppShell` |
| Web bridge hook | `useXxx` | `useStateFlow`, `useFlow`, `useEscClose` |

## Shared UI Components — Use, Don't Recreate

**Before building any new UI element, check the catalogue: `docs/components-index.md`.** It
lists every reusable Compose `@Composable` (under `ui/components`) and web React `FC`
(under `web/components` / `web/modals`) with a one-line purpose. Reuse the closest match, or
extend it; only add a new component when nothing fits. Regenerate the index whenever you add
or remove a component:

```bash
./scripts/gen-component-index.sh
```

**Supersede, don't duplicate.** When a new component/screen replaces an old one, delete (or
redirect to) the old one in the SAME change. Most of the dead code removed in the cleanup
sweeps was old screens left behind when their replacements shipped (EditProfile, RelaySettings,
BackupScreen, the web OnboardingScreen / AccountChooserModal). A stale duplicate is worse than
no component: it splits behavior and gets picked up by the next search.

Canonical examples: `ProfileAvatar` / `OptimizedSmallAvatar` (avatars), `Jdenticon`
(identicon), `ShimmerEffect` / `SkeletonLoader` (loading), `AppButton` (button),
`InfoCard` / `WarningCard` (cards).

## Key Dependencies

| Library | Purpose | Platforms |
|---|---|---|
| Kotlin 2.2.20 | Language | All |
| Compose Multiplatform 1.9.1 | UI | Android + JVM + iOS (native only) |
| kotlin-wrappers (react, react-dom) | Web UI | JS only (BOM 2025.10.0, pinned to Kotlin 2.2.20) |
| Ktor 3.0 | WebSocket + HTTP | All |
| kotlinx.coroutines 1.10.2 | Async | All |
| kotlinx.serialization | JSON parsing | All |
| secp256k1-kmp | Elliptic curve crypto | Android + JVM |
| BouncyCastle | Additional crypto | Android + JVM |
| Coil 3.x / Kamel | Image loading (KMP) | All |
| androidx.security-crypto | EncryptedSharedPreferences | Android only |

## Working preferences

- Diagnose, fix, compile once. Avoid reading "neighbouring" files just to be thorough. The one
  exception that is never "fishing": before creating a UI component, grep `docs/components-index.md`
  for an existing one. That check is cheap and prevents the duplicate-then-dead-code cycle.
- One concern per commit, one concern per PR. Ship the PR the same day when possible.
- Commit message size proportional to diff size: trivial diffs get subject-line only.
- Stage files by name; never `git add -A` (the working tree often has untracked screenshots and planning docs that must not be versioned).
- When asked to plan, show the plan inline in the chat. Do not write plans to files unless asked.
- All user-facing strings must be in English. No Portuguese in UI text.
- **Comments: terse, present-tense, current behavior + the why.** Never narrate the diff (no
  "previously" / "no longer" / "used to" / "we now"); keep a past state only when it is
  load-bearing rationale that prevents a regression. Don't restate what the next line obviously
  does. Guardrails read as invariants.
- **Search online early for toolchain errors.** For anything about the external toolchain —
  kotlin-wrappers / React, Gradle, Compose Multiplatform, Ktor, Kotlin/JS — use WebSearch /
  WebFetch BEFORE guessing, and always before a *second* attempt at the same error. The web is
  authoritative here and internal recall is unreliable for version-specific behavior, exact API
  signatures, cryptic build errors, and icon/SVG paths. Don't wait to be asked; still verify the
  answer by compiling. (Past misses where a search would have been faster: kotlin-wrappers
  `useRef` signature, `Icons.Outlined.Bolt` path, the Kotlin/JS build-cache "Could not pack tree"
  error.)

## Slash commands

- `/nip <n>` — fetches NIP-`<n>` spec, greps the codebase for existing usage, suggests where to add code.

## Skills

- `nip29-expert` — implementing or debugging NIP-29 group functionality.
- `ui-parity` — adding/porting/modifying any screen or UI component; keeps Compose and web in sync (screen↔file↔VM map + primitive/token mapping).
