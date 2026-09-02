# 테스트 계획 및 진행 상황

`app/src/androidTest`(계측/Compose UI)와 `app/src/test`(순수 로직, 일반 JUnit)에 있는 테스트의 전체
계획과 현재 상태를 기록하는 문서. 진행하면서 계속 업데이트합니다.
(기능 백로그는 [`IDEAS.md`](IDEAS.md), 앱 소개/설계는 [`README.md`](../README.md) 참고)

## 어디에 테스트를 두나

- **`app/src/test`** — Android 런타임(Context, Room, DataStore, Compose, 실제 `TextMeasurer` 등)이
  전혀 필요 없는 순수 로직. 기기/에뮬레이터 없이 JVM에서 바로 돌아 훨씬 빠르다
  (`./gradlew :app:testDebugUnitTest`). 예: `ChapterDetector`/`ChapterPatternCatalog`,
  `ChapterJumpNavigator`, `EncodingDetector`(합성 바이트로 검증 가능한 부분).
- **`app/src/androidTest`** — Compose UI를 실제로 렌더링하거나, Room/DataStore/실제 파일 I/O처럼
  Android 런타임이 필요한 것. "화면에 안 보이는" 헤드리스 `ReaderViewModel` 테스트라도 실제
  `AndroidViewModel`/Room/DataStore/`TextMeasurer`를 쓰면 여기 속한다(JVM 단독으로는 안 돌아감).

새 테스트를 추가할 때 기준: 대상 로직이 `Context`나 다른 Android 프레임워크 API 없이 순수 함수/클래스로
테스트 가능하면 `app/src/test`로, 그렇지 않으면(Compose 렌더링이든 Room/DataStore 같은 Android 런타임
의존이든) `app/src/androidTest`로 그대로 둔다.

## 배경

기존에 짜둔 테스트 몇 개(뒤로가기 2단계 처리, 이어서 읽기 다이얼로그, 폴더 탐색 시나리오)는 페이지네이션을
전면 재설계하면서 전제가 바뀌거나 범위가 좁아, 전부 지우고 아래 페이즈대로 다시 짠다. 목표는 세 가지를
겹치지 않게 커버하는 것:
1. 안드로이드 앱에 일반적으로 포함되는 테스트(스모크, 네비게이션, 빈/로딩 상태, 다이얼로그, 바텀시트 뒤로가기)
2. 이 앱의 특성상 필요한 테스트(대용량 실제 소설로 페이지네이션·챕터인식·인코딩 검증)
3. 대화하면서 실제로 고쳤던 버그/기능에 대한 회귀 테스트

## 공용 테스트 인프라

- `testutil/TestBooks.kt` — `androidTest/assets/books/`에 커밋된 퍼블릭 도메인 소설 픽스처를 캐시
  파일로 복사해 `file://` URI로 접근 가능하게 하고, 인메모리 Room에 `BookEntity`로 등록. 픽스처가 없는
  환경(sparse checkout 등)에서도 안전하도록 `Assume`으로 테스트를 스킵 처리하는 방어 로직은 남겨둠.
- `testutil/TestTextMeasurer.kt` — Compose 렌더링 없이도 진짜 텍스트 측정이 되는 `TextMeasurer`를
  직접 생성 — `ReaderViewModel`/`Paginator`를 전체 화면 없이 빠르게 검증할 때 사용.
- `ui/library/FakeFolderBrowser.kt` — 실제 SAF 권한 없이 미리 정해둔 폴더 목록을 돌려주는 테스트 더블
  (이미 있음, 유지).

**주의 — 실제 DataStore 오염**: `ReaderViewModel`을 직접 구동하는 헤드리스 테스트(Phase 3)는
`ReaderSettingsRepository(application)`을 그대로 쓰는데, 이건 앱이 실제로 쓰는 프로덕션 DataStore
파일과 같다. 그래서 실기기에서 이전에 수동으로 테스트하며 남겨둔 설정이 그대로 테스트에 섞여 들어가
비결정적으로 실패할 수 있다. 실제로 겪은 두 가지:
- `autoAdvanceMode=TIMER`가 남아있으면 백그라운드에서 몰래 `next()`를 더 호출해 방문 이력 스택이
  테스트가 센 횟수와 어긋남.
- `chapterJumpEnabled=true`가 남아있으면 `next()`/`previous()`가 방문 이력 스택이 아니라
  `ChapterJumpNavigator`의 목차 등분 breakpoint 경로(그때그때 `jumpToPageAt`으로 이력을 비움)를 타서,
  페이지 단위 왕복 테스트와는 전혀 다른 이동 방식이 돼버림 — `pageTurnMode`/`autoAdvanceMode`만 고정하고
  이걸 놓쳐서 처음엔 원인을 못 찾고 같은 실패가 재발했다.

`PageNavigationRoundTripTest`, `JumpToFarOffsetTest`는 실행 전 필요한 설정(`pageTurnMode=HORIZONTAL_PAGE`,
`autoAdvanceMode=OFF`, `chapterJumpEnabled=false`)을 강제로 고정하고 끝나면 원래 값으로 복원하는 패턴을
쓴다 — `ReaderViewModel`을 직접 구동하는 새 테스트를 추가할 때, 그 테스트가 건드리는 내비게이션 경로에
영향을 주는 설정이 있는지 먼저 따져보고 같은 패턴을 따를 것.

## 픽스처 (2026-09-01 기준 — 퍼블릭 도메인으로 교체)

`androidTest/assets/books/`는 원래 저작권이 있는 번역 웹소설로 채워져 있어 로컬 전용으로 gitignore
해뒀었는데, 공개 저장소로 옮기면서 전부 퍼블릭 도메인 소설로 교체하고 커밋했다. 전부 UTF-8, 4개:

- `Heuk.txt` — 이광수 「흙」(1932, 저자 사후 70년 경과로 한국 저작권 만료), [한국어 위키문헌](https://ko.wikisource.org/wiki/흙) 원문. 실제 장 구분(제1장~제5장)을 살려 각 장
  시작에 `## 제N장` 헤더를 넣어둠 — 기본 챕터 인식 프리셋(`##`로 시작)과 맞아떨어지는 정탐 케이스이자,
  대부분의 테스트가 쓰는 범용 대용량 픽스처.
- `Mujeong.txt` — 이광수 「무정」(1917, 마찬가지로 저작권 만료), [한국어 위키문헌](https://ko.wikisource.org/wiki/무정) 원문. `##` 헤더가 전혀 없는 연속 산문이라
  "챕터 없음"이 정상 동작임을 검증하는 좋은 사례로 씀(예전 `Yellow Radio.txt`의 역할).
- `MobyDick.txt` — Herman Melville, 『Moby-Dick』([Project Gutenberg #2701](https://www.gutenberg.org/ebooks/2701), 라이선스 boilerplate는
  제거하고 본문만 남김).
- `Dracula.txt` — Bram Stoker, 『Dracula』([Project Gutenberg #345](https://www.gutenberg.org/ebooks/345), 마찬가지로 본문만).

EUC-KR 픽스처는 없어서 인코딩 감지 테스트는 합성 데이터를 따로 만들어야 함(→ `EncodingDetectorTest`).
영어 픽스처 2개는 현재 어떤 테스트에서도 아직 안 쓰고 있음 — 다국어 페이지네이션/리플로우 검증용으로
비축.

## 페이즈

- [x] **Phase 0 — 정리**: 기존 테스트 4개 삭제 (`FakeFolderBrowser`는 인프라라 유지)
- [x] **Phase 1 — 공용 인프라**: `TestBooks`, `TestTextMeasurer` 추가
- [x] **Phase 2 — 일반적으로 포함되는 테스트**
  - 앱 실행 스모크 테스트 + 라이브러리 빈 상태
  - 리더 화면 기본 렌더링(로딩→콘텐츠) + 뒤로가기 콜백
  - "이어서 읽기" 다이얼로그 확인/취소
  - ~~검색창/챕터패턴시트 뒤로가기 2단계~~ → 실기기 테스트에서 실패, 원인 규명 후 아래 "의도적으로
    제외" 항목으로 옮기고 테스트는 삭제함
- [x] **Phase 3 — 핵심 아키텍처 회귀 테스트** (실제 소설 사용, 가장 중요)
  - `next()` N번 → `previous()` N번 → 원래 페이지로 정확히 복귀 (방문 이력 스택)
  - 검색결과/목차/챕터점프 점프 시 목표 지점 텍스트가 실제로 보이는 페이지에 도착
- [x] **Phase 4 — 라이브러리/파일 기능**
  - 폴더 탐색 → 파일 목록 → 선택 → 리더 로드 (실제 소설로)
  - 정렬 옵션(이름/날짜/크기순 6종)
  - 인코딩 감지: (androidTest) `EncodingDetectionTest` — 실제 UTF-8 픽스처 파일(Context 필요).
    (일반 유닛) `EncodingDetectorTest` — EUC-KR/ASCII/UTF-8 합성 바이트 + 빈 입력, Android 의존성이
    없어 분리함
- [x] **Phase 5 — 챕터/목차/검색**
  - 챕터 자동 인식: (androidTest) `ChapterDetectionRegressionTest` — 정탐(`Heuk.txt`, `## ` 프리셋)
    + `Mujeong.txt`의 "챕터 없음" 정상 처리(오탐 없음), 실제 픽스처 기반이라 Context 필요.
    (일반 유닛) `ChapterDetectorEdgeCaseTest` — 합성 문자열로 `\r\n` 처리, 마지막 줄(줄바꿈 없음),
    60자 초과 줄 제외, 오프셋이 trim 전 원본 줄 시작을 가리키는지, 잘못된 커스텀 정규식이 조용히
    걸러지는지, 겹치는 패턴이 중복 집계되지 않는지 등 세부 분기 검증
  - 챕터 점프 N등분 내비게이션: (일반 유닛) `ChapterJumpNavigatorTest`(순수 로직: breakpoint 계산/
    다음/이전/왕복/빈 목록/등분 0 이하/길이 0인 마지막 챕터) + (androidTest)
    `ChapterJumpNavigationTest`(실제 소설로 `ReaderViewModel.next()/previous()`가 breakpoint를
    그대로 따라가는지 헤드리스로 검증)
  - 목차 시트 자동 스크롤 + 강조: (androidTest) `TocSheetAutoScrollTest` — 현재 챕터가 스크롤 없이
    바로 보이는지, 클릭 시 정확한 오프셋으로 점프하는지, 빈 목차 메시지
  - 검색 제출식 실행 + 마지막 결과 유지 + 최근접 결과 강조: (androidTest) `SearchSheetTest` — 타이핑만
    으로는 검색 안 됨, 버튼/키보드 검색 액션으로만 실행, 시트 재오픈 시 이전 검색어/결과 유지(재검색
    안 함), 최근접 결과 자동 스크롤, 시트를 열면 커서가 검색어 맨 끝에 있어(기존 검색어 없으면 맨
    앞과 같음) 곧바로 백스페이스로 지울 수 있는지(`SemanticsProperties.TextSelectionRange`로 커서
    위치 자체를 검증 + 실제 백스페이스 입력으로 마지막 글자가 지워지는지 이중 확인)
- [x] **Phase 6 — 설정**: (androidTest) `QuickSettingsSheetTest` — 폰트 크기/여백/테마/페이지전환
  애니메이션을 시트에서 바꾸면 화면(uiState)에 반영되고 실제로 DataStore에도 저장되는지 확인.
  `ReaderViewModel`이 프로덕션 DataStore를 그대로 쓰므로 실행 전 원래 값을 기억해뒀다가 끝나면 복원.
- [x] **Phase 7 — 그 외 순수 로직** (다른 오픈소스 리더 앱들 조사 후 추가한 커버리지 공백 메우기):
  - (일반 유닛) `TextReflowerTest` — PRESERVE(줄=문단 1:1, `\r\n` 처리, 빈 줄은 빈 문단) /
    REFLOW(단일 개행=공백 이어붙임, 빈 줄=문단 경계, 연속 빈 줄 collapse, 선두/말미 빈 줄 무시) 둘 다
    합성 문자열로 정확한 오프셋까지 검증. 페이지네이션이 여기 출력을 그대로 쓰는데 지금까지 테스트가
    하나도 없었음.
  - (일반 유닛) `CollectionExtTest` — `binarySearchFloor`(스크롤 모드에서 현재 오프셋의 문단 인덱스
    찾기): 빈 리스트, 범위 밖 값, 정확히 일치, 중복값 등 경계 케이스
- [x] **Phase 9 — 폰트 다운로드/적용** ("의도적으로 제외"였다가 방법을 찾아서 승격):
  - (androidTest) `FontResolverTest` — `FontResolver`는 로컬에 폰트 파일이 있는지만 보고 커스텀
    `FontFamily`/`FontFamily.Default`를 가르므로, 실제 다운로드 없이 파일 유무만 흉내내면 "폰트를
    선택하면 실제로 다른 폰트가 적용되는지"를 정확히 검증할 수 있다(더미 바이트로 충분, 내용 검사는
    안 하므로). 시스템 기본/알 수 없는 id/미다운로드/다운로드됨 4가지 케이스.
  - (androidTest) `FontDownloadManagerTest` — `MockWebServer`(로컬 가짜 HTTP 서버)로 실제 GitHub
    없이 다운로드 성공(파일 저장 + `Downloaded` 상태 + 진행률 emit) / 실패(404 → `Failed` 상태,
    최종 파일 안 남음) 경로 검증. `androidTestImplementation(libs.mockwebserver)` 추가, `http://`
    요청이 필요해 `app/src/debug/res/xml/network_security_config.xml`로 `localhost`/`127.0.0.1`만
    cleartext 예외 허용(debug 소스셋 전용, 릴리스 빌드엔 안 들어감).
  - (androidTest, **실 네트워크**) `RealFontDownloadIntegrationTest` — 위 두 테스트는 가짜(더미
    파일/MockWebServer)로 계약만 검증하는데, "진짜 배포처에서 실제로 다운로드되고 적용되는지"는 실제
    인터넷으로 한 번은 확인해야 의미가 있어서 별도로 뒀다. `FontCatalog.entries` 5개 전부(나눔고딕/
    나눔명조/본고딕/리디바탕/Pretendard) 하나씩 실제로 다운로드해 `Downloaded` 상태 + 파일 크기
    (100KB 초과) + 포맷에 맞는 매직 넘버(`.ttf`는 `0x00010000`, `.otf`는 `OTTO`)로 진짜 폰트 파일인지
    + `FontResolver`가 커스텀 폰트로 적용하는지까지 확인. `FontCatalog`의 "다운로드 URL은 배포처가
    바뀌면 깨질 수 있다"는 문서화된 우려를 실제로 검증하는 역할이고, 실제로 이 테스트로 세 개(나눔고딕/
    나눔명조/리디바탕)가 깨져있는 걸 잡아서 URL을 교체했다(각각 `naver/nanumfont`의 `fonts/` 폴더
    자체가 없어짐 → Google Fonts 저장소로, `ridi/RIDIBatang` 저장소 자체가 없어짐 →
    `fonts-archive/RIDIBatang` 미러로). 정기 Phase 회귀 스위트라기보단 수동 확인용에 가깝다
    (오프라인이거나 배포처 URL이 실제로 깨지면 실패하는 게 의도된 동작).
  - (androidTest, **실 네트워크**) `FontApplyRepaginatesViewerTest` — 위 테스트들은 다운로드/
    `FontResolver` 계약까지만 확인하고 실제 뷰어(`ReaderPagerContent`)까지는 안 건드리는데, "폰트를
    다운로드해서 적용하면 뷰어에 실제로 다르게 보이는지"는 별개로 확인해야 한다. 실제로 나눔명조를
    다운로드해 적용한 뒤, 뷰어가 정확히 하는 일(`FontResolver.resolve`로 새 `FontFamily`를 구해
    `PaginationParams`에 담아 `onViewportMeasured` 호출)을 그대로 재현해, 같은 시작 지점이라도
    폰트 적용 전후로 페이지 경계(끝 오프셋)가 실제로 달라지는지 확인한다 — 화면을 렌더링해 픽셀을
    비교하는 스크린샷 테스트 없이도 "뷰어가 진짜 다시 계산해서 다르게 그렸다"를 안정적으로 증명하는
    방법.
  - (androidTest) `FontPickerSheetTest` — `FontPickerSheet`의 실제 클릭 흐름: 다운로드 안 된 폰트는
    선택 불가, 이미 다운로드된 폰트(더미 파일로 흉내)를 탭하면 `viewModel.selectFont`가 실제로 호출돼
    설정에 반영되는지.
- [x] **Phase 8 — 나머지 순수 로직 후보**:
  - (일반 유닛) `FontCatalogTest` — id/localFileName 중복 방지, `SYSTEM_DEFAULT_ID`가 카탈로그
    항목과 안 겹치는지, `findById` 정상/미존재 케이스
  - (일반 유닛) `ChapterPatternCatalogTest` — 각 프리셋의 `example`이 실제로 자기 `pattern`에
    매칭되는지(프리셋을 나중에 고칠 때 예시와 정규식이 서로 어긋나는 실수 방지), id 중복 방지,
    `buildRegexList`의 활성화/비활성화·빌트인+커스텀 순서 조합
  - (일반 유닛) `AutoPageTurnControllerTest` — 타이머 자동넘김(순수 코루틴, Android 의존성 없음)의
    tick 타이밍을 `kotlinx-coroutines-test`의 가상 시간(`runTest`/`advanceTimeBy`)으로 결정적으로
    검증: 간격마다 정확히 한 번 tick, `stop()` 이후 더 이상 안 옴, `start()` 재호출 시 이전 타이머
    취소하고 새 간격 적용. 새 `testImplementation(libs.kotlinx.coroutines.test)`(1.8.1, 프로젝트가
    실제로 물고 있는 kotlinx-coroutines-core 버전과 맞춤) 추가.
- [x] **Phase 10 — 기기 간 동기화** (`data/sync/*`, VSCode 위치 동기화 + PC 파일 동기화 — 추가 당시
  테스트가 0건이었던 패키지):
  - (일반 유닛) `RelativePathNormalizeTest` — `normalizeRelativePath`: 구분자 통일(`\` → `/`),
    소문자화, NFC 정규화(완성형 vs 자모 분해형 한글이 같은 키로 합쳐지는지 `\u` 이스케이프로 명시한
    두 문자열로 검증).
  - (일반 유닛) `PcTlsTrustTest` — `sha256Fingerprint`: `openssl x509 -fingerprint -sha256`와 같은
    형식(콜론 구분 대문자 헥사 32쌍)인지, 같은 바이트는 항상 같은 지문을 내는지, 다른 바이트는 다른
    지문을 내는지. TLS 핸드셰이크 자체(lenient/pinned `SSLContext`)는 순수 로직이 아니라 androidTest
    쪽 `PcSyncClientTest`에서 실제 소켓으로 검증한다.
  - (일반 유닛) `PcSyncDeltaTest`의 로직은 `PcSyncFileManager.kt`에서 `computeSyncDelta`(순수 함수,
    I/O 없음)로 뽑아냈지만, 테스트 자체는 `LocalLibraryFile.documentUri`가 `android.net.Uri` 타입이라
    (`Uri.parse`가 app/src/test의 android.jar 스텁에서는 예외를 던짐) androidTest 쪽에 있다 — 아래
    참고.
  - (androidTest) `RelativePathSafTest` — `relativePathFromSafDocumentUri`: 최상위/중첩 파일 경로
    역산, 트리 루트 자체를 가리키는 문서는 null, 무관한 트리의 문서는 null. **회귀 테스트**: 트리
    `primary:Books`와 접두사가 겹치는 형제 트리 `primary:BooksExtra`의 문서가 잘못 매칭되던 버그(단순
    `startsWith`, 구분자 없는 접두사 비교)를 테스트 작성 중 발견해 `"$treeDocumentId/"`로 구분자까지
    포함해 비교하도록 [RelativePath.kt](../app/src/main/java/com/moonkata/textreader/data/sync/RelativePath.kt)를
    같이 고쳤다.
  - (androidTest) `PcSyncDeltaTest` — `computeSyncDelta`: 원격에만 있으면 다운로드, 로컬에만 있으면
    삭제, 크기가 다르면 갱신. **회귀 테스트**: 크기가 같으면 로컬/원격 수정시각이 완전히 달라도
    `toWrite`에 들어가면 안 된다 — 2026-09-02 실기기 검증에서 잡힌 "재동기화 때마다 안 바뀐 파일까지
    매번 다시 받던" 버그의 재발 방지.
  - (androidTest) `ReadingPositionSyncClientTest` — `ReadingPositionSyncClient`가 Supabase
    PostgREST에 보내는 요청 계약을 로컬 `MockWebServer`(평문 HTTP)로 검증: `fetch`의 쿼리 파싱(빈
    배열/서버 에러 → null, 정상 응답 파싱, `encoding: null` 처리), `apikey`/`x-moonkata-secret` 헤더,
    `upsert`의 JSON 바디 모양과 `Prefer: resolution=merge-duplicates` 헤더, `testConnection`의
    2xx/4xx 판정.
  - (androidTest) `PcSyncClientTest` — `PcSyncClient`가 PC 트레이 서버와 주고받는 요청 계약을 로컬
    `MockWebServer`(진짜 TLS 핸드셰이크가 되는 HTTPS, `okhttp-tls`의 `HeldCertificate`로 즉석에서
    자체 서명 인증서 생성)로 검증 — TOFU 지문 고정의 실제 동작을 흉내만 내지 않고 실제 소켓으로
    확인하는 유일한 테스트: lenient 모드로 연결하면 실제 인증서 지문이 `lastSeenFingerprint`에
    기록되는지, 그 지문으로 pinned 모드 연결하면 성공하는지, **틀린 지문으로 pinned 모드 연결하면
    실패하는지**(TOFU의 핵심 방어선), `/list` 응답 파싱, `/file` 다운로드 바이트가 정확히 스트리밍
    되는지, 경로 URL 인코딩, `/ping` 응답으로 `isPcSyncServer` 판정. `PC_SYNC_PORT`(58221)가
    `PcSyncClient`에 하드코딩돼 있어 MockWebServer도 임의 포트가 아니라 그 고정 포트로 직접 띄운다.
  - (Go, `external_library/sync_server`) `filelist_test.go` — `go test ./...`로 별도 실행(안드로이드
    Gradle 빌드에 안 묶임). `resolveFilePath`의 경로 탈출 방지(`../`, 절대경로, 루트 자체)는 추가 당시
    테스트가 하나도 없던 보안 관련 로직이라 우선 커버; `listFilesRecursively`의 dotfile/dot폴더
    스킵(Syncthing 마커 파일 회귀), 확장자 필터, 빈 폴더에서 `nil`이 아니라 빈 슬라이스를 돌려주는지
    (JSON으로 `null`이 아니라 `[]`로 인코딩되어야 안드로이드 쪽 파서가 그대로 통과함).
- [x] **Phase 11 — 자기 자신을 겨냥한 테스트가 없던 핵심 파일들** (2026-09-03, 다른 테스트를 통해서만
  간접적으로 exercise되던 파일들을 직접 겨냥):
  - (androidTest) `PaginatorTest` — `data/parser/Paginator.kt`는 이 앱에서 가장 복잡한 핵심 로직인데
    독립 테스트가 없었다. 빈 문단 목록, 뷰포트 너비/높이 0, 개행이 하나도 없는 극단적으로 긴 문단
    하나가 여러 페이지로 쪼개지는 경우(문단 인덱스를 안 넘기고 같은 문단을 계속 쪼개는 분기), 뷰포트가
    한 줄보다도 작아 `fitLines == 0`이 강제로 1로 폴백되는 경우(무한루프 방지 분기)에도 매 페이지
    실제로 진전이 있는지, 페이지 구분용 빈 줄이 다음 페이지 맨 위로 안 넘어가는지, `maxPages`가 정확히
    지켜지는지, `onePageEndingAt`의 역산 결과가 정방향 계산과 실제로 일치하는지(및 `endOffset<=0`/빈
    문단 목록의 null 폴백)까지 커버. 실제 `TextMeasurer`가 필요해(`TestTextMeasurer`) androidTest.
  - (androidTest) `PcSyncSheetTest` — PC 동기화 UI(버튼을 누르는 흐름) 자체는 테스트가 없었다(밑단인
    `PcSyncClient`/`computeSyncDelta`만 검증돼 있었음). 미검증 상태에서 동기화 버튼 비활성화, 검증된
    host/secret과 입력값이 일치하면 "연결됨" + 버튼 활성화, 시크릿을 검증 후 다시 고치면 즉시 검증
    풀림, 실제 네트워크(TEST-NET-1 블랙홀 주소 192.0.2.1로 접속 시도, connectTimeout 5초)로 연결
    테스트 실패 시 실패 문구 노출, "닫기"로 나가면 미저장 draft가 실제로 커밋되는지. 테스트를 짜다가
    **시트 자체의 실제 버그**를 발견해 같이 고쳤다: `PcSyncSheet`의 `Column`에 `QuickSettingsSheet`와
    달리 `verticalScroll`이 빠져있어서, 내용이 길어지면(PC 찾기 결과 목록 + 동기화 진행률/결과가 한꺼번에
    보이는 경우 등) 아래쪽 "닫기" 버튼이 화면 밖으로 밀려나 눌리지 않았다.
  - (androidTest) `TtsControllerTest` — `tts/TtsController.kt`는 테스트가 전혀 없었다. 실제 음성 합성
    완료 타이밍은 TESTING.md에도 이미 명시했듯 기기/설치된 음성 데이터에 좌우돼 자동화 신뢰도가
    낮으므로, 그 경계를 지키면서 검증 가능한 만큼만 다룬다: 생성 직후 `isReady`가 아직 false인지,
    엔진이 준비되기 전에 `speak`/`stop`/`setRate`/`setPitch`를 불러도 죽지 않는지, `shutdown` 이후
    호출도 안전한지. 실제 발화 완료 콜백(`onUtteranceDone`)까지 가는 왕복 테스트도 하나 두되, 이
    기기에 TTS 엔진이 없거나 실제로 발화가 안 끝나는 환경이면 `Assume`으로 건너뛴다(실패로 보고하지
    않음) — 실행되는 환경에서는 진짜로 엔진→콜백까지 검증하되, 안 되는 환경에서 억지로 통과/실패
    시키지 않는다.
  - (androidTest) `BookDaoTest` — 인메모리 Room으로 `data/db/BookDao.kt` 자체를 직접 겨냥: insert/
    조회/각 update 메서드가 자신이 선언한 필드만 정확히 바꾸는지, `documentUri` UNIQUE 인덱스 충돌 시
    `OnConflictStrategy.IGNORE`가 실제로 기존 행을 보존하고 `-1`을 돌려주는지, `getAllOrderByRecent`가
    `lastOpenedAt DESC, addedAt DESC`로 정확히 정렬되고(한 번도 안 연 책이 SQL의 NULL 정렬 규칙으로
    맨 뒤로 가는지까지) Flow가 데이터 변경 시 실제로 재emit하는지.
  - (androidTest) `BookRepositoryTest` — `data/repository/BookRepository.kt`도 직접 겨냥이 없었다.
    `findOrCreateBook`의 세 분기(신규 삽입/기존 재사용/`relativePath`가 다르면 그 자리에서 갱신)와,
    실제 파일 I/O가 걸리는 `openBookContent`/`bookFileExists`를 진짜 임시 파일로 검증 — 특히
    `bookFileExists`가 파일을 디스크에서 실제로 지운 뒤 정말 `false`로 바뀌는지까지 확인한다("이어서
    읽기" 후보에서 삭제된 파일을 걸러내는 바로 그 경로).
  - (androidTest) `ReaderSettingsRepositoryTest` — `data/datastore/ReaderSettingsRepository.kt`도
    다른 시트 테스트를 통해서만 간접 검증되고 있었다. 대표 타입(String/Float/Boolean/Int/enum/Set)의
    왕복 저장, `lastUsedSafTreeUri`에 `null`을 넘기면 문자열 "null"이 아니라 키 자체가 지워지는지,
    그리고 단순 왕복이 아니라 실제 조건부 로직이 있는 두 메서드 — `updateSupabaseSharedSecret`(
    `verifiedSecret`을 안 넘기면 검증된 값이 안 바뀌는지), `updatePcSyncConnection`(`verified=false`면
    검증된 host/secret/지문을 안 건드리는지, `fingerprint=null`이면 이전 지문을 안 지우는지)까지.
  - (androidTest) `QuickSettingsSheetModeTogglesTest` — `QuickSettingsSheetTest`가 폰트/여백/테마/
    전환 애니메이션만 다루고, 시트에 실제로 있는 나머지 토글(읽기 모드 전환, 줄바꿈 정리 모드, 화면
    꺼짐 방지, 화면 방향 고정)은 테스트가 없었다(USER_SCENARIOS.md §11에만 문서화돼 있었음). 이 넷을
    바꾸면 실제로 uiState와 DataStore에 반영되는지 확인한다. 짜는 과정에서 `SwitchRow`(화면 꺼짐
    방지 등 4개 스위치가 공유하는 내부 컴포저블)를 라벨 텍스트만으로 특정할 방법이 없다는 걸 발견해
    (Row가 시맨틱 경계를 안 만들어 라벨과 스위치가 서로 다른 형제 스위치들과 전부 평탄화된 채 섞임)
    `Row` 전체를 `Modifier.toggleable(role = Role.Switch)`로 감싸 라벨+스위치를 하나로 병합하도록
    고쳤다 — 터치 영역이 스위치 썸(thumb)만큼 작던 것도 라벨 전체로 넓어져 접근성도 같이 개선됨.
- [x] **Phase 12 — USER_SCENARIOS.md 16개 시나리오 전체를 다시 훑어서 남아있던 공백 메우기**
  (2026-09-03):
  - (androidTest) `SafFolderBrowserTest` — `data/file/SafFolderBrowser.kt`의 `listZipEntries`(zip
    안 `.txt` 나열)는 테스트가 하나도 없었다. `listFolder`는 진짜 SAF 트리 URI가 있어야 해서
    자동화가 어렵지만(그래서 계속 `FakeFolderBrowser`로 대체), `listZipEntries`는
    `contentResolver.openInputStream`만 쓰므로 `file://` URI로도 실제 로직을 그대로 검증할 수
    있다: `.txt`만 나열, 디렉터리 접두사가 파일명에서 잘리는지(원본 엔트리 이름은 `entryName`에
    그대로 남는지), 디렉터리 엔트리 자체는 건너뛰는지, 빈 zip/손상된 zip/없는 파일이 예외 없이 빈
    목록을 돌려주는지, 크기가 정확한지. 크기 검증 과정에서 실제 자바 zip API의 함정을 하나 배웠다 —
    `ZipOutputStream`에 `ZipEntry`를 그냥 스트리밍으로 쓰면(기본 DEFLATED, 크기를 미리 안 정해줌)
    data descriptor 방식이 돼서, `ZipInputStream`으로 앞에서부터 읽을 때(`listZipEntries`가 하는
    방식) 아직 그 엔트리 바이트를 다 읽기 전엔 `ZipEntry.size`가 -1(모름)일 수 있다 — 테스트용 zip은
    `STORED` + 크기/CRC 사전 계산으로 만들어 이 문제를 피했다(실제 zip 도구들은 스트리밍이 아니라
    파일 크기를 미리 알고 쓰기 때문에 이 문제가 거의 없음).
  - (androidTest) `LibraryZipAndBreadcrumbNavigationTest` — USER_SCENARIOS.md §1의 7·8번(zip 진입,
    브레드크럼 복귀)은 지금까지 자동화가 없었다. 폴더 목록은 `FakeFolderBrowser`로 흉내내지만, zip
    안 파일을 실제로 여는 부분은 진짜 zip 파일로 검증한다(`BookContentReader`가 폴더 탐색기를 거치지
    않고 URI로 직접 읽으므로 진짜 zip이면 실제 데이터 흐름 그대로 확인 가능). `FakeFolderBrowser`에
    zip 목록도 흉내낼 수 있는 생성자 파라미터를 추가(기존 호출부는 기본값으로 그대로 동작).
  - (androidTest) `ChapterPatternSheetTest` — `ChapterPatternSheet`(§8)는 순수 로직
    (`ChapterPatternCatalog`/`ChapterDetector`)만 검증돼 있었지 시트 자체는 테스트가 없었다. 내장
    프리셋을 끄면 실제로 챕터 재인식까지 일어나는지(유일한 프리셋을 끄면 챕터가 진짜로 0개가 되는지
    까지 확인해 "설정만 바뀌고 재인식은 안 일어남" 같은 배선 누락을 잡을 수 있게 함), 유효한 커스텀
    정규식 추가 시 입력창이 실제로 비워지는지, 잘못된 정규식은 에러 문구만 뜨고 저장 안 되는지, 커스텀
    패턴 삭제가 실제로 반영되는지.
  - (androidTest) `ReaderViewModelWiringTest` — 두 가지 배선이 지금까지 검증된 적 없었다: (1)
    세로 스크롤 모드(§5)에서 `next()`/`previous()`가 `Paginator` 계산이 아니라 `navEvents`로
    `RequestNextPage`/`RequestPreviousPage`(챕터점프 모드가 같이 켜져 있으면 `JumpToOffset`)를
    방출하는지 — 실제 스크롤 자체는 Compose 쪽(`ReaderScrollContent`) 담당이라 이 레벨에서는 "올바른
    이벤트가 나가는지"까지만 검증 가능. (2) `flushPendingPosition()`(§14, 화면 이탈 시 즉시 저장
    경로)이 500ms 디바운스를 기다리지 않고 즉시 Room에 반영되는지. `navEvents`(replay 없는
    SharedFlow) 구독을 검증하다가 흔히 겪는 함정 하나를 다시 확인함 — 같은 단일 스레드
    `runBlocking` 이벤트 루프 위에서 `launch`로 띄운 구독 코루틴과 `Thread.sleep` 기반 폴링
    (`waitUntilTrue`)을 같이 쓰면, 그 sleep이 구독 코루틴이 실행될 차례 자체를 막아버려 이벤트를
    영원히 못 받는다 — `CoroutineStart.UNDISPATCHED`로 시작한 `async { flow.first() }`로 바꿔
    `next()`/`previous()` 호출 전에 구독이 확실히 걸려 있음을 보장하는 방식으로 해결.
  - (androidTest) `ReaderTapZoneAndSwipeNavigationTest` — `ReaderChromeAutoHideTest`는 "화면 가운데
    탭은 상하단바만 닫고 페이지는 안 넘어간다"만 확인했지, 실제 탭 존(좌/우 절반)이나 스와이프가
    `TouchTurnMode`/`SwipeTurnMode` 설정에 따라 진짜로 다음/이전 중 어느 쪽으로 넘기는지는 검증된 적이
    없었다(§4). 오른쪽 탭/왼쪽 스와이프는 항상 다음, 왼쪽 탭/오른쪽 스와이프는 `STANDARD`면 이전,
    `BOTH_NEXT`면 마찬가지로 다음으로 가는 네 조합을 실제 `performTouchInput`(탭 좌표, `swipeLeft`/
    `swipeRight`)으로 검증.

  이 라운드에서 검토했지만 의도적으로 손대지 않은 것 두 가지(VSCode 동기화 오케스트레이션, TTS
  청크/콜백 배선)는 아래 "의도적으로 제외" 목록에 사유와 함께 정리해뒀다.

## 의도적으로 제외

- **바텀시트 뒤로가기 2단계 처리(키보드 먼저 닫고 두 번째에 시트 닫기)** — 실기기 테스트에서 확인한
  결과, `ModalBottomSheet`는 `shouldDismissOnBackPress = false`로 두면 뒤로가기 키 이벤트를 별도
  `Window`(`ModalBottomSheetWindow`) 레벨에서 그대로 삼켜버려 시트 안의 `BackHandler`까지 아예
  전달되지 않는다 — 우리 쪽 버그가 아니라 이 Compose Material3 버전의 알려진 제약. `true`로 되돌리면
  한 번에 바로 닫히긴 하지만(검색 결과 목록이 통째로 사라짐, 원치 않음), 지금처럼 `false`로 두면
  뒤로가기가 그냥 아무 동작도 안 해서(그래서 검색 결과는 안전) 결과적으로 이 케이스는 테스트할 만한
  "동작"이 없다. 프로덕션 코드는 그대로 두고 관련 테스트만 삭제.
- IME(소프트 키보드) 표시 여부 — Compose 시맨틱 트리로 신뢰성 있게 검증 불가 (수동 확인으로 대체)
- TTS 실제 음성 재생, 타이머 자동넘김의 실제 타이밍
- 볼륨키 물리 입력, 화면 밝기/방향 고정 — 윈도우 플래그라 검증 신뢰성 낮음
- 실제 시스템 SAF 폴더 선택창 자동화(UiAutomator) — 기기/OS 버전마다 깨지기 쉬워 보류
- **`PcSyncFileManager.sync()`의 실제 SAF 파일 쓰기/삭제** — 델타 계산 자체는 `PcSyncDeltaTest`로
  커버하지만, 실제 `DocumentFile.createFile`/`openOutputStream`/`delete`까지 자동화하려면 진짜 SAF
  트리 권한(사용자가 실제로 폴더를 선택해야 발급됨)이 필요해 안정적인 테스트 더블을 만들기 어렵다.
  `PC_SYNC_SERVER_PLAN.md`의 "실기기 종단 검증" 기록으로 대체.
- **`PcHostScanner`의 실제 서브넷 스캔** — `ConnectivityManager`가 돌려주는 로컬 IP와 실제 LAN 환경에
  의존해 에뮬레이터/CI에서 의미 있게 재현하기 어렵다. `PcSyncClient.isPcSyncServer`(스캔이 후보마다
  호출하는 핵심 판정 로직)는 `PcSyncClientTest`로 커버.
- **Go PC 서버(`external_library/sync_server`)의 HTTP 핸들러/트레이 UI/자동 실행 전체 흐름** —
  순수 로직(`resolveFilePath`, `listFilesRecursively`)만 `go test`로 커버하고, `server.go`의 실제
  라우팅이나 `tray.go`/`autostart_windows.go`의 OS 연동은 `PC_SYNC_SERVER_PLAN.md`에 기록된 실기기
  검증(포트 바인딩, `/ping`·`/list`·`/file` 실제 왕복, Windows 시작 프로그램 레지스트리 키 실행까지
  확인)으로 대체 — Windows 트레이 UI는 Claude가 스크린샷으로 확인할 수 없어 자동화 테스트의 신뢰도
  자체가 낮다.
- **VSCode 원격 동기화의 `ReaderViewModel` 레벨 오케스트레이션**(`checkRemoteAndMaybeNotify`가
  "더 멀리 읽었습니다" 팝업을 실제로 띄우는지, `scheduleRemoteSyncCheckpoint`의 1분 유휴 타이머) —
  `ReaderViewModel`이 `SupabaseConfig.URL`(고정값)로 `ReadingPositionSyncClient`를 직접 만들어서
  `MockWebServer`로 가로챌 주입 지점이 없다. 이 앱의 핵심 상태 머신 파일에 이 테스트 하나만을 위한
  주입 지점을 새로 뚫는 건 위험 대비 효익이 낮다고 판단해 보류 — 밑단 프로토콜(
  `ReadingPositionSyncClient`)은 `ReadingPositionSyncClientTest`로 이미 검증됨.
- **TTS의 500자 청크 분할·utterance 완료 후 `jumpToOffset` 배선**(`ReaderViewModel` 내부) — 위와
  같은 이유로 `TtsController`가 `ReaderViewModel` 안에서 직접 만들어져 주입 지점이 없고, 그 위에 실제
  TTS 엔진 타이밍이라는 근본적인 불안정성까지 겹친다. `TtsController` 자체의 안전성(준비 전/후 호출,
  shutdown 이후 호출)은 `TtsControllerTest`로 검증돼 있음.
