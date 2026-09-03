# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

**문카타 리더 (Moonkata Reader)** — 로컬 `.txt` 소설을 읽기 위한 오프라인 우선 단일 사용자 안드로이드 앱.
핵심 읽기 경험은 서버/로그인/네트워크 없이 완전 오프라인 동작. 기본 꺼짐인 선택적 기기 간 동기화 기능이
둘 있음 — VSCode와 읽기 위치 공유(Supabase 경유, `data/sync/ReadingPositionSyncClient.kt`), PC와 책
파일 동기화(자체 Go 트레이 서버, HTTPS+TLS 지문 고정, `data/sync/PcSyncClient.kt` +
`external_library/sync_server/`). Kotlin + Jetpack Compose, 수동 MVVM(`AndroidViewModel` +
Repository), DI 프레임워크 없음.

자세한 기능 목록과 설계 배경은 [README.md](README.md)(영문) / [README.ko.md](README.ko.md)(한글) 참고.
처음 이 저장소에 들어왔다면 [.docs/ONBOARDING.md](.docs/ONBOARDING.md)(빌드/테스트 실행법, 코드 지도,
자주 밟는 함정) → [.docs/DESIGN_RATIONALE.md](.docs/DESIGN_RATIONALE.md)("왜 이렇게 만들었나" — 각
결정을 순진한 구현과 대조해 설명) 순으로 읽으면 된다. 기능별 파일 단위 구현 설명은
[.docs/FEATURES.md](.docs/FEATURES.md), 사용자 시나리오별 코드 실행 흐름은
[.docs/USER_SCENARIOS.md](.docs/USER_SCENARIOS.md), 백로그는 [.docs/IDEAS.md](.docs/IDEAS.md).

## 빌드 & 테스트 명령어

```bash
# 디버그 빌드
./gradlew assembleDebug

# 순수 로직 유닛 테스트 (JVM만 필요, 기기/에뮬레이터 불필요, 빠름) — app/src/test
./gradlew :app:testDebugUnitTest

# 특정 유닛 테스트 클래스만
./gradlew :app:testDebugUnitTest --tests "com.moonkata.textreader.data.parser.ChapterDetectorEdgeCaseTest"

# 계측 테스트 (Compose UI, Room, DataStore 등 — 연결된 기기/에뮬레이터 필요) — app/src/androidTest
./gradlew :app:connectedDebugAndroidTest

# 특정 계측 테스트 클래스만
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.moonkata.textreader.ui.reader.PageNavigationRoundTripTest
```

- `compileSdk`/`targetSdk` 36, `minSdk` 24, Java 11, Kotlin 2.2.0.
- `RealFontDownloadIntegrationTest`, `FontApplyRepaginatesViewerTest`(androidTest)는 실제 인터넷 연결이 필요한 실 네트워크 테스트 — 오프라인이거나 폰트 배포처 URL이 깨지면 실패하는 게 의도된 동작.
- Phase 3(`PageNavigationRoundTripTest` 등)과 챕터 인식 회귀 테스트는 `app/src/androidTest/assets/books/`에 커밋된 퍼블릭 도메인 소설 픽스처(이광수 「무정」/「흙」, Gutenberg의 「Moby-Dick」/「Dracula」)를 사용하며, 혹시라도 없는 환경에서는 `Assume`으로 자동 스킵됨.
- 릴리스 서명: `RELEASE_KEYSTORE_PATH` 등 환경 변수가 없으면 `assembleRelease`도 조용히 디버그 서명으로 폴백 — 이 저장소(공개)에는 키스토어를 절대 커밋하지 않는다. 버전명/코드도 `RELEASE_VERSION_NAME`/`RELEASE_VERSION_CODE` 환경 변수([.github/workflows/release.yml](.github/workflows/release.yml)가 태그 push 시 주입)가 없으면 고정 폴백값(`1.0`/`1`)을 쓴다.
- VSCode 읽기 위치 동기화용 Supabase URL/publishable key도 같은 이유로 소스에 안 박아두고 `SUPABASE_URL`/`SUPABASE_PUBLISHABLE_KEY` 환경 변수(로컬은 `local.properties`로 대체 가능)로 주입 — 값이 없으면 빈 문자열로 조용히 빌드되고 VSCode 동기화 기능만 비활성화됨(`app/build.gradle.kts`, [.docs/SYNC_MULTIUSER_PLAN.md](.docs/SYNC_MULTIUSER_PLAN.md) 스테이지 3).

## 테스트를 어디에 둘지 결정하는 기준

- 대상 로직이 `Context`나 다른 Android 프레임워크 API 없이 순수 함수/클래스로 테스트 가능 → **`app/src/test`** (일반 JUnit)
- Compose 렌더링, Room/DataStore, 실제 `TextMeasurer` 등 Android 런타임이 필요 → **`app/src/androidTest`** (계측 테스트) — 헤드리스 `ReaderViewModel` 테스트라도 `AndroidViewModel`/Room/DataStore를 쓰면 여기 속함.

`ReaderViewModel`을 직접 구동하는 androidTest는 프로덕션 DataStore 파일을 그대로 사용하므로, 실기기에서 남은 설정(`autoAdvanceMode`, `chapterJumpEnabled` 등)이 내비게이션 경로 자체를 바꿔 비결정적 실패를 유발할 수 있음 — 새 테스트를 추가할 때 관련 설정을 강제로 고정하고 끝나면 복원하는 기존 패턴(`PageNavigationRoundTripTest` 등 참고)을 따를 것.

테스트 전체 계획, 각 테스트의 의도, 의도적으로 제외한 항목은 [.docs/TESTING.md](.docs/TESTING.md)에 단계별로 정리되어 있음 — 새 테스트를 추가하기 전에 먼저 확인할 것.

## 아키텍처

### 화면 흐름
`MainActivity` → `AppNavigation`(Navigation-Compose, 2개 라우트: `library`, `reader/{bookId}`) → `LibraryScreen` / `ReaderScreen`. `MainActivity`는 물리 키(볼륨키 등) `onKeyDown`을 현재 화면에 위임하는 역할만 함.

### 핵심 설계 결정 (코드를 고칠 때 반드시 이해하고 있어야 함)

**읽기 위치는 페이지 번호가 아니라 전체 텍스트 내 "문자 오프셋"으로 저장한다.** 폰트 크기/여백/화면 크기가 바뀌면 페이지 나누기 자체가 달라지므로, 페이지 인덱스는 항상 파생값으로 취급하고 Room(`BookEntity`)에는 오프셋만 저장한다.

**페이지 모드(`Paginator`, [Paginator.kt](app/src/main/java/com/moonkata/textreader/data/parser/Paginator.kt))는 책 전체를 미리 페이지네이션하지 않는다.** 지금 보여줄 페이지 하나(문자 오프셋 구간)만 그때그때 계산:
- 다음 페이지: 현재 페이지 끝 오프셋부터 한 페이지만 새로 측정 (`paginateFrom`)
- 이전 페이지: 정방향으로 넘기며 쌓은 방문 이력 스택을 되짚어 쓰거나, 이력이 없을 때만(예: 검색 점프 직후) `onePageEndingAt`으로 역산 추정
- 페이지 경계 측정은 문단별로 따로 재는 게 아니라 후보 텍스트 구간 전체를 하나의 `TextMeasurer.measure` 호출로 측정 — `ReaderPagerContent`가 실제로 페이지 전체를 하나의 `Text`로 그리기 때문에 문단별 높이 합산은 실제 렌더링과 어긋날 수 있음
- 페이지 전환 애니메이션은 `HorizontalPager`의 인덱스 기반이 아니라 `AnimatedContent` 기반 — 페이지 개수/인덱스를 미리 맞춰둘 필요가 없음

**목차(챕터)는 DB에 저장하지 않고 세션마다 정규식(`ChapterDetector` + `ChapterPatternCatalog`)으로 즉석에서 계산한다.** 스키마 마이그레이션 없이 패턴 추가/개선 가능, 매칭 0건이면 정상적으로 "목차 없음".

**자동 넘김은 `OFF / TIMER / TTS` 3중 상태 하나로 모델링한다** (`AutoPageTurnController`, `TtsController`) — 서로 다른 boolean 두 개로 두면 동시에 켜지는 충돌 상태가 생길 수 있어 처음부터 배제.

**챕터 점프 모드**(`ChapterJumpNavigator`)는 챕터 하나를 N등분해 그 breakpoint들을 순서대로 점프 — 활성화되면 `next()`/`previous()`가 페이지 단위 방문 이력 스택이 아니라 이 breakpoint 경로를 탄다(테스트 작성 시 주의).

### 레이어 구조

```
com.moonkata.textreader/
├── MainActivity.kt              — NavHost, 물리 키 이벤트 위임만
├── navigation/                  — library ↔ reader 화면 전환
├── data/
│   ├── db/            Room: BookEntity(오프셋 등 영구 저장), BookDao
│   ├── datastore/      DataStore 기반 ReaderSettings + Repository (폰트/여백/테마/탭존 등 앱 설정)
│   ├── file/           SafFolderBrowser(SAF 탐색, 재귀 스캔 아님), EncodingDetector(UTF-8/EUC-KR/CP949 자동감지), BookSource(zip 내부도 탐색)
│   ├── font/           FontCatalog(무료 한글 폰트 목록) + FontDownloadManager + FontResolver
│   ├── parser/         TextReflower(줄바꿈 정리) → Paginator가 소비, ChapterDetector/ChapterPatternCatalog, ChapterJumpNavigator
│   └── repository/     BookRepository — DB/파일/설정을 화면에 노출하는 단일 진입점
├── model/                        — Paragraph, Chapter, PageBreak, FolderEntry 등 도메인 모델
├── ui/
│   ├── library/                  — 폴더 브라우저 화면, "이어서 읽기" 다이얼로그
│   ├── reader/                   — 리더 화면, 퀵설정/목차/검색/폰트/챕터패턴 시트
│   └── theme/                    — 테마 프리셋
├── tts/                          — TtsController, AutoPageTurnController
└── util/                         — SAF/컬렉션 확장 함수
```

`ui/reader/ReaderViewModel`이 리더 화면의 핵심 상태 머신(약 700줄) — 페이지네이션 트리거, 방문 이력 스택, 검색, 목차, 챕터 점프, 자동 넘김/TTS, 원격 동기화 조율이 모두 여기 모임. 리더 관련 기능을 고칠 때는 대부분 이 파일에서 시작하게 됨.

`ui/reader/*Sheet.kt`(QuickSettingsSheet, TocSheet, SearchSheet, FontPickerSheet, ChapterPatternSheet)는 각각 `ModalBottomSheet` 하나씩 — `shouldDismissOnBackPress = false`로 두는 이유는 TESTING.md의 "의도적으로 제외" 항목 참고(Compose Material3의 알려진 제약, 우리 쪽 버그 아님).

### 의존성 관리
버전은 [gradle/libs.versions.toml](gradle/libs.versions.toml)의 버전 카탈로그에서 관리. 새 의존성 추가 시 `[versions]`/`[libraries]`에 등록 후 `app/build.gradle.kts`에서 `libs.xxx`로 참조.
