# Nostrord KMP

Nostrord is a cross-platform Nostr NIP-29 (Groups) client built with Kotlin Multiplatform and Compose Multiplatform. Targets Android, iOS, Web (WASM/JS), and Desktop (JVM).

## Build & Run Commands

```bash
# Android
./gradlew :composeApp:assembleDebug

# Desktop (JVM)
./gradlew :composeApp:run

# Web (WASM)
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# Web (JS)
./gradlew :composeApp:jsBrowserDevelopmentRun

# iOS (requires macOS)
./gradlew :composeApp:iosSimulatorArm64Main
# Then open iosApp/iosApp.xcodeproj in Xcode

# Run tests
./gradlew :composeApp:allTests
```

## Architecture

### Source Organization
```
composeApp/src/
├── commonMain/kotlin/org/nostr/nostrord/
│   ├── nostr/      # Cryptography & Nostr protocol (NIP-01, NIP-04, NIP-44, NIP-46)
│   ├── network/    # Relay communication, state management
│   ├── storage/    # Secure storage (expect/actual)
│   ├── ui/
│   │   ├── theme/          # Centralized colors (NostrordColors)
│   │   ├── util/           # UI utilities (generateColorFromString)
│   │   ├── components/
│   │   │   ├── avatars/    # ProfileAvatar, AvatarPlaceholder
│   │   │   ├── buttons/    # AppButton
│   │   │   ├── cards/      # WarningCard, KeyCard, InfoCard
│   │   │   ├── chat/       # MessageItem, SystemEventItem, DateSeparator
│   │   │   ├── layout/     # ResponsiveScaffold
│   │   │   └── sidebars/   # Sidebar, GroupSidebar
│   │   ├── screens/
│   │   │   ├── home/       # HomeScreen, HomeScreenMobile, HomeScreenDesktop
│   │   │   ├── group/      # GroupScreen + model/ + components/
│   │   │   ├── relay/      # RelaySettingsScreen + model/ + components/
│   │   │   ├── backup/     # BackupScreen + components/
│   │   │   └── login/      # NostrLoginScreen + components/
│   │   └── Screen.kt       # Navigation sealed class
│   ├── model/      # Data models
│   └── utils/      # Utilities
├── androidMain/    # Android implementations
├── jvmMain/        # Desktop implementations
├── iosMain/        # iOS implementations
├── jsMain/         # JS web implementations
└── wasmJsMain/     # WASM implementations
```

### Key Patterns

- **Singleton Repository**: `NostrRepository` is the central state manager for auth, relays, groups, and messages
- **Expect/Actual**: Platform-specific code uses `expect` declarations in commonMain with `actual` implementations per platform (Crypto, SecureStorage, HttpClientFactory, Nip04, Nip44, Nip46Client)
- **Sealed Navigation**: `Screen` sealed class for type-safe navigation between screens
- **StateFlow**: All reactive state exposed via `StateFlow` from `NostrRepository`
- **Coroutines**: Async operations use `kotlinx.coroutines` with proper scope handling

### Key Files

- `network/NostrRepository.kt` - Central business logic (~1000 lines), handles auth, relay connections, group management
- `network/NostrGroupClient.kt` - WebSocket relay client, parses Nostr events
- `nostr/Event.kt` - Nostr Event model with NIP-01 signing/verification
- `storage/SecureStorage.kt` - Encrypted key and settings persistence
- `App.kt` - Main composable entry point with login state
- `ui/Screen.kt` - Navigation sealed class
- `ui/theme/Colors.kt` - Centralized color constants (NostrordColors)

## Code Style

- Package: `org.nostr.nostrord.*`
- Official Kotlin code style (`kotlin.code.style=official`)
- Composables: Standard function naming (lowercase start)
- Platform files: Use `.android.kt`, `.jvm.kt`, `.ios.kt` suffixes for actual implementations
- UI: Material3 with Discord-inspired dark theme (background: 0xFF36393F)

### Responsive Design
- Compact: <600dp (mobile, drawer navigation)
- Medium: 600-840dp (tablet, sidebar + 2-column)
- Large: >840dp (desktop, sidebar + 3-column)
- Use `BoxWithConstraints` for responsive layouts

## NIP Support

- **NIP-01**: Event signing and verification (SECP256K1)
- **NIP-04**: Legacy encryption
- **NIP-44**: Modern encryption
- **NIP-46**: Bunker/remote signer support
- **NIP-29**: Groups protocol (core feature)

## Dependencies

- Kotlin 2.2.20
- Compose Multiplatform 1.9.1
- Ktor 3.0.0 (networking)
- Kotlinx Coroutines 1.10.2
- Kotlinx Serialization (JSON)
- secp256k1-kmp (cryptography)
- Coil/Kamel (image loading)

## Platform Notes

### Android
- Min SDK 24, Target SDK 36
- Ktor engine: OkHttp
- Secure storage: EncryptedSharedPreferences

### Desktop (JVM)
- Ktor engine: CIO
- Secure storage: Java Preferences with AES-256

### iOS
- Static framework output
- Ktor engine: Darwin

### Web (WASM/JS)
- Deployed to GitHub Pages via CI/CD
- Browser-based secure storage

## Testing

Tests are in `commonTest`. Run with `./gradlew :composeApp:allTests`.

## Common Tasks

When modifying crypto or storage:
1. Update the `expect` declaration in commonMain
2. Implement `actual` in all platform modules (androidMain, jvmMain, iosMain, jsMain, wasmJsMain)

When adding new screens:
1. Add variant to `Screen` sealed class in `ui/Screen.kt`
2. Create feature folder in `ui/screens/` (e.g., `ui/screens/newfeature/`)
3. Create main screen file (e.g., `NewFeatureScreen.kt`)
4. Create mobile/desktop variants (e.g., `NewFeatureScreenMobile.kt`, `NewFeatureScreenDesktop.kt`)
5. Add feature-specific components in `components/` subfolder
6. Add models in `model/` subfolder if needed
7. Add navigation case in `App.kt`
8. Use `NostrordColors` from `ui/theme/Colors.kt` for consistent styling
