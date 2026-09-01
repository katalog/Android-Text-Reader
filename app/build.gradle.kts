plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
}

// 릴리스 키스토어는 이 저장소(공개)에 절대 커밋하지 않는다 — 환경 변수로만 넘긴다.
// RELEASE_KEYSTORE_PATH가 없으면(포크한 사람의 로컬 빌드, 이 시크릿을 안 넣은 CI 등) 조용히
// 디버그 서명으로 폴백해 assembleRelease가 항상 그냥 돌아가게 한다.
val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")

// release.yml이 태그(v1.1 → "1.1")와 CI 실행 번호로 이 두 값을 넘긴다 — 없으면(로컬 빌드) 예전
// 고정값으로 폴백한다. 이게 없으면 어떤 태그로 릴리스를 뽑든 설치된 APK의 실제 버전 표시는 항상
// 이 폴백값에 머물러서, Obtainium 같은 도구가 보여주는 "최신 태그"와 실제 설치 버전이 어긋난다.
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
    }
}

dependencies {
    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room (로컬 DB)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // DataStore (리더 설정 저장)
    implementation(libs.androidx.datastore.preferences)

    // 인코딩 자동 감지 (EUC-KR/CP949 등)
    implementation(libs.juniversalchardet)

    // SAF 폴더/파일 탐색
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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.mockwebserver)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
