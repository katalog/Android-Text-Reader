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
