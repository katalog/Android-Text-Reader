# 기능별 구현 — 뭘 고치려면 어디로 가나

여기 뭐가 있는지 훑어보는 문서가 아니라, **뭔가 고치려고 열게 되는 문서**입니다. 그래서 기능마다
"무엇을 하나 → 파일 → 구현 요약" 순으로 두되, 함정이 있는 곳은 ⚠️로 눈에 띄게 표시했습니다. 처음
이 저장소를 본다면 [ONBOARDING.md](ONBOARDING.md) → [DESIGN_RATIONALE.md](DESIGN_RATIONALE.md)를
먼저 읽는 게 낫습니다 — 왜 이렇게 짰는지 알고 오면 여기 표들이 훨씬 빨리 읽힙니다.

로컬 `.txt`(및 zip 안의 `.txt`) 소설을 오프라인으로 읽는 앱입니다. 화면은 **서재**와 **리더** 두 개뿐이고,
DI 프레임워크 없이 수동 MVVM(`AndroidViewModel` + Repository)으로 짭니다.

```
library  →  reader/{bookId}
```

| 역할 | 파일 |
|---|---|
| 진입점, 볼륨키 위임 | `MainActivity.kt` |
| 화면 전환 | `navigation/AppNavigation.kt` |
| 매니페스트(인터넷은 폰트 다운로드에만 씀) | `app/src/main/AndroidManifest.xml` |

설정은 DataStore(`ReaderSettings`/`ReaderSettingsRepository`), 책·읽기 위치는 Room(`BookEntity`/
`BookDao`/`AppDatabase`)에 저장합니다.

앱 자체는 완전 오프라인으로 동작하지만, 기기 간 동기화 두 가지(둘 다 기본 꺼짐, 사용자가 시크릿을 직접
입력하거나 QR을 스캔해야 켜짐)가 `data/sync/`에 있습니다 — VSCode와 읽기 위치를 공유하는 §15, PC와
책 파일 자체를 동기화하는 §16. 둘 다 개인 Supabase 프로젝트(§15) 또는 사용자 PC(§16)를 매개로 하되,
Supabase 쪽은 공유 시크릿의 SHA-256 해시를 `user_key`로 써서 설치본마다 자기 행만 보이게 서버 RLS로
분리해뒀습니다(요금 폭주 리스크였던 "모든 설치본이 한 파티션 공유" 문제의 해결책) — 자세한 배경은
각각 [`VSCODE_SYNC_PLAN.md`](VSCODE_SYNC_PLAN.md), [`PC_SYNC_SERVER_PLAN.md`](PC_SYNC_SERVER_PLAN.md),
멀티유저 전환 과정 전체는 [`SYNC_MULTIUSER_PLAN.md`](SYNC_MULTIUSER_PLAN.md).

> **여기서 자주 헷갈리는 것 4가지**
> 1. **저장되는 위치는 페이지 번호가 아니라 글자 번호입니다** — §4. 페이지 번호 필드를 아무리 찾아도
>    없습니다, 원래 없습니다.
> 2. **목차는 DB에 없습니다** — §8. 책을 열 때마다 매번 다시 스캔합니다. "왜 저장 안 하지?" 싶으면
>    거기 이유가 적혀 있습니다.
> 3. **`ReaderViewModel.kt`가 700줄에 가깝습니다.** 다 읽지 마세요 — 아래 표에서 고치려는 항목을 찾아
>    관련 함수만 보면 됩니다.
> 4. **`onePageEndingAt`은 정확한 답이 아니라 추정입니다.** 검색 점프 직후처럼 방문 이력이 없을
>    때만 쓰이고, 다음 페이지로 한 번만 넘겨도 이력이 정확한 값으로 바로 채워집니다 — §5.

---

## 1. 서재 — SAF 폴더 탐색기

**무엇을 하나**
시스템 폴더 선택기(SAF)로 루트 폴더를 고르면 그 안을 **한 단계씩** 탭해서 들어갑니다. 전체 트리를
재귀 스캔하지 않습니다. `.txt`와 `.zip`만 보이고, zip은 폴더처럼 들어가 그 안의 `.txt`를 엽니다.
이름/날짜/크기 정렬과 파일별 읽기 진행률 표시가 있습니다. 상단 바 오른쪽엔 PC 동기화(§16)·정렬·설정
아이콘 3개가 있어, 책을 열지 않고도 서재 화면에서 바로 접근할 수 있습니다.

**파일**

| 역할 | 파일 |
|---|---|
| UI | `ui/library/LibraryScreen.kt` |
| 상태·탐색 로직 | `ui/library/LibraryViewModel.kt` |
| SAF 폴더 선택 런처 | `ui/library/FolderPickerLauncher.kt` |
| 폴더/zip 한 단계 나열 | `data/file/SafFolderBrowser.kt` (`FolderBrowser` 인터페이스) |
| 항목 모델·정렬 enum | `model/FolderEntry.kt` |
| 영구 읽기 권한 | `util/SafUriExt.kt` (`takePersistableReadPermission`) |
| 마지막 폴더 URI·정렬 저장 | `data/datastore/ReaderSettingsRepository.kt` |
| 책 레코드 생성 | `data/repository/BookRepository.kt` (`findOrCreateBook`) |
| 상단 바에서 퀵설정/폰트/챕터패턴 시트를 열기 위한 공통 인터페이스 | `ui/SettingsController.kt` |

**구현 요약**

1. FAB → `OpenDocumentTree`로 트리 URI를 받습니다.
2. `takePersistableUriPermission`으로 재실행 후에도 읽을 수 있게 만들고, URI를 DataStore
   `lastUsedSafTreeUri`에 넣습니다. 다음 실행 때 그 폴더를 바로 엽니다.
3. `BrowseLocation` 스택(실제 폴더 또는 zip)으로 경로를 유지합니다. 뒤로가기/브레드크럼은 스택을
   잘라 `listFolder`/`listZipEntries`만 다시 호출합니다.
4. `.txt`를 탭하면 `BookSource`를 문자열로 직렬화해 Room에 upsert하고, `openBookEvents`로
   `reader/{id}`로 이동합니다.
5. 목록 진행률은 `observeLibrary()`의 `lastReadProgressPercent`를 `documentUri` 키로 매핑해
   행에 붙입니다.

> **⚠️ 여기서 테스트를 만들 땐**: `SafFolderBrowser.listFolder`는 진짜 SAF 트리 URI가 있어야
> 동작해서 자동화하기 어렵습니다 — `FakeFolderBrowser`로 목록을 흉내내는 게 이 저장소의 표준
> 패턴입니다(`LibraryFolderBrowseScenarioTest` 참고). 반면 `listZipEntries`는 `openInputStream`만
> 쓰기 때문에 `file://` URI로도 진짜 로직을 그대로 테스트할 수 있습니다(`SafFolderBrowserTest`).

---

## 2. 이어서 읽기 다이얼로그

**무엇을 하나**
앱을 새로 켰을 때 최근에 연 책이 있으면 바로 이어서 볼지 묻습니다. 같은 프로세스 안에서는 한 번만
뜹니다.

**파일**

- `ui/library/LibraryViewModel.kt` — `resumeCandidate`
- `ui/library/LibraryScreen.kt` — `ResumeReadingDialog`

**구현 요약**
시작 시 `observeLibrary()`의 첫 항목(최근순)에 `lastOpenedAt`이 있으면 후보로 올립니다. 확인하면 그
`bookId`로 리더로 가고, 닫으면 후보만 비웁니다.

> **⚠️ 실제로 겪은 버그**: 후보로 올리기 전에 파일이 지금도 열리는지(`bookFileExists`) 반드시
> 확인해야 합니다. 안 하면, 파일이 삭제/이동된 책을 후보로 띄웠다가 "계속 보기"를 누르는 순간
> `FileNotFoundException`을 잡는 곳이 없어 앱이 죽습니다. `ResumeCandidateFileExistsTest`가 이 버그의
> 재발 방지 테스트입니다.

---

## 3. 책 파일 열기 · 인코딩 · zip

**무엇을 하나**
일반 txt URI 또는 `zip:URI!entryName` 형태를 읽어, 앞 256KB 샘플로 문자셋을 추정한 뒤 전체 바이트를
그 인코딩으로 디코딩합니다. UTF-8 / EUC-KR / CP949(MS949)를 다룹니다.

**파일**

| 역할 | 파일 |
|---|---|
| 위치 모델·직렬화 | `data/file/BookSource.kt` |
| 바이트 읽기 + 디코딩 | `data/file/BookContentReader.kt` |
| 인코딩 감지 | `data/file/EncodingDetector.kt` (`juniversalchardet`) |
| zip 안 txt 목록 | `data/file/SafFolderBrowser.kt` `listZipEntries` |
| 메타(총 글자 수, 인코딩명) 저장 | `BookRepository.markOpened` → `BookDao.updateMeta` |

**구현 요약**
`ZipInputStream`으로 엔트리 이름이 일치할 때까지 순회해 바이트를 꺼냅니다. 감지는 `UniversalDetector`
결과와 MS949/EUC-KR/UTF-8 후보를 `Charset.isSupported`로 걸러 UTF-8로 폴백합니다.

---

## 4. 읽기 위치 저장 (문자 오프셋)

**무엇을 하나**
페이지 번호가 아니라 **전체 텍스트 내 문자 오프셋**과 진행률(%)을 Room에 둡니다. 폰트·여백이 바뀌어
페이지가 다시 나뉘어도 같은 글자 위치로 복원됩니다.

**왜 페이지가 아니라 글자 번호인가:** 페이지 번호로 저장하면, 글자 크기 한 단계만 바꿔도 "37페이지"가
가리키는 실제 내용이 바뀌어버립니다. 이 앱 전체 설계에서 제일 중요한 결정이고, 자세한 사연은
[DESIGN_RATIONALE.md](DESIGN_RATIONALE.md) §1.

**파일**

| 역할 | 파일 |
|---|---|
| 스키마 | `data/db/BookEntity.kt` (`lastReadCharOffset`, `lastReadProgressPercent`) |
| DAO | `data/db/BookDao.kt` `updateReadPosition` |
| 디바운스·플러시 | `ui/reader/ReaderViewModel.kt` (`updateCurrentOffset`, `schedulePositionWrite`, `flushPendingPosition`) |
| ON_STOP 즉시 저장 | `ui/reader/ReaderScreen.kt` Lifecycle observer |
| ViewModel 종료 시 블로킹 저장 | `ReaderViewModel.onCleared` |

**구현 요약**
오프셋 변경 후 500ms 디바운스로 DB에 씁니다. 백그라운드 전환이나 화면 이탈 때는 타이머를 기다리지
않고 `flushPendingPosition`합니다.

> **⚠️ `onCleared`가 좀 특이합니다**: `viewModelScope`가 곧 취소되는 시점이라 평소처럼 `launch`를
> 쓰면 저장이 시작도 못 하고 사라집니다. 그래서 여기만 일부러 `runBlocking`으로 블로킹 저장합니다 —
> 다른 곳에서 이 패턴을 흉내내지 마세요, `onCleared`가 아니면 메인 스레드를 막는 안티패턴입니다.

---

## 5. 가로 페이지 모드 (스와이프 페이지)

**무엇을 하나**
화면에 들어갈 만큼만 잘라 **현재 페이지 하나**만 계산·표시합니다. 책 전체 페이지 리스트를 만들지
않습니다. 다음 페이지는 현재 끝 오프셋부터 한 장만 측정하고, 이전은 정방향 방문 이력을 팝하거나
(정확), 점프 직후처럼 이력이 없으면 역산합니다.

**이 앱에서 가장 복잡한 파일입니다.** 왜 이런 모양인지 궁금하면 코드보다
[DESIGN_RATIONALE.md](DESIGN_RATIONALE.md) §2·§3을 먼저 읽는 걸 권합니다 — 둘 다 실제로 겪은 문제
(느려짐, 마지막 줄 잘림)를 고친 흔적입니다.

**파일**

| 역할 | 파일 |
|---|---|
| `TextMeasurer`로 경계 계산 | `data/parser/Paginator.kt` |
| 현재 페이지·이력 | `ui/reader/ReaderViewModel.kt` (`pageHistory`, `advancePageForward`/`advancePageBackward`) |
| 렌더·전환 애니메이션 | `ui/reader/ReaderPagerContent.kt` (`AnimatedContent`) |
| 문단 분할 | `data/parser/TextReflower.kt` |
| 페이지 구간 모델 | `model/PageBreak.kt`, `model/Paragraph.kt` |

**구현 요약**

1. 뷰포트가 잡히면 `onViewportMeasured`가 폰트/크기/여백 키를 만들고, 바뀌면 이력을 비운 뒤
   `paginateFrom(..., maxPages = 1)`로 현재 페이지만 계산합니다.
2. 측정은 **페이지 전체 substring을 한 번에** 합니다.
   > **⚠️ 문단별로 재고 싶은 유혹이 들 수 있는데, 안 됩니다.** 실제 `Text` 렌더는 페이지 전체를 한
   > 덩어리로 그려서, 문단별 높이 합이 실제 렌더 높이와 정확히 일치하지 않습니다 — 예전에 이걸로
   > 마지막 줄이 화면 밖으로 잘려 안 보이는 버그가 났습니다.
3. 넘치면 레이아웃의 줄 단위 `getLineBottom`으로 들어가는 줄 수까지 자르고, 다음 페이지 시작은
   선행 개행을 건너뜁니다.
4. 전환은 `HorizontalPager`가 아니라 `AnimatedContent` + `PageTransitionAnimation`(없음/슬라이드/
   덮기)입니다 — 페이지 개수·인덱스를 미리 맞출 필요가 없어서 이쪽을 골랐습니다.

이전 페이지 역산: `Paginator.onePageEndingAt`이 목표 끝보다 앞 구간을 순방향으로 몇 장 계산해,
`endOffset` 바로 앞에서 끝나는 페이지를 고릅니다.

> **여기 고치기 전에 봐야 할 테스트:** `PaginatorTest`(경계 케이스: 빈 텍스트, 뷰포트 0, 무한루프
> 방지 폴백)와 `PageNavigationRoundTripTest`(실제 소설로 왕복 검증). 새 분기를 추가한다면 둘 다에
> 케이스를 남겨두세요.

---

## 6. 세로 스크롤 모드

**무엇을 하나**
문단 리스트를 `LazyColumn`으로 스크롤합니다. 이어읽기·검색·목차 점프는 오프셋에 해당하는 문단
인덱스로 스크롤합니다.

**파일**

- `ui/reader/ReaderScrollContent.kt`
- `ui/reader/ReaderViewModel.kt` — `ReaderNavEvent` (`JumpToOffset`, `RequestNextPage`/`RequestPreviousPage`)
- `util/CollectionExt.kt` — `binarySearchFloor`

**구현 요약**
`snapshotFlow`로 보이는 아이템의 시작 오프셋을 `currentOffset`에 반영합니다. 다음/이전은 뷰모델이
이벤트를 보내고, UI가 한 화면 분량만큼 스크롤합니다. 챕터 점프가 켜져 있으면 챕터 제목 줄에 배경
하이라이트를 넣습니다.

읽기 모드 전환: `PageTurnMode`(`HORIZONTAL_PAGE`/`VERTICAL_SCROLL`)를 DataStore에 두고
`ReaderScreen`이 두 콘텐츠 중 하나를 고릅니다. `currentOffset`은 모드 전환에 영향받지 않으니, 페이지
↔ 스크롤을 오가도 같은 글자 위치가 유지됩니다.

---

## 7. 줄바꿈 정리 (원문 유지 / 문단 재구성)

**무엇을 하나**
소설 txt의 강제 줄바꿈을 그대로 두거나, 빈 줄만 문단 경계로 보고 한 줄 개행은 공백으로 이어붙입니다.

**파일**

- `data/parser/TextReflower.kt`
- 설정: `LineBreakMode` in `data/datastore/ReaderSettings.kt`
- 재적용: `ReaderViewModel.reflowParagraphs`(설정 변경 시 `Dispatchers.Default`)

> **⚠️ 설정 저장과 재적용이 한 곳에 안 묶여 있습니다.** `setLineBreakMode`는 값만 저장하고 끝나고,
> 실제 재실행은 `init{}`의 `settingsFlow.collect`가 이전 값과 비교해서 감지합니다. 이 파일이
> 페이지네이션의 입력값이라(§5), 여기 로직이 안 도는 채로 넘어가면 화면이 새 설정을 안 따라갑니다.

---

## 8. 챕터(목차) 자동 인식

**무엇을 하나**
세션마다 줄 단위로 정규식을 돌려 챕터 목록을 만듭니다. **DB에 목차를 안 넣습니다.** 매칭 0건은
"목차 없음"으로 정상 처리합니다.

**왜 DB에 안 넣나:** 저장해두면 규칙(정규식)을 고칠 때마다 옛날 규칙으로 만든 목차가 남아 어긋나고,
마이그레이션까지 딸려옵니다. 매번 다시 스캔하면 그 비용이 아예 안 생깁니다 — [DESIGN_RATIONALE.md](DESIGN_RATIONALE.md) §4.

**파일**

| 역할 | 파일 |
|---|---|
| 줄 스캔 | `data/parser/ChapterDetector.kt` |
| 내장 프리셋 + 커스텀 정규식 합성 | `data/parser/ChapterPatternCatalog.kt` |
| 패턴 켜기/끄기·추가 UI | `ui/reader/ChapterPatternSheet.kt` |
| 목차 시트 | `ui/reader/TocSheet.kt` |
| 로드와 분리된 백그라운드 탐지 | `ReaderViewModel.loadBook`/`redetectChapters` |

**구현 요약**
줄 길이 ≤ 60이고 trim 후 패턴 중 하나와 `matches`되면 챕터입니다. 기본 프리셋은 `##`로 시작하는
줄입니다.

> **⚠️ 이 스캔이 첫 화면 표시를 막으면 안 됩니다.** `redetectChapters`는 항상 백그라운드로 돌립니다
> — 첫 페이지는 챕터 탐지가 끝나기 전에 이미 떠 있고, 끝나면 `chapters`만 나중에 채워집니다. 새로
> 여기 손댈 때 이 순서(먼저 표시, 나중에 채움)를 깨뜨리지 마세요.

목차 시트를 열면 `currentOffset` 이하인 마지막 챕터를 현재로 강조하고, 그 위치보다 약간 앞에서부터
스크롤합니다(`TocSheetAutoScrollTest`가 이걸 검증합니다).

---

## 9. 챕터 점프 모드

**무엇을 하나**
각 챕터 구간을 N등분한 지점을 순서대로 점프해 빠르게 훑습니다. 마지막 등분점은 다음 챕터 시작과
같아서, 계속 "다음"을 누르면 자연히 다음 장으로 갑니다.

**파일**

- `data/parser/ChapterJumpNavigator.kt`
- `ReaderViewModel.next`/`previous` (`lastChapterJumpOffset`)
- 토글 UI: `ui/reader/ReaderTopBar.kt`(상단바 2행), 설정: `QuickSettingsSheet.kt`

**구현 요약**
페이지가 정착하면 `currentOffset`이 페이지 **시작**(점프 목표보다 앞)으로 맞춰질 수 있습니다. 같은
브레이크포인트를 다시 잡지 않도록 마지막 **목표 오프셋**을 `lastChapterJumpOffset`에 따로 둡니다.
더 점프할 지점이 없으면 일반 페이지 넘김으로 폴백합니다.

> **🎯 첫 태스크로 좋은 지점입니다.** `ChapterJumpNavigator.kt`는 34줄짜리 안드로이드 의존성 없는
> 순수 함수라, `ChapterJumpNavigatorTest`(JVM, 기기 불필요)에 케이스 추가하면서 TDD로 건드려볼 수
> 있습니다. `IDEAS.md`에 실제 백로그 항목(챕터 패턴이 너무 가까우면 등분하지 말고 바로 다음 패턴으로)
> 도 여기 걸려 있습니다 — 자세한 접근법은 [ONBOARDING.md](ONBOARDING.md) §7.

---

## 10. 본문 검색

**무엇을 하나**
입력 중이 아니라 검색 버튼(또는 IME 검색)을 눌렀을 때 실행합니다. 대소문자 무시 `indexOf`, 최대
200건. 시트를 닫았다 열어도 마지막 쿼리·결과를 유지하고, 현재 위치와 가장 가까운 결과가 강조되며
목록이 그 근처로 스크롤됩니다.

**파일**

- `ui/reader/SearchSheet.kt`
- `ReaderViewModel.search` — `lastSearchQuery`, `lastSearchResults`
- `model/SearchResult.kt`

**구현 요약**
스니펫은 매칭 전후 20자이며, 개행이 미리보기 줄을 잡아먹지 않게 공백으로 합칩니다. 항목을 탭하면
`jumpToOffset`합니다.

> **⚠️ "타이핑하면 바로 검색되게 하자"는 유혹을 참으세요.** 이 시트의 핵심 계약이 "제출해야만 실행"
> 입니다(`SearchSheetTest`의 `typingAlone_doesNotSearch_onlySearchButtonDoes`). 큰 소설에서 매 키
> 입력마다 `indexOf`를 돌리면 느리고, 검색 결과가 타이핑 중에 계속 바뀌면 오히려 헷갈립니다.

---

## 11. 읽기 제스처 · 볼륨키 · 크롬 자동 숨김

**무엇을 하나**
화면 좌/우 탭, 좌/우 스와이프, 볼륨키를 각각 "이전/다음" 또는 "양쪽 다 다음"으로 매핑할 수 있습니다.
상단바(하단바는 없음)는 로딩이 끝나면 숨고, 가운데 탭으로 토글됩니다. 상단바가 숨겨진 동안에도 읽은
비율을 놓치지 않도록 화면 구석에 작은 퍼센트 표시가 따로 뜹니다.

**파일**

- `ui/reader/ReaderScreen.kt` — 탭 존, 스와이프, `volumeKeyHandler`, `showChrome`, 크롬 숨김 중
  구석 퍼센트 표시
- `MainActivity.onKeyDown`
- 매핑 enum: `TouchTurnMode`, `SwipeTurnMode` in `ReaderSettings.kt`
- 상단바(2행): `ui/reader/ReaderTopBar.kt` — 1행: 뒤로 · 파일명 · 설정, 2행: 목차 · 검색 · 챕터점프
  토글

**구현 요약**
볼륨키는 Activity가 가로채 `ReaderScreen`의 DisposableEffect가 등록한 람다에 넘깁니다. `true`면
시스템 볼륨 토스트가 안 뜹니다. 상단바는 `Surface` 안에 `TopAppBar`(1행)와 그 아래 `Row`(2행)를 세로로
쌓은 구조입니다.

> **⚠️ 상단바가 떠 있을 때 탭하면 페이지가 넘어가면 안 됩니다.** 탭 좌표와 무관하게 "일단 상단바만
> 닫는다"가 먼저입니다 — 안 그러면 상단바를 닫으려던 탭이 페이지도 같이 넘겨버려서 사용자가 방금 본
> 내용을 놓칩니다. `ReaderChromeAutoHideTest`, `ReaderTapZoneAndSwipeNavigationTest`가 이 순서를
> 지킵니다.

---

## 12. 타이포 · 여백 · 테마 · 밝기 · 방향 · 화면 유지

**무엇을 하나**
글자 크기, 줄간격, 자간, 좌우·상하 여백, 라이트/다크/세피아, 앱 내 밝기 오버라이드, 화면 방향 고정,
화면 꺼짐 방지를 퀵설정에서 바꿉니다.

**파일**

| 역할 | 파일 |
|---|---|
| 설정 UI | `ui/reader/QuickSettingsSheet.kt` |
| 값 저장 | `data/datastore/ReaderSettings.kt`, `ReaderSettingsRepository.kt` |
| 리더 배경/글자색 | `ui/theme/ReaderThemePresets.kt` |
| 윈도우 플래그·밝기·orientation | `ui/reader/ReaderScreen.kt` DisposableEffect |

커스텀 테마 색(`CUSTOM` + ARGB)은 모델·저장소에 있으나, 퀵설정 칩은 라이트/다크/세피아만 노출합니다.

> **`QuickSettingsSheet`/`FontPickerSheet`/`ChapterPatternSheet`는 리더뿐 아니라 서재 화면에서도
> 열립니다.** 둘 다 `SettingsController`(`ui/SettingsController.kt`) 인터페이스 하나만 받으므로, 시트
> 코드는 자신이 리더에서 열렸는지 서재에서 열렸는지 모릅니다. `ReaderViewModel`은 세션 중 부수효과(재
> 페이지네이션 등)까지 처리하도록, `LibraryViewModel`은 값을 저장만 하도록 각자 구현합니다.

> **여기 새 설정 하나 추가하려면**: 네 군데를 순서대로 고쳐야 합니다. 레시피는
> [ONBOARDING.md](ONBOARDING.md) §5에 있고, 저장 키 이름을 나중에 바꾸면 안 된다는 경고도 거기
> 있습니다.

> **⚠️ 설정 스위치를 하나 더 만들 때**: `Row`로 라벨+스위치를 감싸기만 하면, 스위치가 여러 개인
> 시트에서 라벨 텍스트로 특정 스위치를 못 찾습니다(`Row`가 시맨틱 경계를 안 만들어서 전부 평탄화됨).
> 이 시트의 `SwitchRow`는 `Modifier.toggleable(role = Role.Switch)`로 감싸 라벨+스위치를 하나로
> 병합해뒀습니다 — 같은 패턴을 따르세요.

---

## 13. 한글 폰트 다운로드 · 적용

**무엇을 하나**
OFL 한글 폰트 카탈로그에서 골라 앱 내부 저장소에 받고, Compose `FontFamily`로 바로 적용합니다.
인터넷은 이 기능에만 필요합니다.

**파일**

| 역할 | 파일 |
|---|---|
| 목록·URL | `data/font/FontCatalog.kt` |
| HTTP 다운로드·진행률·`.part` 후 rename | `data/font/FontDownloadManager.kt` |
| 파일 → `FontFamily` | `data/font/FontResolver.kt` |
| UI | `ui/reader/FontPickerSheet.kt` |
| 적용 후 재페이지 | `ReaderViewModel.onViewportMeasured`(폰트 키가 바뀜) |

**구현 요약**
`filesDir/fonts/`에 저장합니다. `.part` 임시 파일로 받다가 끝나야 실제 파일명으로 rename합니다 —
다운로드 도중 죽어도 반쯤 받은 파일이 "다운로드됨"으로 오인되지 않습니다. 파일이 없으면 시스템 기본
폰트로 폴백합니다. 페이지 모드에서는 측정 키가 바뀌어 현재 오프셋 기준으로 한 장을 다시 계산합니다.

> **⚠️ `FontCatalog`의 다운로드 URL은 배포처가 바뀌면 조용히 깨집니다.** 실제로 세 개가 깨진 적이
> 있습니다(나눔고딕, 나눔명조, 리디바탕 — 저장소 자체가 없어짐). `RealFontDownloadIntegrationTest`가
> 진짜 인터넷으로 5개 전부 받아보는 테스트라, 오프라인이거나 URL이 실제로 죽으면 실패하는 게
> **의도된 동작**입니다. 이 테스트가 빨간불이면 URL부터 의심하세요.

---

## 14. 자동 페이지 넘김과 TTS (상호 배타)

**무엇을 하나**
`AutoAdvanceMode`: `OFF`/`TIMER`/`TTS` 하나뿐입니다. 타이머와 TTS를 동시에 켜는 상태를 모델에서
막습니다 — 왜 `boolean` 두 개가 아니라 이렇게 했는지는 [DESIGN_RATIONALE.md](DESIGN_RATIONALE.md) §5.

**파일**

| 역할 | 파일 |
|---|---|
| 3상태 설정 | `ReaderSettings.autoAdvanceMode` |
| 타이머 | `tts/AutoPageTurnController.kt` |
| TTS 래퍼 | `tts/TtsController.kt` |
| 청크 낭독·페이지 진행 | `ReaderViewModel` (`ttsChunkChars = 500`, `speakFromCurrentOffset`) |

**구현 요약**
`TIMER`면 설정한 초마다 `next()`를 호출합니다. `TTS`면 현재 오프셋부터 500자씩 읽고, utterance 완료
시 그 끝으로 `jumpToOffset`한 뒤 다음 청크를 말합니다. 모드가 TTS가 아니면 `ttsController.stop()`
합니다.

> **⚠️ TTS 실제 발화 완료 타이밍은 자동화 테스트로 못 잡습니다.** 기기/설치된 음성 데이터에 좌우돼
> 신뢰도가 낮습니다. `TtsControllerTest`는 엔진 준비 전/후 호출 안정성까지만 확인하고, 엔진이 없는
> 환경에서는 `Assume`으로 건너뜁니다(실패로 안 침). `ReaderViewModel` 안의 500자 청크 배선 자체도
> 주입 지점이 없어서 자동화 범위 밖입니다 — `TESTING.md`의 "의도적으로 제외" 참고.

---

## 15. VSCode 읽기 위치 동기화

**무엇을 하나**
같은 책을 PC(VSCode 확장)와 안드로이드 양쪽에서 읽을 때, "더 멀리 읽은 위치"를 공유 Supabase
프로젝트를 매개로 맞춰줍니다. 로그인·계정 개념 없이 사용자가 직접 만든 공유 시크릿 문자열 하나로
인증하고, 그 시크릿을 양쪽에 입력하는 방법은 직접 타이핑/붙여넣기 또는 **QR 스캔** 둘 다 됩니다.
실패해도(네트워크 없음, 시크릿 미검증 등) 로컬 읽기·저장 흐름에는 전혀 영향이 없는 best-effort
기능입니다.

> **개발자 개인 Supabase 프로젝트 하나를 모든 설치본이 공유하지만, 이제 설치본끼리 서로의 데이터를
> 못 봅니다.** URL/키는 여전히 소스에 고정값으로 박혀 있지만(`SupabaseConfig.kt`), 공유 시크릿의
> SHA-256 해시가 `user_key`가 돼 서버 트리거가 자동 계산하고 RLS가 그 값으로 행을 격리합니다 — 클라
> 이언트 코드 변경 없이 서버(Postgres) 쪽 스키마·정책만으로 해결했습니다. 요청 자체는 여전히 모든
> 설치본이 같은 무료 티어 한도를 나눠 쓰므로 사용자가 아주 많이 늘면 그건 별개 문제로 남습니다 — 배경은
> [`SYNC_MULTIUSER_PLAN.md`](SYNC_MULTIUSER_PLAN.md).

**파일**

| 역할 | 파일 |
|---|---|
| Supabase 프로젝트 URL·publishable key(고정값) | `data/sync/SupabaseConfig.kt` |
| PostgREST 직접 호출(fetch/upsert/testConnection) | `data/sync/ReadingPositionSyncClient.kt` |
| 매칭 키(상대경로) 정규화 + SAF documentId 역산 폴백 | `data/sync/RelativePath.kt` |
| 조회·체크포인트·팝업 트리거 | `ui/reader/ReaderViewModel.kt` (`checkRemoteAndMaybeNotify`, `scheduleRemoteSyncCheckpoint`, `syncNowToRemote`, `onReaderResumed`) |
| "더 멀리 읽었습니다" 팝업 UI | `ui/reader/ReaderScreen.kt` (`externalFurtherOffset` 다이얼로그) |
| 시크릿 수동 입력·연결 테스트 UI | `ui/reader/QuickSettingsSheet.kt` |
| QR 페이로드 파싱(`{"type":"vscode_sync","secret":"..."}`) | `data/sync/QrPairingPayload.kt` (`VscodeSync`) |
| 카메라로 QR 스캔(CameraX + ML Kit Barcode Scanning) | `ui/qr/QrScannerDialog.kt` |
| 시크릿/검증 상태 저장 | `data/datastore/ReaderSettings.kt`, `ReaderSettingsRepository.kt` |
| VSCode 쪽 QR 표시(SVG, `qrcode` 패키지) | `vscode-moonkata-reader-sync` 저장소 `src/pairingQr.ts` (`showPairingQr` 커맨드) |

**구현 요약**

1. 책마다 `BookEntity.relativePath`(라이브러리 루트 기준 상대경로, NFC+소문자 정규화)가 VSCode
   쪽과 맞춰야 하는 매칭 키입니다. 폴더 브라우징 중엔 자연히 채워지지만, "이어서 읽기"처럼 그
   경로를 안 거치는 진입점에서는 SAF `documentId` 문자열에서 트리 루트 접두사를 잘라내 역산하는
   폴백(`relativePathFromSafDocumentUri`)으로 채웁니다.
   > ⚠️ 이 접두사 비교는 `"$treeDocumentId/"`처럼 구분자까지 포함해야 합니다. 그냥 `startsWith`로만
   > 하면 `primary:Books`와 `primary:BooksExtra`처럼 이름이 겹치는 형제 폴더를 같은 트리로 잘못
   > 매칭합니다 — 실제로 테스트 작성 중 발견한 버그입니다.
2. 시크릿을 입력만 하고 "연결 테스트"를 통과하지 않으면 기능 자체가 비활성 상태입니다
   (`supabaseSharedSecret != supabaseVerifiedSecret`이면 클라이언트를 안 만듦) — 검증 안 된
   시크릿으로 계속 실패할 요청을 조용히 반복하지 않기 위해서입니다.
   > **왜 연결 테스트가 조회가 아니라 upsert인가:** RLS가 막은 SELECT는 에러 없이 빈 배열을 돌려줘서
   > "행이 없어서 비었나 시크릿이 틀려서 비었나"를 구분할 수 없습니다. INSERT는 RLS를 어기면
   > PostgREST가 401/403으로 명확히 거부합니다.
3. 원격 조회는 책을 열 때, 그리고 리더 화면이 다시 보이게 될 때(`ON_START`, 화면 잠금 해제·다른
   앱에서 복귀 등)마다 합니다. 원격 오프셋이 로컬보다 500자 넘게 앞서 있을 때만 팝업을 띄웁니다.
   > **왜 500자인가:** VSCode 커서 오프셋과 안드로이드 페이지 오프셋은 단위가 달라(글자 위치 vs
   > 페이지 시작 위치) 같은 곳을 읽고 있어도 수백 자 어긋날 수 있습니다. 이 데드존 없이는 사소한
   > 차이로도 팝업이 계속 떴습니다.
4. 원격에 쓰는 건 같은 위치에서 1분 이상 머무를 때(체크포인트)와 화면을 벗어나는 시점뿐입니다 —
   같은 오프셋을 반복해서 안 올리도록 마지막으로 반영한 오프셋을 기억해둡니다.
5. VSCode에서 `showPairingQr` 커맨드를 실행하면(기존 시크릿이 있으면 재사용, 없으면 새로 생성) 그
   시크릿을 담은 `{"type":"vscode_sync","secret":"..."}` QR을 보여줍니다. 안드로이드 퀵설정의
   "QR로 연결"이 `QrScannerDialog`로 그걸 스캔해 `QrPairingPayload.parse`로 파싱, 시크릿 입력칸에
   자동으로 채우고 바로 연결 테스트까지 진행합니다 — 타이핑 실수 여지를 없앤 것뿐, 검증 로직 자체는
   수동 입력 경로와 동일합니다.

---

## 16. PC 파일 동기화

**무엇을 하나**
PC에서 실행하는 별도 트레이 앱([`external_library/sync_server`](../external_library/sync_server),
Go)이 지정한 폴더를 HTTPS로 공유하면, 안드로이드가 그 폴더를 라이브러리 폴더의 거울(단방향 PC→폰)로
동기화합니다. Wi-Fi에서 서로 붙어 있으면 되고, 클라우드 저장소나 로그인 없이 PC를 직접 서버로 씁니다.
자세한 설계 배경은 [`PC_SYNC_SERVER_PLAN.md`](PC_SYNC_SERVER_PLAN.md).

**파일 — 안드로이드 쪽**

| 역할 | 파일 |
|---|---|
| PC 서버 HTTP(S) 클라이언트 | `data/sync/PcSyncClient.kt` (`/ping`·`/list`·`/file`, 고정 포트 58221) |
| TLS 신뢰 로직(lenient/pinned `SSLContext`, 지문 계산) | `data/sync/PcTlsTrust.kt` |
| 로컬 서브넷에서 PC 서버 찾기 | `data/sync/PcHostScanner.kt` (`/ping`으로 후보마다 확인) |
| 라이브러리 SAF 트리 재귀 스캔(동기화용) | `data/sync/LocalLibraryScanner.kt` |
| 델타 계산(순수 함수) + 실제 SAF 반영 | `data/sync/PcSyncFileManager.kt` (`computeSyncDelta`, `sync`) |
| 동기화 설정 draft·연결 테스트·"지금 동기화" 트리거 | `ui/library/LibraryViewModel.kt` (`testPcSyncConnection`, `scanForPcSyncHosts`, `syncFromPc`, `pcSyncState`) |
| 설정 UI | `ui/library/PcSyncSheet.kt` |
| 호스트/시크릿/검증 상태/지문 저장 | `data/datastore/ReaderSettings.kt`, `ReaderSettingsRepository.kt` |
| QR 페이로드 파싱(`{"type":"pc_sync","host":...,"secret":...,"fingerprint":...}`) | `data/sync/QrPairingPayload.kt` (`PcSync`) |
| 카메라로 QR 스캔(§15와 공유) | `ui/qr/QrScannerDialog.kt` |

**파일 — PC 쪽 (Go, `external_library/sync_server`)**

| 역할 | 파일 |
|---|---|
| 진입점, HTTPS 서버 기동, 중복 실행 방지 | `main.go` |
| HTTP 라우팅(`/ping`, `/list`, `/file`, `/pair`) + 시크릿 검사 | `server.go` |
| 폴더 재귀 스캔 + 경로 탈출 방지 | `filelist.go` |
| 설정 파일(`%APPDATA%\MoonkataSyncServer\config.json`) | `config.go` |
| 트레이 메뉴 변경이 재시작 없이 반영되게 하는 공유 상태 | `state.go` |
| 자체 서명 인증서 생성·재사용 | `tls.go` |
| 시스템 트레이 UI(폴더 변경/QR 보기/시크릿 복사·재생성/자동실행) | `tray.go` |
| `/pair` 페이지 — 로컬 LAN IP 추정 + QR PNG 생성(호스트/시크릿/지문 포함) | `pair.go` |
| 네이티브 알림·폴더선택·클립보드(Windows 토스트, MessageBox 아님) | `dialog_windows.go` |
| Windows 시작 프로그램 등록(`HKCU ... Run`) | `autostart_windows.go` |
| 이름 있는 뮤텍스로 중복 실행 방지 | `single_instance_windows.go` |

**구현 요약**

1. PC 서버는 처음 실행될 때 시크릿을 자동 생성해 트레이 알림으로 보여주고 클립보드에 복사합니다.
   안드로이드에서 그 시크릿과 PC 주소(직접 입력 또는 "PC 찾기")를 입력하고 "연결 테스트"를 통과해야
   "지금 동기화"가 활성화됩니다.
2. **왜 CA 인증이 아니라 지문 고정(TOFU)인가:** 사설 IP(`192.168.x.x`)엔 공인 CA가 인증서를 못
   줍니다. 그래서 "PC 찾기"·"연결 테스트"는 `createLenientSslContext`(아무 인증서나 받아들임)로
   접속하되, 연결 테스트 성공 순간 실제로 받은 인증서 지문을 저장합니다. 이후 "지금 동기화"는
   `createPinnedSslContext`로 그 지문과 정확히 일치하는 인증서만 받아들입니다 — SSH가 최초 접속 때
   호스트 키를 저장해두는 것과 같은 방식입니다.
3. 델타는 상대경로 키(VSCode 동기화와 같은 정규화 규칙 재사용)로 원격·로컬 파일 목록을 맞춰봐서
   계산합니다(`computeSyncDelta`, 순수 함수).
   > **⚠️ 크기만 비교하고 수정시각은 안 봅니다.** 다운로드한 로컬 파일의 수정시각은 "받은 시점"이
   > 돼버려 PC 원본 시각을 못 물려받습니다. 수정시각을 비교에 넣으면 안 바뀐 파일도 재동기화마다
   > 매번 다시 받는 버그가 실제로 있었습니다(2026-09-02 실기기 검증).
4. 기존 로컬 파일을 갱신할 땐 `documentUri`를 유지한 채 내용만 덮어씁니다 — 지우고 새로 만들면
   `BookEntity`가 그 URI로 참조하던 읽기 위치 기록이 고아가 됩니다.
5. PC 서버의 폴더/시크릿은 `AppState`(뮤텍스로 보호된 공유 상태)를 통해 매 요청마다 읽으므로,
   트레이 메뉴에서 폴더를 바꾸거나 시크릿을 재생성해도 HTTP 리스너를 재시작할 필요가 없습니다.
6. 트레이 메뉴 "동기화 QR 보기"는 기본 브라우저로 `https://127.0.0.1:{port}/pair`를 엽니다. 이
   엔드포인트는 인증 없이 응답합니다 — QR 자체에 시크릿이 담겨 있어서, `/list`·`/file`처럼 시크릿을
   요구하면 "QR을 보려고 시크릿이 먼저 필요"한 모순이 생기기 때문입니다. QR에는 호스트(§ 아래 참고)·
   시크릿·인증서 지문이 한 번에 담겨서, 안드로이드가 스캔만 하면 "PC 찾기"(서브넷 스캔)와 수동 시크릿
   입력을 모두 건너뛰고 지문도 QR로 미리 받아 lenient TLS 단계 없이 바로 `createPinnedSslContext`로
   연결을 시작합니다.
   > **⚠️ QR의 `host` 필드는 포트 없이 IP만 담습니다.** `PcSyncClient`가 고정 포트(58221)를 스스로
   > 붙이는 구조라, 한때 `pair.go`가 포트까지 같이 보내 "IP:포트:포트"로 겹치는 실제 버그가 있었습니다
   > (`MalformedURLException`, 실사용 중 발견 → `pair_test.go`에 회귀 테스트 추가).
   > **⚠️ `localLanIP()`는 인터페이스를 그냥 순서대로 훑지 않습니다.** VPN/가상 어댑터나 DHCP 실패로
   > 생긴 링크-로컬 주소(`169.254.x.x`)를 먼저 집어 QR에 잘못된 주소가 실리는 문제가 실사용 중
   > 있었습니다 — 대신 UDP 소켓으로 실제 아웃바운드 라우팅을 물어보는 표준 트릭(`outboundIP`)을 우선
   > 쓰고, 완전 오프라인일 때만 링크-로컬을 걸러낸 인터페이스 목록으로 폴백합니다.
7. **모든 트레이 알림은 논블로킹입니다.** 원래 Win32 `MessageBoxW` 기반 모달로 시작/폴더변경/시크릿
   복사 등을 알렸는데, 사용자가 PC 앞에 없을 때 확인을 누를 때까지 서버가 멈춰있는 것처럼 보이는
   문제가 실사용 피드백으로 나와서 전부 `showNotification`(PowerShell로 띄우는 WinForms
   `NotifyIcon.ShowBalloonTip`, 별도 프로세스를 기다리지 않고 즉시 리턴)으로 교체했습니다 —
   `dialog_windows.go`에 `MessageBoxW` 코드는 더 이상 없습니다.
8. exe를 두 번 실행해도 서버/트레이 아이콘이 중복 기동되지 않도록 `single_instance_windows.go`가
   이름 있는 Windows 뮤텍스로 두 번째 실행을 감지해 조용히 종료시킵니다.

---

## 데이터 계층 한눈에

```
Room (books)
  └── BookRepository  ← 서재 열기 / 리더 로드 / 위치 저장

DataStore (reader_settings)
  └── ReaderSettingsRepository  ← 거의 모든 뷰어 옵션 + 마지막 SAF 폴더

파일
  └── BookSource → BookContentReader → EncodingDetector
  └── SafFolderBrowser (폴더 한 단계 / zip 엔트리)

파서 (세션 메모리만)
  └── TextReflower → Paragraphs
  └── ChapterDetector → Chapters (패턴은 DataStore)
  └── Paginator → 현재 PageBreak 하나
  └── ChapterJumpNavigator → 점프 오프셋 목록

기기 간 동기화 (둘 다 기본 꺼짐, best-effort)
  └── ReadingPositionSyncClient → Supabase(reading_positions 테이블) ← 읽기 위치만, VSCode와 공유
  └── PcSyncClient/PcSyncFileManager → PC 트레이 서버(HTTPS+TOFU) ← 책 파일 자체, 라이브러리 SAF 트리로 반영
```

도메인 모델: `model/Paragraph.kt`, `Chapter.kt`, `PageBreak.kt`, `SearchResult.kt`, `FolderEntry.kt`.

---

## 테스트가 가리키는 기능

로직은 `app/src/test`, Compose/Room/실제 측정은 `app/src/androidTest`입니다. 전체 계획은
[`TESTING.md`](TESTING.md).

대표적으로 라이브러리 정렬·폴더 탐색, 서재 화면에서 설정 시트 열기(`LibraryScreenSettingsAccessTest`),
이어읽기 다이얼로그, 페이지 왕복, 먼 오프셋 점프, 목차 자동 스크롤, 검색 시트, 퀵설정, 폰트 적용 후
재페이지, 챕터 인식 회귀, 폰트 다운로드(MockWebServer + 실네트워크)가 있습니다. 기기 간 동기화(§15,
§16)는 상대경로 정규화·델타 계산·QR 페이로드 파싱(`QrPairingPayloadTest`) 같은 순수 로직과,
`MockWebServer`(HTTPS 포함)로 검증하는 PC/Supabase 요청 프로토콜 계약까지가 자동화 테스트 범위이고,
실제 카메라 QR 스캔·SAF 파일 쓰기·서브넷 스캔·PC 쪽 트레이 UI/Windows 자동 실행은 실기기 수동 검증으로
남겨뒀습니다(`TESTING.md`의 "의도적으로 제외" 참고). PC 서버(Go)의 경로 탈출 방지 로직과 `/pair`의
호스트 필드 형식(`TestHandlePair_HostFieldHasNoPort`)은 `external_library/sync_server`에서
`go test ./...`로 별도 검증합니다.
