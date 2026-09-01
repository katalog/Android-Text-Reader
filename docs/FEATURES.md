# 문카타 리더 — 기능별 구현 설명

로컬 `.txt`(및 zip 안의 `.txt`) 소설을 오프라인으로 읽는 안드로이드 앱입니다.  
화면은 **서재(라이브러리)** 와 **리더** 두 개뿐이고, DI 프레임워크 없이 수동 MVVM(`AndroidViewModel` + Repository)으로 구성됩니다.

진입점: `MainActivity`가 Compose `NavHost`만 띄우고, 볼륨키는 리더가 켜져 있을 때만 가로챕니다.

```
library  →  reader/{bookId}
```

관련 파일:

| 역할 | 파일 |
|---|---|
| 액티비티, 볼륨키 위임 | `app/src/main/java/com/moonkata/textreader/MainActivity.kt` |
| 화면 전환 | `app/src/main/java/com/moonkata/textreader/navigation/AppNavigation.kt` |
| 매니페스트(인터넷은 폰트 다운로드만) | `app/src/main/AndroidManifest.xml` |

설정은 DataStore(`ReaderSettings` / `ReaderSettingsRepository`), 책·읽기 위치는 Room(`BookEntity` / `BookDao` / `AppDatabase`)에 저장합니다.

---

## 1. 서재 — SAF 폴더 탐색기

**무엇을 하나**  
시스템 폴더 선택기(Storage Access Framework)로 루트 폴더를 고르면, 그 안을 **한 단계씩** 탭해서 들어갑니다. 전체 트리를 재귀 스캔하지 않습니다. `.txt`와 `.zip`만 보이고, zip은 폴더처럼 들어가 그 안의 `.txt`를 열 수 있습니다. 이름/날짜/크기 정렬과 파일별 읽기 진행률 표시가 있습니다.

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

**구현 요약**

1. FAB → `OpenDocumentTree`로 트리 URI를 받습니다.
2. `takePersistableUriPermission`으로 재실행 후에도 읽을 수 있게 만들고, URI를 DataStore `lastUsedSafTreeUri`에 넣습니다. 다음 실행 때 그 폴더를 바로 엽니다.
3. `BrowseLocation` 스택(실제 폴더 또는 zip)으로 경로를 유지합니다. 뒤로가기/브레드크럼은 스택을 잘라 `listFolder` / `listZipEntries`만 다시 호출합니다.
4. `.txt`를 탭하면 `BookSource`를 문자열로 직렬화해 Room에 upsert하고, `openBookEvents`로 `reader/{id}`로 이동합니다.
5. 목록 진행률은 `observeLibrary()`의 `lastReadProgressPercent`를 `documentUri` 키로 매핑해 행에 붙입니다.

---

## 2. 이어서 읽기 다이얼로그

**무엇을 하나**  
앱을 새로 켰을 때, 최근에 연 책이 있으면 바로 이어서 볼지 묻습니다. 같은 프로세스 안에서는 한 번만 뜹니다.

**파일**

- `ui/library/LibraryViewModel.kt` — `resumeCandidate`
- `ui/library/LibraryScreen.kt` — `ResumeReadingDialog`

**구현 요약**  
시작 시 `observeLibrary()`의 첫 항목(최근순)에 `lastOpenedAt`이 있으면 후보로 올립니다. 확인하면 그 `bookId`로 리더로 가고, 닫으면 후보만 비웁니다.

---

## 3. 책 파일 열기 · 인코딩 · zip

**무엇을 하나**  
일반 txt URI 또는 `zip:URI!entryName` 형태를 읽어, 앞 256KB 샘플로 문자셋을 추정한 뒤 전체 바이트를 그 인코딩으로 디코딩합니다. UTF-8 / EUC-KR / CP949(MS949) 등을 다룹니다.

**파일**

| 역할 | 파일 |
|---|---|
| 위치 모델·직렬화 | `data/file/BookSource.kt` |
| 바이트 읽기 + 디코딩 | `data/file/BookContentReader.kt` |
| 인코딩 감지 | `data/file/EncodingDetector.kt` (`juniversalchardet`) |
| zip 안 txt 목록 | `data/file/SafFolderBrowser.kt` `listZipEntries` |
| 메타(총 글자 수, 인코딩명) 저장 | `BookRepository.markOpened` → `BookDao.updateMeta` |

**구현 요약**  
`ZipInputStream`으로 엔트리 이름이 일치할 때까지 순회해 바이트를 꺼냅니다. 감지는 `UniversalDetector` 결과와 MS949 / EUC-KR / UTF-8 후보를 `Charset.isSupported`로 걸러 UTF-8로 폴백합니다.

---

## 4. 읽기 위치 저장 (문자 오프셋)

**무엇을 하나**  
페이지 번호가 아니라 **전체 텍스트 내 문자 오프셋**과 진행률(%)을 Room에 둡니다. 폰트·여백이 바뀌어 페이지가 다시 나뉘어도 같은 글자 위치로 복원됩니다.

**파일**

| 역할 | 파일 |
|---|---|
| 스키마 | `data/db/BookEntity.kt` (`lastReadCharOffset`, `lastReadProgressPercent`) |
| DAO | `data/db/BookDao.kt` `updateReadPosition` |
| 디바운스·플러시 | `ui/reader/ReaderViewModel.kt` (`updateCurrentOffset`, `schedulePositionWrite`, `flushPendingPosition`) |
| ON_STOP 즉시 저장 | `ui/reader/ReaderScreen.kt` Lifecycle observer |
| ViewModel 종료 시 블로킹 저장 | `ReaderViewModel.onCleared` |

**구현 요약**  
오프셋 변경 후 500ms 디바운스로 DB에 씁니다. 백그라운드 전환이나 화면 이탈 때는 타이머를 기다리지 않고 `flushPendingPosition`합니다. `onCleared`에서는 `viewModelScope`가 곧 취소되므로 `runBlocking`으로 마지막 위치를 남깁니다.

---

## 5. 가로 페이지 모드 (스와이프 페이지)

**무엇을 하나**  
화면에 들어갈 만큼만 잘라 **현재 페이지 하나**만 계산·표시합니다. 책 전체 페이지 리스트를 만들지 않습니다. 다음 페이지는 현재 끝 오프셋부터 한 장만 측정하고, 이전은 정방향 방문 이력을 팝하거나(정확), 점프 직후처럼 이력이 없으면 역산합니다.

**파일**

| 역할 | 파일 |
|---|---|
| `TextMeasurer`로 경계 계산 | `data/parser/Paginator.kt` |
| 현재 페이지·이력 | `ui/reader/ReaderViewModel.kt` (`pageHistory`, `advancePageForward` / `advancePageBackward`) |
| 렌더·전환 애니메이션 | `ui/reader/ReaderPagerContent.kt` (`AnimatedContent`) |
| 문단 분할 | `data/parser/TextReflower.kt` |
| 페이지 구간 모델 | `model/PageBreak.kt`, `model/Paragraph.kt` |

**구현 요약**

1. 뷰포트가 잡히면 `onViewportMeasured`가 폰트/크기/여백 키를 만들고, 바뀌면 이력을 비운 뒤 `paginateFrom(..., maxPages = 1)`로 현재 페이지만 계산합니다.
2. 측정은 **페이지 전체 substring을 한 번에** 합니다. 문단별 높이 합은 실제 `Text` 렌더와 어긋나 마지막 줄이 잘릴 수 있어 쓰지 않습니다.
3. 넘치면 레이아웃의 줄 단위 `getLineBottom`으로 들어가는 줄 수까지 자르고, 다음 페이지 시작은 선행 개행을 건너뜁니다.
4. 전환은 `HorizontalPager`가 아니라 `AnimatedContent` + `PageTransitionAnimation`(없음 / 슬라이드 / 덮기)입니다.

이전 페이지 역산: `Paginator.onePageEndingAt`이 목표 끝보다 앞 구간을 순방향으로 몇 장 계산해, `endOffset` 바로 앞에서 끝나는 페이지를 고릅니다.

---

## 6. 세로 스크롤 모드

**무엇을 하나**  
문단 리스트를 `LazyColumn`으로 스크롤합니다. 이어읽기·검색·목차 점프는 오프셋에 해당하는 문단 인덱스로 스크롤합니다.

**파일**

- `ui/reader/ReaderScrollContent.kt`
- `ui/reader/ReaderViewModel.kt` — `ReaderNavEvent` (`JumpToOffset`, `RequestNextPage` / `RequestPreviousPage`)
- `util/CollectionExt.kt` — `binarySearchFloor`

**구현 요약**  
`snapshotFlow`로 보이는 아이템의 시작 오프셋을 `currentOffset`에 반영합니다. 다음/이전은 뷰모델이 이벤트를 보내고, UI가 한 화면 분량만큼 스크롤합니다. 챕터 점프가 켜져 있으면 챕터 제목 줄에 배경 하이라이트를 넣습니다.

읽기 모드 전환: `PageTurnMode` (`HORIZONTAL_PAGE` / `VERTICAL_SCROLL`)를 DataStore에 두고 `ReaderScreen`이 두 콘텐츠 중 하나를 고릅니다.

---

## 7. 줄바꿈 정리 (원문 유지 / 문단 재구성)

**무엇을 하나**  
소설 txt의 강제 줄바꿈을 그대로 두거나, 빈 줄만 문단 경계로 보고 한 줄 개행은 공백으로 이어붙입니다.

**파일**

- `data/parser/TextReflower.kt`
- 설정: `LineBreakMode` in `data/datastore/ReaderSettings.kt`
- 재적용: `ReaderViewModel.reflowParagraphs` (설정 변경 시 `Dispatchers.Default`)

---

## 8. 챕터(목차) 자동 인식

**무엇을 하나**  
세션마다 줄 단위로 정규식을 돌려 챕터 목록을 만듭니다. DB에 목차를 넣지 않습니다. 매칭 0건은 “목차 없음”으로 정상 처리합니다.

**파일**

| 역할 | 파일 |
|---|---|
| 줄 스캔 | `data/parser/ChapterDetector.kt` |
| 내장 프리셋 + 커스텀 정규식 합성 | `data/parser/ChapterPatternCatalog.kt` |
| 패턴 켜기/끄기·추가 UI | `ui/reader/ChapterPatternSheet.kt` |
| 목차 시트 | `ui/reader/TocSheet.kt` |
| 로드와 분리된 백그라운드 탐지 | `ReaderViewModel.loadBook` / `redetectChapters` |

**구현 요약**  
줄 길이 ≤ 60이고 trim 후 패턴 중 하나와 `matches`되면 챕터입니다. 기본 프리셋은 `##`로 시작하는 줄입니다. 첫 페이지는 챕터 탐지가 끝나기 전에 띄우고, 끝나면 `chapters`만 채웁니다.

목차 시트를 열면 `currentOffset` 이하인 마지막 챕터를 현재로 강조하고, 그 위치보다 약간 앞에서부터 스크롤합니다.

---

## 9. 챕터 점프 모드

**무엇을 하나**  
각 챕터 구간을 N등분한 지점을 순서대로 점프해 빠르게 훑습니다. 마지막 등분점은 다음 챕터 시작과 같아, 계속 “다음”을 누르면 자연히 다음 장으로 갑니다.

**파일**

- `data/parser/ChapterJumpNavigator.kt`
- `ReaderViewModel.next` / `previous` (`lastChapterJumpOffset`)
- 토글 UI: `ui/reader/ReaderBottomBar.kt`, 설정: `QuickSettingsSheet.kt`

**구현 요약**  
페이지가 정착하면 `currentOffset`이 페이지 **시작**(점프 목표보다 앞)으로 맞춰질 수 있습니다. 같은 브레이크포인트를 다시 잡지 않도록 마지막 **목표 오프셋**을 따로 둡니다. 더 점프할 지점이 없으면 일반 페이지 넘김으로 폴백합니다.

---

## 10. 본문 검색

**무엇을 하나**  
입력 중이 아니라 검색 버튼(또는 IME 검색)을 눌렀을 때 실행합니다. 대소문자 무시 `indexOf`, 최대 200건. 시트를 닫았다 열어도 마지막 쿼리·결과를 유지하고, 현재 위치와 가장 가까운 결과가 강조되며 목록이 그 근처로 스크롤됩니다.

**파일**

- `ui/reader/SearchSheet.kt`
- `ReaderViewModel.search` — `lastSearchQuery`, `lastSearchResults`
- `model/SearchResult.kt`

**구현 요약**  
스니펫은 매칭 전후 20자이며, 개행이 미리보기 줄을 잡아먹지 않게 공백으로 합칩니다. 항목을 탭하면 `jumpToOffset`합니다.

---

## 11. 읽기 제스처 · 볼륨키 · 크롬 자동 숨김

**무엇을 하나**  
화면 좌/우 탭, 좌/우 스와이프, 볼륨키를 각각 “이전/다음” 또는 “양쪽 다 다음”으로 매핑할 수 있습니다. 상·하단 바는 로딩이 끝나면 숨고, 가운데 탭으로 토글됩니다.

**파일**

- `ui/reader/ReaderScreen.kt` — 탭 존, 스와이프, `volumeKeyHandler`, `showChrome`
- `MainActivity.onKeyDown`
- 매핑 enum: `TouchTurnMode`, `SwipeTurnMode` in `ReaderSettings.kt`
- 상단바: `ui/reader/ReaderTopBar.kt` (뒤로, 검색, 목차, 설정)

**구현 요약**  
볼륨키는 Activity가 가로채 `ReaderScreen`의 DisposableEffect가 등록한 람다에 넘깁니다. true면 시스템 볼륨 토스트가 안 뜹니다.

---

## 12. 타이포 · 여백 · 테마 · 밝기 · 방향 · 화면 유지

**무엇을 하나**  
글자 크기, 줄간격, 자간, 좌우·상하 여백, 라이트/다크/세피아, 앱 내 밝기 오버라이드, 화면 방향 고정, 화면 꺼짐 방지를 퀵설정에서 바꿉니다.

**파일**

| 역할 | 파일 |
|---|---|
| 설정 UI | `ui/reader/QuickSettingsSheet.kt` |
| 값 저장 | `data/datastore/ReaderSettings.kt`, `ReaderSettingsRepository.kt` |
| 리더 배경/글자색 | `ui/theme/ReaderThemePresets.kt` |
| 윈도우 플래그·밝기·orientation | `ui/reader/ReaderScreen.kt` DisposableEffect |

커스텀 테마 색(`CUSTOM` + ARGB)은 모델·저장소에 있으나, 퀵설정 칩은 라이트/다크/세피아만 노출합니다.

---

## 13. 한글 폰트 다운로드 · 적용

**무엇을 하나**  
OFL 한글 폰트 카탈로그에서 골라 앱 내부 저장소에 받고, Compose `FontFamily`로 바로 적용합니다. 인터넷은 이 기능에만 필요합니다.

**파일**

| 역할 | 파일 |
|---|---|
| 목록·URL | `data/font/FontCatalog.kt` |
| HTTP 다운로드·진행률·`.part` 후 rename | `data/font/FontDownloadManager.kt` |
| 파일 → `FontFamily` | `data/font/FontResolver.kt` |
| UI | `ui/reader/FontPickerSheet.kt` |
| 적용 후 재페이지 | `ReaderViewModel.onViewportMeasured` (폰트 키가 바뀜) |

**구현 요약**  
`filesDir/fonts/`에 저장합니다. 파일이 없으면 시스템 기본 폰트로 폴백합니다. 페이지 모드에서는 측정 키가 바뀌어 현재 오프셋 기준으로 한 장을 다시 계산합니다.

---

## 14. 자동 페이지 넘김과 TTS (상호 배타)

**무엇을 하나**  
`AutoAdvanceMode`: `OFF` / `TIMER` / `TTS` 하나뿐입니다. 타이머와 TTS를 동시에 켜는 상태를 모델에서 막습니다.

**파일**

| 역할 | 파일 |
|---|---|
| 3상태 설정 | `ReaderSettings.autoAdvanceMode` |
| 타이머 | `tts/AutoPageTurnController.kt` |
| TTS 래퍼 | `tts/TtsController.kt` |
| 청크 낭독·페이지 진행 | `ReaderViewModel` (`ttsChunkChars = 500`, `speakFromCurrentOffset`) |

**구현 요약**  
TIMER면 설정한 초마다 `next()`를 호출합니다. TTS면 현재 오프셋부터 500자씩 읽고, utterance 완료 시 그 끝으로 `jumpToOffset`한 뒤 다음 청크를 말합니다. 모드가 TTS가 아니면 `ttsController.stop()`합니다.

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
```

도메인 모델: `model/Paragraph.kt`, `Chapter.kt`, `PageBreak.kt`, `SearchResult.kt`, `FolderEntry.kt`.

---

## 테스트가 가리키는 기능

로직은 `app/src/test`, Compose/Room/실제 측정은 `app/src/androidTest`입니다. 자세한 목록은 저장소의 `TESTING.md`를 참고하세요.

대표적으로 라이브러리 정렬·폴더 탐색, 이어읽기 다이얼로그, 페이지 왕복, 먼 오프셋 점프, 목차 자동 스크롤, 검색 시트, 퀵설정, 폰트 적용 후 재페이지, 챕터 인식 회귀, 폰트 다운로드(MockWebServer + 실네트워크)가 있습니다.
