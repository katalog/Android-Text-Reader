import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

// The release keystore is never committed to this (public) repo — it's only passed in via an
// environment variable. If RELEASE_KEYSTORE_PATH is missing (a fork's local build, CI without this
// secret set, etc.), it silently falls back to debug signing so assembleRelease always just works.
val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")

// The Supabase URL/publishable key are also injected here instead of as a source literal — the
// value itself isn't secret (RLS is the real defense, see SupabaseConfig.kt), but this avoids it
// sitting permanently in the public repo's history (SYNC_MULTIUSER_PLAN.md stage 3). For local dev,
// put these two keys in local.properties (gitignored); CI passes them as env vars (see release.yml).
// If neither is set, the build still succeeds with empty strings — matching this project's existing
// principle, only the VSCode sync feature is silently disabled at runtime (the
// ReadingPositionSyncClient call fails and runCatching swallows it — judged, like release signing,
// as an "optional feature with no reason to block the build itself").
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
// The .trim() matters — pasting a GitHub Actions secret into the web UI can easily leave a trailing
// newline/space, and if that survives on the end of the URL string it produces a request path like
// "https://...supabase.co /rest/v1/..." with a stray space, which PostgREST rejects with
// "PGRST125: invalid path specified in request url" (hit this for real — trimEnd('/') alone doesn't
// catch a trailing space). The local local.properties value can suffer the same copy-paste mistake,
// so it's trimmed the same way.
val supabaseUrl = (System.getenv("SUPABASE_URL") ?: localProperties.getProperty("SUPABASE_URL") ?: "").trim()
val supabasePublishableKey = (System.getenv("SUPABASE_PUBLISHABLE_KEY")
    ?: localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY") ?: "").trim()

// For convenient real-device sync testing — debug builds only pre-fill the PC-sync/VSCode shared
// secrets (so a single "Test connection" tap connects immediately, no QR scan or manual typing
// needed). These aren't real credentials, just an arbitrary matching string, so committing them
// isn't risky, but they're tied to the developer's own PC/network, so — same pattern as
// SUPABASE_URL — they're only injected via local.properties (gitignored). Debug buildType only, so
// none of this ends up in the release APK (see LibraryViewModel.seedDebugSyncDefaultsIfBlank).
val debugPcSyncHost = (System.getenv("DEBUG_PC_SYNC_HOST") ?: localProperties.getProperty("DEBUG_PC_SYNC_HOST") ?: "").trim()
val debugPcSyncSecret = (System.getenv("DEBUG_PC_SYNC_SECRET") ?: localProperties.getProperty("DEBUG_PC_SYNC_SECRET") ?: "").trim()
val debugSupabaseSharedSecret = (System.getenv("DEBUG_SUPABASE_SHARED_SECRET")
    ?: localProperties.getProperty("DEBUG_SUPABASE_SHARED_SECRET") ?: "").trim()

// release.yml passes these two values from the tag (v1.1 → "1.1") and the CI run number — if
// missing (a local build), it falls back to the old fixed values. Without this, no matter which tag
// a release is cut from, the installed APK's actual displayed version would always stay at this
// fallback, so a tool like Obtainium's "latest tag" would disagree with the actual installed version.
val releaseVersionName = System.getenv("RELEASE_VERSION_NAME")
val releaseVersionCode = System.getenv("RELEASE_VERSION_CODE")?.toIntOrNull()

android {
    namespace = "com.moonkata.textreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moonkata.textreader"
        minSdk = 24
        targetSdk = 36
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabasePublishableKey\"")
        // Declared with an empty-string default so it still compiles in release too (shared code
        // references it) — the real value is only overridden in the debug buildType.
        buildConfigField("String", "DEBUG_PC_SYNC_HOST", "\"\"")
        buildConfigField("String", "DEBUG_PC_SYNC_SECRET", "\"\"")
        buildConfigField("String", "DEBUG_SUPABASE_SHARED_SECRET", "\"\"")
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEBUG_PC_SYNC_HOST", "\"$debugPcSyncHost\"")
            buildConfigField("String", "DEBUG_PC_SYNC_SECRET", "\"$debugPcSyncSecret\"")
            buildConfigField("String", "DEBUG_SUPABASE_SHARED_SECRET", "\"$debugSupabaseSharedSecret\"")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystorePath != null) "release" else "debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// CameraX (camera-core, stage 4) strictly pins androidx.concurrent:concurrent-futures to 1.1.0,
// while androidTest's espresso-core/androidx.test:core wants 1.2.0 — AGP's "consistent resolution"
// forcing the androidTest classpath to match the main classpath then collided the two versions,
// failing kspDebugAndroidTestKotlin (hit this for real, 2026-09-03). Fixed by forcing both to 1.2.0.
configurations.all {
    resolutionStrategy {
        force("androidx.concurrent:concurrent-futures:1.2.0")
        force("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    }
}

dependencies {
    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room (local DB)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // DataStore (reader settings storage)
    implementation(libs.androidx.datastore.preferences)

    // Automatic encoding detection (EUC-KR/CP949, etc.)
    implementation(libs.juniversalchardet)

    // SAF folder/file browsing
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.navigation.compose)

    // QR pairing scanner (SYNC_MULTIUSER_PLAN.md stage 4) — offline-capable, bundled ML Kit model
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The reference implementation needed to make org.json actually work in JVM unit tests —
    // android.jar's org.json is a stub that throws when called from a unit test (this real
    // implementation only takes priority on the test classpath).
    testImplementation(libs.json.java)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
