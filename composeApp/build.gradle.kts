import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

// Bulk message/event history store. The generated CacheDb interface lands in commonMain
// (pure Kotlin over the SqlDriver abstraction); only the native targets construct a real
// driver. The web (js) target compiles the generated code but never instantiates it — it
// uses the IndexedDB CacheStore actual instead, so DCE keeps SQLite out of the web bundle.
sqldelight {
    databases {
        create("CacheDb") {
            packageName.set("org.nostr.nostrord.storage.cache.db")
        }
    }
}

kotlin {
    // Suppress expect/actual classes beta warnings
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm()

    js {
        browser {
            // Configure webpack for production builds
            webpackTask {
                mainOutputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    // Custom intermediate source set `uiComposeMain` for the Compose Multiplatform UI,
    // shared only by android / jvm / ios. The web target (js) renders via React/DOM
    // and is intentionally excluded, so it never compiles the Compose UI. Done via the
    // hierarchy template (not manual dependsOn) to keep the default template intact.
    applyDefaultHierarchyTemplate {
        common {
            group("uiCompose") {
                withAndroidTarget()
                withJvm()
                // Nest the `ios` group (not bare withIos()) so the shared `iosMain`
                // intermediate refines uiComposeMain too — bare withIos() only attaches
                // the iosArm64/iosSimulatorArm64 leaves, leaving iosMain (where the
                // actuals + MainViewController live) unable to see the Compose UI sources.
                group("ios") {
                    withIos()
                }
            }
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation("io.github.nostrord:pomegranate-dealer:0.1.0")
            implementation(compose.preview)
            // Chrome Custom Tabs for the pomegranate Google sign-in (system browser, not WebView).
            implementation("androidx.browser:browser:1.8.0")
            implementation(libs.androidx.activity.compose)
            implementation("io.ktor:ktor-client-okhttp:3.4.2")
            implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.14.0")
            implementation("androidx.security:security-crypto:1.1.0")
            implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            // Animated GIF support: provides AnimatedImageDecoder (API 28+) and GifDecoder (API < 28)
            implementation("io.coil-kt.coil3:coil-gif:3.3.0")
            // Media3 ExoPlayer for video playback (same stack as Amethyst)
            implementation("androidx.media3:media3-exoplayer:1.6.0")
            implementation("androidx.media3:media3-ui:1.6.0")
            implementation(libs.sqldelight.android.driver)
        }

        commonMain.dependencies {
            // Only compose.runtime here (lightweight, no Skiko) — for @Immutable/@Stable
            // and the @Composable platform helpers. The web (js) target compiles
            // commonMain, so the heavy Compose UI libs (which pull Skiko) live in
            // uiComposeMain instead, keeping Skia out of the React/DOM web bundle.
            implementation(compose.runtime)
            // compose-resources must live in commonMain (the resources plugin generates
            // the `Res` class for commonMain). It transitively references compose.ui, but
            // nothing on the js entry path uses `Res`, so DCE strips Skiko from the web
            // bundle anyway. Only the Compose targets actually render fonts/images.
            implementation(compose.components.resources)
            // lifecycle-viewmodel (base, KMP, no Skiko) lives here as `api` so the shared
            // screen ViewModels in commonMain — consumed by both the Compose UI and the
            // React web UI — resolve their ViewModel / viewModelScope supertypes on every
            // target, including js.
            api(libs.androidx.lifecycle.viewmodel)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

            implementation("io.ktor:ktor-client-core:3.4.2")
            implementation("io.ktor:ktor-client-websockets:3.4.2")
            implementation("io.ktor:ktor-client-content-negotiation:3.4.2")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.2")
            implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
            // SQLDelight runtime: the generated CacheDb interface lives in commonMain. The
            // platform SQLite drivers live in the native source sets; web never constructs one.
            implementation(libs.sqldelight.runtime)
        }

        // Compose Multiplatform UI deps — shared by android / jvm / ios only (not js).
        // This is what keeps Skiko/Compose out of the React web bundle.
        val uiComposeMain by getting {
            dependencies {
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation("io.coil-kt.coil3:coil-compose:3.3.0")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.3.0")
                // SVG decoder so inline data:image/svg+xml avatars (some profile/relay
                // icons) render instead of failing the fetcher on native targets.
                implementation("io.coil-kt.coil3:coil-svg:3.3.0")
                implementation("io.github.alexzhirkevich:qrose:1.0.1")
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }

        iosMain.dependencies {
            implementation("io.github.nostrord:pomegranate-dealer:0.1.0")
            implementation(libs.sqldelight.native.driver)
            // Darwin engine for the Ktor WebSocket/HTTP client on iOS.
            implementation("io.ktor:ktor-client-darwin:3.4.2")
            // secp256k1 for keygen/Schnorr/ECDH — same backend as desktop (statically
            // linked native libsecp256k1 via cinterop).
            implementation("fr.acinq.secp256k1:secp256k1-kmp:0.14.0")
        }

        jvmMain.dependencies {
            implementation("io.github.nostrord:pomegranate-dealer:0.1.0")
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.sqldelight.sqlite.driver)
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("media.kamel:kamel-image:0.9.5")
            implementation("io.ktor:ktor-client-cio:3.4.2")
            implementation("fr.acinq.secp256k1:secp256k1-kmp:0.14.0")
            implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.14.0")
            implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            implementation("org.slf4j:slf4j-nop:2.0.9")
            // Inline video player (GStreamer on Linux, MediaFoundation on Win, AVPlayer on Mac)
            implementation("io.github.kdroidfilter:composemediaplayer:0.8.3")
            // JavaFX WebView for YouTube iframe on desktop
            val fxVersion = "17.0.14"
            val os =
                org.gradle.internal.os.OperatingSystem
                    .current()
            val fxClassifier =
                when {
                    os.isLinux -> "linux"
                    os.isMacOsX -> "mac"
                    os.isWindows -> "win"
                    else -> "linux"
                }
            implementation("org.openjfx:javafx-base:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-graphics:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-controls:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-web:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-swing:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-media:$fxVersion:$fxClassifier")
            // Pure-Java MP3 decoder for notification chime — avoids the GStreamer
            // native-library matrix that JavaFX Media relies on.
            implementation("javazoom:jlayer:1.0.1")
            implementation("com.github.javakeyring:java-keyring:1.0.3")
            // Native file picker (zenity/kdialog subprocess on Linux) via LWJGL tinyfd.
            // Chosen over java.awt.FileDialog (GNOME focus-stealing on repeat opens) and
            // over FileKit (its dbus-java 5.x clashes with java-keyring's Secret Service
            // dbus-java 3.x, breaking the OS keychain). tinyfd pulls no dbus-java.
            val lwjglVersion = "3.3.4"
            val osArch = System.getProperty("os.arch")
            val lwjglNatives =
                when {
                    os.isLinux -> if (osArch.startsWith("aarch64")) "natives-linux-arm64" else "natives-linux"
                    os.isMacOsX -> if (osArch.startsWith("aarch64")) "natives-macos-arm64" else "natives-macos"
                    os.isWindows -> "natives-windows"
                    else -> "natives-linux"
                }
            implementation("org.lwjgl:lwjgl:$lwjglVersion")
            implementation("org.lwjgl:lwjgl-tinyfd:$lwjglVersion")
            runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
            runtimeOnly("org.lwjgl:lwjgl-tinyfd:$lwjglVersion:$lwjglNatives")
        }

        jsMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.4.2")
            // Compression webpack plugin for production builds
            implementation(devNpm("compression-webpack-plugin", "11.1.0"))

            // React via kotlin-wrappers — web UI is migrating off Compose Canvas
            // (Skia) to real DOM/React. BOM keeps all wrapper modules in sync.
            // (kotlin-css / kotlin-react-router / tanstack-virtual get added as the
            // screen rewrite needs them.)
            // Pin: 2025.10.0 is the wrapper line built against Kotlin 2.2.20 (this
            // project's version). Newer 2026.x wrappers target Kotlin 2.3.x and their
            // klibs are not consumable by the 2.2.20 compiler (serialization ICE).
            implementation(project.dependencies.platform("org.jetbrains.kotlin-wrappers:kotlin-wrappers-bom:2025.10.0"))
            implementation("org.jetbrains.kotlin-wrappers:kotlin-react")
            implementation("org.jetbrains.kotlin-wrappers:kotlin-react-dom")
            // js.objects.unsafeJso, used for per-seed inline styles (gradient avatars).
            implementation("org.jetbrains.kotlin-wrappers:kotlin-js")

            // Pure-JS QR generator for the NIP-46 nostrconnect login (no canvas needed).
            implementation(npm("qrcode-generator", "1.4.4"))

            // FROST trusted dealer for pomegranate (Login with Google). Hosted on JSR;
            // the npm: alias + the @jsr registry mapping (root build.gradle.kts) resolve it.
            implementation(npm("@fiatjaf/promenade-trusted-dealer", "npm:@jsr/fiatjaf__promenade-trusted-dealer@0.4.3"))
        }
    }
}

// Print full stack traces (incl. `Caused by:`) on test failure. The default terse
// output hides the cause of cross-test coroutine leaks (UncaughtExceptionsBeforeTest),
// making them impossible to diagnose from CI logs.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        showExceptions = true
    }
    // Keep unit tests off the real OS keyring: java-keyring's Secret Service (dbus-java)
    // reader thread races its own executor shutdown and the stray RejectedExecutionException
    // poisons a later runTest as UncaughtExceptionsBeforeTest (intermittent cross-class
    // failure). KeychainStore honors this and SecureStorage uses its no-keychain fallback.
    systemProperty("nostrord.disableKeychain", "true")
}

val appVersion = project.property("app.version") as String
val appVersionCode = (project.property("app.versionCode") as String).toInt()

// Release signing — reads from keystore.properties at the repo root (gitignored).
// Devs without the file can still build debug; release builds require it.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }

android {
    namespace = "org.nostr.nostrord"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "org.nostr.nostrord"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = appVersionCode
        versionName = appVersion
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile").trim())
                storePassword = keystoreProps.getProperty("storePassword").trim()
                keyAlias = keystoreProps.getProperty("keyAlias").trim()
                keyPassword = keystoreProps.getProperty("keyPassword").trim()
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "org.nostr.nostrord.MainKt"

        jvmArgs(
            "-Dsun.java2d.wmClassName=Nostrord",
            // JavaFX module access for WebView/MediaPlayer when loaded from classpath
            "--add-opens",
            "java.base/java.lang=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.io=ALL-UNNAMED",
            "--add-exports",
            "java.base/sun.nio.ch=ALL-UNNAMED",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Nostrord"
            packageVersion = appVersion

            // jpackage's jlink image ships only Compose's default modules; java-keyring's
            // DBus backend touches java.sql.DriverManager at startup and kills the app
            // with NoClassDefFoundError when the module is absent (#165). jdk.unsupported
            // provides sun.misc.Unsafe for jnr-ffi (dbus-java transport); without it
            // Keyring.create() throws BackendNotSupportedException and the packaged app
            // falls back to the passphrase gate even with a Secret Service running.
            // jdk.httpserver provides com.sun.net.httpserver.HttpServer, which the Google
            // login flow binds on localhost to catch the OAuth redirect; without it the
            // sign-in dialog dies with NoClassDefFoundError in the packaged app.
            modules("java.sql", "jdk.unsupported", "jdk.httpserver")

            linux {
                iconFile.set(project.file("src/jvmMain/resources/icon-512.png"))
                debPackageVersion = packageVersion
                rpmPackageVersion = packageVersion
                appCategory = "Network"
            }

            macOS {
                // jpackage requires .icns here; a .png is silently ignored and the
                // bundle ships the default Java icon.
                iconFile.set(project.file("src/jvmMain/resources/icon.icns"))
                bundleID = "org.nostr.nostrord"
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>Nostrord Deep Link</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>nostrord</string>
                                </array>
                            </dict>
                        </array>
                    """
                }
            }

            windows {
                // Same trap as macOS: jpackage requires .ico here; a .png is silently
                // ignored and the exe/taskbar shows the default Java icon (#107).
                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
                menuGroup = "Nostrord"
                shortcut = true
                // Stable GUID so MSI upgrades replace the install instead of duplicating it.
                upgradeUuid = "9f3a6c1e-2d47-4b8a-9c05-7d1e84a3f6b2"
            }
        }

        buildTypes.release {
            proguard {
                configurationFiles.from(project.file("proguard-rules.pro"))
            }
        }
    }
}

// Post-process .deb to install icons into hicolor theme and fix .desktop metadata.
// jpackage does NOT do this — it dumps a single PNG into /opt/<app>/lib/ and references
// it by absolute path, which GNOME on Debian does not reliably resolve.
tasks.register("fixDebPackage") {
    description = "Repack .deb with hicolor icons and correct .desktop metadata"
    dependsOn("packageDeb")

    // Resolve paths at configuration time (configuration-cache safe)
    val debDir = layout.buildDirectory.dir("compose/binaries/main/deb")
    val repackDir = layout.buildDirectory.dir("deb-repack")
    val iconSrcDir = layout.projectDirectory.dir("src/jvmMain/resources/linux-icons")

    doLast {
        fun run(vararg args: String) {
            val proc = ProcessBuilder(*args).inheritIO().start()
            check(proc.waitFor() == 0) { "Command failed: ${args.joinToString(" ")}" }
        }

        val debFile =
            debDir
                .get()
                .asFile
                .listFiles()!!
                .first { it.extension == "deb" }
        val workDir = repackDir.get().asFile
        workDir.deleteRecursively()
        workDir.mkdirs()

        val extractDir = File(workDir, "extract")
        val controlDir = File(workDir, "control")
        extractDir.mkdirs()
        controlDir.mkdirs()

        // Extract .deb
        run("dpkg-deb", "-x", debFile.absolutePath, extractDir.absolutePath)
        run("dpkg-deb", "-e", debFile.absolutePath, controlDir.absolutePath)

        // Install icons into hicolor
        val sizes = listOf(16, 32, 48, 64, 128, 256, 512)
        for (s in sizes) {
            val hicolorDir = File(extractDir, "usr/share/icons/hicolor/${s}x$s/apps")
            hicolorDir.mkdirs()
            val src = File(iconSrcDir.asFile, "icon-$s.png")
            if (src.exists()) {
                src.copyTo(File(hicolorDir, "nostrord.png"), overwrite = true)
            }
        }

        // Patch .desktop file: use icon theme name, add StartupWMClass and proper Categories
        // walkTopDown enumerates dirs too — the bundled JDK runtime contains a
        // legal/java.desktop directory that would match without the isFile filter.
        val desktopFile = extractDir.walkTopDown().first { it.isFile && it.extension == "desktop" }
        // On Wayland, Java AWT derives app_id from the main class name:
        // org.nostr.nostrord.MainKt → org-nostr-nostrord-MainKt
        // StartupWMClass MUST match this for GNOME to associate the window.
        // On X11, sun.java2d.wmClassName=Nostrord overrides WM_CLASS,
        // so we list both as a fallback chain via StartupWMClass matching the Wayland value.
        val patchedDesktop =
            """
            |[Desktop Entry]
            |Name=Nostrord
            |Comment=Nostrord - NOSTR NIP-29 Client
            |Exec=/opt/nostrord/bin/Nostrord %u
            |Icon=nostrord
            |Terminal=false
            |Type=Application
            |Categories=Network;InstantMessaging;Chat;
            |StartupWMClass=org-nostr-nostrord-MainKt
            |MimeType=x-scheme-handler/nostrord;x-scheme-handler/nostr;
            """.trimMargin() + "\n"
        desktopFile.writeText(patchedDesktop)

        // Patch postinst to run gtk-update-icon-cache after install
        val postinst = File(controlDir, "postinst")
        val postinstText = postinst.readText()
        val patchedPostinst =
            postinstText.replace(
                "xdg-desktop-menu install /opt/nostrord/lib/nostrord-Nostrord.desktop",
                "xdg-desktop-menu install /opt/nostrord/lib/nostrord-Nostrord.desktop\n" +
                    "    xdg-mime default nostrord-Nostrord.desktop x-scheme-handler/nostrord 2>/dev/null || true\n" +
                    "    xdg-icon-resource forceupdate --theme hicolor 2>/dev/null || true\n" +
                    "    gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true",
            )
        postinst.writeText(patchedPostinst)

        // Patch prerm to clean up hicolor icons on uninstall
        val prerm = File(controlDir, "prerm")
        val prermText = prerm.readText()
        val d = '$'
        val cleanupScript =
            "xdg-desktop-menu uninstall /opt/nostrord/lib/nostrord-Nostrord.desktop\n" +
                "    for s in 16 32 48 64 128 256 512; do\n" +
                "        rm -f \"/usr/share/icons/hicolor/$d{s}x$d{s}/apps/nostrord.png\"\n" +
                "    done\n" +
                "    gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true"
        val patchedPrerm =
            prermText.replace(
                "xdg-desktop-menu uninstall /opt/nostrord/lib/nostrord-Nostrord.desktop",
                cleanupScript,
            )
        prerm.writeText(patchedPrerm)

        // Repack .deb — copy control files into DEBIAN dir and set correct permissions
        val debianDir = File(extractDir, "DEBIAN")
        debianDir.mkdirs()
        controlDir.listFiles()?.forEach { file ->
            val dest = File(debianDir, file.name)
            file.copyTo(dest, overwrite = true)
            // Maintainer scripts must be executable (dpkg-deb requires >=0555)
            if (file.name in listOf("postinst", "prerm", "postrm", "preinst")) {
                dest.setExecutable(true, false)
            }
        }

        run("dpkg-deb", "--build", "--root-owner-group", extractDir.absolutePath, debFile.absolutePath)

        println("Repacked .deb with hicolor icons: ${debFile.name}")
    }
}

// Stamp build version into index.html and sw.js for cache busting
abstract class StampBuildVersionTask : DefaultTask() {
    @get:InputDirectory
    abstract val distDir: DirectoryProperty

    @TaskAction
    fun stamp() {
        val buildVersion = System.currentTimeMillis().toString()
        val dir = distDir.get().asFile
        listOf("index.html", "sw.js").forEach { filename ->
            val file = File(dir, filename)
            if (file.exists()) {
                file.writeText(file.readText().replace("__BUILD_VERSION__", buildVersion))
            }
        }
        println("Stamped build version $buildVersion into web assets")
    }
}

tasks.register<StampBuildVersionTask>("stampJsBuildVersion") {
    dependsOn("jsBrowserProductionWebpack")
    mustRunAfter("copyJsCompressedFiles")
    distDir.set(layout.buildDirectory.dir("dist/js/productionExecutable"))
}

// Copy pre-compressed files for js target
tasks.register<Copy>("copyJsCompressedFiles") {
    dependsOn("jsBrowserProductionWebpack")
    from(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) {
        include("**/*.gz", "**/*.br")
    }
    into(layout.buildDirectory.dir("dist/js/productionExecutable"))
}

// Make distribution tasks depend on copy and stamp tasks
tasks.matching { it.name == "jsBrowserDistribution" }.configureEach {
    finalizedBy("copyJsCompressedFiles", "stampJsBuildVersion")
}

// Exclude the Kotlin/JS compile from the Gradle build cache: storing its output
// intermittently fails to pack the incremental IR (ir-translation-result.tab.values.at ->
// "Could not get file mode"), which fails the build AFTER the compile already succeeded.
// Incremental compilation still applies; only cross-build cache reuse is skipped for these.
// JVM / Android tasks keep caching (where CI gets its reuse).
tasks.matching { it.name == "compileKotlinJs" || it.name == "compileTestKotlinJs" }.configureEach {
    outputs.cacheIf { false }
}
