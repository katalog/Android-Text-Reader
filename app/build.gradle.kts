import java.util.Properties

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

// Supabase URL/publishable key도 소스 리터럴 대신 여기서 주입한다 — 값 자체가 비밀은 아니지만(RLS가
// 실제 방어선, SupabaseConfig.kt 참고) 공개 저장소 히스토리에 그대로 남는 걸 피하기 위해서다
// (SYNC_MULTIUSER_PLAN.md 스테이지 3). 로컬 개발은 local.properties(gitignore 대상)에 아래 두 키를
// 적어두면 되고, CI는 환경 변수로 넘긴다(release.yml 참고). 둘 다 없으면 빈 문자열로 빌드는 그대로
// 성공하고 — 이 프로젝트의 기존 원칙과 동일하게, VSCode 동기화 기능만 실행 시점에 조용히 비활성화된다
// (ReadingPositionSyncClient 호출이 실패하고 runCatching이 잡아서 무시함 — 릴리스 서명처럼 "빌드
// 자체를 막을 이유가 없는 선택적 기능"이라는 판단).
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
// .trim()이 중요하다 — GitHub Actions 시크릿은 웹 UI에 붙여넣을 때 끝에 개행/공백이 실수로 같이
// 들어가기 쉬운데, 그 상태로 URL 문자열 끝에 남으면 "https://...supabase.co /rest/v1/..."처럼 뒤에
// 공백이 낀 요청 경로가 만들어져 PostgREST가 "PGRST125: invalid path specified in request url"로
// 거부한다(실사용 중 실제로 겪음 — trimEnd('/')만으론 공백을 못 걸러냄). 로컬 local.properties 값도
// 사람이 손으로 옮겨 적다 실수할 수 있어 똑같이 trim한다.
val supabaseUrl = (System.getenv("SUPABASE_URL") ?: localProperties.getProperty("SUPABASE_URL") ?: "").trim()
val supabasePublishableKey = (System.getenv("SUPABASE_PUBLISHABLE_KEY")
    ?: localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY") ?: "").trim()

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
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabasePublishableKey\"")
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
        buildConfig = true
    }
}

// CameraX(camera-core, 스테이지 4)가 androidx.concurrent:concurrent-futures를 1.1.0으로 strictly
// 고정하는데, androidTest 쪽 espresso-core/androidx.test:core는 1.2.0을 원한다 — AGP의 "consistent
// resolution"이 androidTest 클래스패스를 메인 클래스패스와 강제로 맞추려다 두 버전이 충돌해
// kspDebugAndroidTestKotlin이 실패했다(실제로 겪음, 2026-09-03). 둘 다 1.2.0으로 강제해서 해결.
configurations.all {
    resolutionStrategy {
        force("androidx.concurrent:concurrent-futures:1.2.0")
        force("androidx.concurrent:concurrent-futures-ktx:1.2.0")
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

    // QR 페어링 스캐너 (SYNC_MULTIUSER_PLAN.md 스테이지 4) — 오프라인 동작하는 번들형 ML Kit 모델
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM 유닛 테스트에서 org.json을 실제로 동작시키기 위한 참조 구현 — android.jar의 org.json은
    // 스텁이라 유닛 테스트에서 호출하면 예외를 던진다(테스트 클래스패스에서만 이 실구현이 우선함).
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
