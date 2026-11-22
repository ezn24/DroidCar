import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Version convention: `MAJOR.MINOR.PATCH` (semantic versioning).
 *
 * `versionCode` is **derived** from `versionName` instead of being filled in by hand in two places: the two drifting apart is the
 * classic cause of broken updates (the system only looks at versionCode; if it is too small users cannot install the new package, with no
 * hint at all).
 * 0.x marks the experimental stage; before a release only this one line needs changing.
 *
 * On every push CI (`.github/workflows/android-ci.yml`) passes
 * `-Pdroidcar.buildNumber=<commit count>` (`git rev-list --count HEAD`), yielding `0.1.13.<N>` / `versionCode = base*10000+N`.
 * Local builds must pass the same value, so that the same commit gets the same version number on both sides
 * (for a **release-signed** package, see [buildNumber] below); no commit needs to be written to the repository.
 */
val appVersionName = "1.43"

/**
 * `major.minor` (from 1.43 on; shown as v1.43.<build>). 1.43 maps to the same 143 the old three-part 0.1.43 did, so
 * installed builds update in place; each minor step adds 1 (minor < 100), each major step 100.
 */
val appVersionCode =
    appVersionName
        .split(".")
        .map { it.toInt() }
        .let { parts ->
            when (parts.size) {
                2 -> parts[0] * 100 + parts[1].also { check(it in 0..99) { "minor version must be 0..99" } }
                3 -> parts[0] * 10_000 + parts[1] * 100 + parts[2]
                else -> error("appVersionName must be major.minor (got \"$appVersionName\")")
            }
        }

/**
 * Pre-release build number (both CI and local builds pass `-Pdroidcar.buildNumber=$(git rev-list --count HEAD)`).
 *
 *  - `versionName` gets `.N` appended: `1.43` -> `1.43.236`;
 *  - `versionCode` reserves the low 4 digits for the build number: `113` -> `1_130_042`.
 *
 * Effect: under the same base every push is **monotonically increasing** and can directly install over the previous one; when the base
 * moves to the next
 * patch (`0.1.14` -> `1_140_000`) it automatically overtakes all builds of this patch (requires N < 10000).
 * When not passed it falls back to the plain base (`1_130_000`), so local/fork builds still work.
 *
 * **Passing a build number requires a real keystore**: otherwise the package would go out as a "release", and
 * packages signed with the debug certificate force every user to uninstall and reinstall when a real certificate is adopted later.
 */
val buildNumberText =
    providers.gradleProperty("droidcar.buildNumber").orNull?.takeIf { it.isNotBlank() }
val buildNumber = buildNumberText?.toIntOrNull()
check(buildNumberText == null || buildNumber != null) {
    "droidcar.buildNumber must be an integer (got \"$buildNumberText\")"
}
check(buildNumber == null || buildNumber in 1..9_999) {
    "droidcar.buildNumber must be within 1..9999 (the low 4 digits of versionCode are reserved for the build number), got $buildNumber"
}
val effectiveVersionName = if (buildNumber == null) appVersionName else "$appVersionName.$buildNumber"
val effectiveVersionCode =
    if (buildNumber == null) appVersionCode * 10_000 else appVersionCode * 10_000 + buildNumber

/**
 * Release signing config: read from `keystore.properties` outside the repository (excluded in .gitignore).
 * When the file is missing, release falls back to **debug signing**, only for forkers/local verification;
 * CI pre-release builds (with `-Pdroidcar.buildNumber`) fail outright and never produce a debug-signed "release".
 *
 * A leaked key = anyone can sign your updates, so the keystore and passwords are **never committed**.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps =
    Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

// Pre-release/CI builds (with a build number) must not fall back to debug signing: better to fail the build than to ship a
// "release" that a real-signed package can never install over later.
check(buildNumber == null || hasReleaseKeystore) {
    "-Pdroidcar.buildNumber was passed but there is no keystore.properties -- pre-release builds must not use debug signing"
}

android {
    namespace = "io.github.ezn24.droidcar"
    compileSdk = 34

    defaultConfig {
        // DroidCar's own package (hard fork, 2026-11-23): installs next to upstream CarDeck and the earlier CarDeck Neo builds
        applicationId = "io.github.ezn24.droidcar"
        minSdk = 31
        targetSdk = 34
        versionCode = effectiveVersionCode
        versionName = effectiveVersionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // bridge dimension: standard = everyday/release (package name unchanged; Shizuku grants are per package name, do not touch);
    // remote = remote-debug build (separate package name .rd, installable alongside the everyday build; the debug bridge code only goes
    // into this variant)
    flavorDimensions += "bridge"
    productFlavors {
        create("standard") {
            dimension = "bridge"
        }
        create("remote") {
            dimension = "bridge"
            applicationIdSuffix = ".rd"
            versionNameSuffix = "-rd"
        }
    }

    buildTypes {
        release {
            // **No code obfuscation**: the project relies on reflection (SystemProperties, Shizuku API) and
            // custom AIDL, so R8 needs extra keep rules; the first release just has to run, obfuscation is left for later.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
        debug {
            // **Do not add applicationIdSuffix**: the debug build here is the everyday package:
            // Shizuku grants are per package name, so changing it would require re-authorization; the
            // `run-as io.github.ezn24.droidcar` debug path in AGENTS.md would break as well.
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        // The remote variant reports BuildConfig.VERSION_NAME to the server (disabled by default in AGP 8)
        buildConfig = true
    }

    testOptions {
        // The protocol-layer replay tests drive the real AirPlayClient; Android calls such as SystemClock in request()
        // can just return default values on the JVM (otherwise "not mocked" blows up immediately)
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // A new lint issue (error level) fails the build directly; existing issues are exempted in the baseline file.
        //  ./gradlew :app:lintDebug          -- check (the pre-commit hook runs it automatically before commit)
        //  ./gradlew :app:updateLintBaseline -- rebuild the baseline after fixing issues (for intentional exemptions)
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // CarPlay-style desktop: paged grid
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // AirPlay pairing cryptography: X25519/Ed25519/ChaCha20-Poly1305/HKDF-SHA512
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Invisible-display mode: shell permission (activated via wireless debugging, no root needed)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    // In local unit tests Android's org.json is a stub (every method throws "not mocked"):
    // replace it with a reference implementation of the same API (needed by replay fixtures and nav parsing tests)
    testImplementation("org.json:json:20240303")
}
