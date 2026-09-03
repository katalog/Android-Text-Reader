# 사용자 시나리오별 코드 실행 흐름

[FEATURES.md](FEATURES.md)가 "이 기능은 이 파일들로 구현돼 있다"는 카탈로그라면, 이 문서는 그 반대
방향입니다 — **"사용자가 이렇게 조작하면 어떤 파일의 어떤 함수가 어떤 순서로 실행되는가"**를 시나리오
단위로 따라갑니다. 코드를 처음 보고 동작 하나를 끝까지 쫓아가 보고 싶을 때 이 문서를 펴면 됩니다. "왜
이렇게 짰는지"는 다루지 않습니다 — 각 시나리오 끝의 `FEATURES.md` 절 번호를 따라가면 그 설계 배경이
있고, 거기서도 안 풀리면 [DESIGN_RATIONALE.md](DESIGN_RATIONALE.md)로 가세요.

파일 경로는 모두 `app/src/main/java/com/moonkata/textreader/` 기준 상대경로이고, PC 서버 쪽은
`external_library/sync_server/` 기준입니다.

> **읽는 요령**: 번호가 매겨진 단계는 실제 호출 순서 그대로입니다. ⚠️ 표시가 붙은 단계는 "왜 이렇게
> 갈라지는지" 알아야 나머지가 이해되는 지점이니 건너뛰지 마세요.

---

## 1. 앱 최초 실행 → 서재 폴더 선택 (FEATURES.md §1)

1. `MainActivity` → `AppNavigation`이 `library` 라우트로 진입 → `ui/library/LibraryScreen.kt` 컴포저블이
   렌더되고 `ui/library/LibraryViewModel.kt`(`AndroidViewModel`)가 생성됨
2. `LibraryViewModel`이 `data/datastore/ReaderSettingsRepository.kt`의 `settingsFlow`를 구독 —
   저장된 `lastUsedSafTreeUri`가 없으므로 빈 서재 상태로 시작
3. 사용자가 FAB 탭 → `ui/library/FolderPickerLauncher.kt`가 `ActivityResultContracts.OpenDocumentTree`
   실행 → 시스템 폴더 선택기가 뜸
4. 폴더 선택 완료 → 콜백이 `LibraryViewModel`에 결과 URI 전달 → `util/SafUriExt.kt`의
   `takePersistableReadPermission`으로 영구 읽기 권한 획득 → `ReaderSettingsRepository`에
   `lastUsedSafTreeUri` 저장(다음 실행 때 이 폴더를 바로 엶)
5. `data/file/SafFolderBrowser.kt`의 `listFolder(treeUri)` 호출 → 한 단계 목록(`.txt`/`.zip`만, 재귀
   스캔 아님)을 반환 → `LibraryScreen`이 `LazyColumn`으로 렌더
6. **정렬 변경**: 정렬 메뉴에서 옵션(이름/날짜/크기 × 오름차순/내림차순, `FolderSortOption`) 선택 →
   `LibraryViewModel.setSortOption(option)`이 즉시 목록을 재정렬하고
   `ReaderSettingsRepository.updateLibrarySortOption`으로 다음 실행에도 남게 저장
7. **폴더/zip 진입**: 폴더 또는 `FolderEntry.ZipArchive` 항목 탭 → `navigateInto(entry)`가
   `BrowseLocation`(`Folder` 또는 `Zip`) 하나를 경로 스택에 push → `loadCurrent()`가 그 타입에 따라
   `SafFolderBrowser.listFolder`(폴더) 또는 `listZipEntries`(zip 내부 `.txt` 나열)를 호출
8. **브레드크럼으로 상위 폴더 복귀**: 브레드크럼 항목 탭 → `navigateToBreadcrumb(index)`가 경로 스택을
   그 지점까지 잘라내고 `loadCurrent()` 재호출 — 뒤로 몇 단계든 스택만 자르면 되므로 개별 폴더를
   기억해뒀다 되짚어갈 필요가 없음

## 2. 앱 재실행 → 이어서 읽기 다이얼로그 (FEATURES.md §2)

1. `LibraryViewModel`의 `observeLibrary()`가 Room `BookDao`를 최근순으로 조회 → 첫 항목에
   `lastOpenedAt`이 있으면 `resumeCandidate`에 세팅
2. `LibraryScreen`이 `resumeCandidate`를 관찰해 `ResumeReadingDialog` 표시
3. "이어서 읽기" 탭 → `reader/{bookId}`로 네비게이션(아래 3번 시나리오로 이어짐)
4. "취소" → `resumeCandidate`만 비우고 서재 화면 유지

> ⚠️ 후보로 올리기 전에 파일이 지금도 실제로 열리는지(`bookFileExists`)를 확인합니다. 이걸 빼면,
> 파일이 삭제되거나 이동된 책도 후보로 뜨고 "계속 보기"를 누르는 순간 처리 안 된
> `FileNotFoundException`으로 앱이 죽습니다 — 실제로 있었던 버그이고 `ResumeCandidateFileExistsTest`가
> 재발 방지 테스트입니다.

## 3. 서재에서 책 파일 탭 → 리더 진입 → 첫 페이지 표시 (FEATURES.md §3, §4, §5, §8)

1. `LibraryScreen`에서 `.txt`(또는 zip 내부 `.txt`) 항목 탭 → `LibraryViewModel`이
   `data/file/BookSource.kt`로 위치를 직렬화해 `data/repository/BookRepository.kt`의
   `findOrCreateBook`으로 Room에 upsert
2. `openBookEvents`(SharedFlow)로 `bookId` 방출 → `LibraryScreen`이 관찰해 `reader/{bookId}`로 이동
3. `ui/reader/ReaderScreen.kt` 진입 → `ui/reader/ReaderViewModel.kt`의 `loadBook(bookId)` 호출:
   - `backfillRelativePathIfNeeded` — `relativePath`가 비어 있으면 SAF `documentId` 문자열에서
     역산해 채움(`data/sync/RelativePath.kt`, VSCode 동기화용 매칭 키)
   - `BookRepository.openBookContent(book)` → `data/file/BookContentReader.kt`가 바이트를 읽고
     `data/file/EncodingDetector.kt`로 인코딩을 추정한 뒤 디코딩된 전체 텍스트 반환
   - `data/parser/TextReflower.kt`의 `reflow(text, lineBreakMode)`(`Dispatchers.Default`)로 문단
     리스트 생성
   - `_uiState`에 `fullText`/`paragraphs`/`currentOffset`(저장된 `lastReadCharOffset`)을 반영 —
     로딩 게이트 해제, 첫 페이지 표시 가능
   - `redetectChapters(settings)`를 백그라운드로 트리거 — ⚠️ 여기서 첫 페이지 표시를 기다리지 않는
     게 핵심입니다. `data/parser/ChapterDetector.kt`의 `detect` 결과는 나중에 `chapters`로 따로
     채워지고, 사용자는 그 사이에도 이미 첫 페이지를 보고 있습니다.
   - `checkRemoteAndMaybeNotify` 호출(VSCode 동기화가 켜져 있으면 — 15번 시나리오로 이어짐)
4. 페이지 모드면 `ui/reader/ReaderPagerContent.kt`의 뷰포트 측정 콜백 `onViewportMeasured`가
   `data/parser/Paginator.kt`의 `paginateFrom(..., maxPages = 1)`로 현재 페이지 한 장만 계산해 표시.
   스크롤 모드면 `ui/reader/ReaderScrollContent.kt`가 `currentOffset`에 해당하는 문단 인덱스로 바로
   스크롤.

## 4. 페이지 넘기기 — 탭 / 스와이프 / 볼륨키, 페이지 모드 (FEATURES.md §5, §9, §11)

1. `ReaderScreen`의 탭 존·스와이프 제스처 핸들러, 또는 `MainActivity.onKeyDown`(볼륨키) →
   `ReaderViewModel.next()` / `previous()` 호출
2. **챕터 점프 모드가 꺼져 있으면**: `next()`는 `Paginator.paginateFrom`(현재 페이지 끝 오프셋부터
   `maxPages = 1`)으로 다음 페이지 한 장만 새로 측정하고, 넘어가기 전 페이지를 방문 이력 스택에
   push. `previous()`는 그 스택을 pop(있으면 즉시·정확) — 스택이 비어 있을 때만(예: 검색 점프 직후)
   `Paginator.onePageEndingAt`으로 역산 추정
3. **챕터 점프 모드가 켜져 있으면**(`ReaderSettings.chapterJumpEnabled`): ⚠️ 여기서 경로가 완전히
   갈립니다 — `ChapterJumpNavigator`가 계산한 breakpoint 오프셋 목록 중 현재 위치 기준 다음/이전
   지점을 골라 `jumpToPageAt(target)`으로 이동하고, 위 2번의 방문 이력 스택은 아예 타지 않습니다.
   같은 지점을 다시 잡지 않도록 마지막으로 실제 이동한 목표 오프셋을 `lastChapterJumpOffset`에
   따로 기억해둡니다(9번 시나리오 참고). 테스트를 짤 때 이 두 경로를 헷갈리면 안 됩니다.
4. 새 오프셋이 `currentOffset`에 반영 → `ReaderPagerContent`가 `AnimatedContent`로 전환 애니메이션
   렌더 → 오프셋 변경이 500ms 디바운스 로컬 저장(14번 시나리오)과 원격 동기화 체크포인트 타이머
   재시작(15번 시나리오)을 함께 트리거

## 5. 세로 스크롤 모드에서 스크롤 (FEATURES.md §6)

1. `ReaderSettings.pageTurnMode == VERTICAL_SCROLL`이면 `ReaderScreen`이 `ReaderScrollContent`를 표시
2. `LazyColumn`의 `snapshotFlow`가 화면에 보이는 첫 아이템의 시작 오프셋을 관찰해 `currentOffset`
   갱신(디바운스 저장 트리거는 페이지 모드와 동일)
3. 다음/이전 버튼·볼륨키는 `ReaderViewModel`이 `ReaderNavEvent.RequestNextPage` /
   `RequestPreviousPage` 이벤트를 방출하고, `ReaderScrollContent`가 그걸 받아 한 화면 분량만큼
   `LazyColumn`을 스크롤

## 6. 본문 검색 (FEATURES.md §10)

1. `ui/reader/ReaderTopBar.kt`의 검색 아이콘 탭 → `ui/reader/SearchSheet.kt` 열림,
   `ReaderViewModel`의 `lastSearchQuery`/`lastSearchResults`로 이전 검색 상태 복원(시트를 껐다 켜도
   유지)
2. 검색어 입력 후 검색 버튼 또는 IME 검색 액션 →
   `ReaderViewModel.search(query)` → `fullText`에 대소문자 무시 `indexOf` 반복(최대 200건) →
   `model/SearchResult.kt` 리스트 생성, 스니펫은 매칭 전후 20자
   > ⚠️ 타이핑만으로는 실행되지 않습니다. 큰 소설에서 매 키 입력마다 `indexOf`를 돌리면 느려지고,
   > 결과가 계속 바뀌면 오히려 헷갈려서 일부러 이렇게 뒀습니다.
3. 결과 중 `currentOffset`과 가장 가까운 항목이 강조되고 그 근처로 자동 스크롤
4. 결과 탭 → `ReaderViewModel.jumpToOffset(offset)` 호출 — `lastChapterJumpOffset`을 비우고, 페이지
   모드면 `jumpToPageAt(offset)`으로 해당 오프셋이 보이는 페이지를 계산(이력 스택은 이 점프 이후
   끊김), 스크롤 모드면 `util/CollectionExt.kt`의 `binarySearchFloor`로 문단 인덱스를 찾아 스크롤

## 7. 목차(챕터) 열기 → 챕터 탭 → 점프 (FEATURES.md §8)

1. `ReaderTopBar`의 목차 아이콘 탭 → `ui/reader/TocSheet.kt` 열림
2. `chapters`(3번 시나리오의 백그라운드 탐지 완료분)에서 `currentOffset` 이하인 마지막 챕터를 현재
   챕터로 강조하고 그 근처로 자동 스크롤
3. 챕터 탭 → `jumpToOffset(chapter.offset)` — 6번 시나리오의 4번과 동일한 점프 경로

## 8. 챕터 인식 패턴 켜기/끄기/추가 (FEATURES.md §8)

1. `ui/reader/ChapterPatternSheet.kt`(또는 `QuickSettingsSheet`)에서 프리셋 토글·커스텀 정규식 추가
   → `ReaderSettingsRepository`에 저장
2. 설정 변경 감지 → `ReaderViewModel.redetectChapters` 재실행 →
   `data/parser/ChapterPatternCatalog.kt`의 `buildRegexList`(활성 프리셋 + 커스텀을 순서대로 합성) →
   `ChapterDetector.detect`가 전체 텍스트를 줄 단위로 다시 스캔 → `chapters` 갱신

## 9. 챕터 점프 모드 켜기 → 다음/이전 (FEATURES.md §9)

1. `ReaderTopBar`의 챕터점프 토글 → `ReaderSettings.chapterJumpEnabled = true`
2. `data/parser/ChapterJumpNavigator.kt`의 `breakpoints(chapters, textLength, divisions)`가 현재
   챕터 구간을 N등분한 오프셋 목록을 그때그때 계산(DB에 저장하지 않음)
3. 이후 `next()`/`previous()`는 4번 시나리오의 3번과 같이 이 breakpoint 순서를 따라
   `jumpToPageAt`을 호출
4. 더 점프할 breakpoint가 없으면(챕터의 마지막 등분점을 지남) 일반 페이지 넘김
   (`Paginator.paginateFrom`)으로 자연스럽게 폴백 — 계속 "다음"을 누르면 다음 챕터로 넘어감

## 10. 한글 폰트 다운로드 → 적용 (FEATURES.md §13)

1. `QuickSettingsSheet` → `ui/reader/FontPickerSheet.kt` 열림 → `data/font/FontCatalog.kt`의
   `entries` 목록 표시(다운로드 여부는 `data/font/FontResolver.kt`가 로컬 파일 존재로 판정)
2. 다운로드 탭 → `data/font/FontDownloadManager.kt`의 `download(entry)`가 Flow로
   `Downloading(progress)` → `Downloaded`/`Failed`를 순서대로 방출, `filesDir/fonts/`에 `.part`로
   받다가 완료 시 rename
3. 다운로드된 폰트 선택 → `selectFont` 호출 → `ReaderSettings`에 폰트 id 저장
4. 폰트 키가 바뀌었으므로 `onViewportMeasured`가 다시 트리거 → `FontResolver.resolve`로 새
   `FontFamily`를 구해 `Paginator`가 현재 오프셋 기준 페이지 한 장을 재계산(4번 시나리오의 2번과
   같은 경로, 측정 키만 폰트로 다름)

## 11. 퀵설정에서 폰트 크기 / 여백 / 테마 변경 (FEATURES.md §12)

1. `QuickSettingsSheet`에서 슬라이더·칩 조작 → 즉시 `ReaderSettingsRepository`에 저장(DataStore)
2. 폰트 크기·여백처럼 페이지 경계에 영향을 주는 값이면 `onViewportMeasured`의 측정 키가 바뀌어 4번
   시나리오의 2번과 같은 경로로 현재 페이지가 재계산됨. 테마 색상처럼 페이지 경계와 무관한 값은
   `ui/theme/ReaderThemePresets.kt`가 바로 반영돼 재계산 없이 다시 그려짐
3. **읽기 모드 전환**(페이지 ↔ 스크롤): `setPageTurnMode(value)` → `ReaderSettings.pageTurnMode` 저장
   → `ReaderScreen`이 다음 컴포지션에서 `ReaderPagerContent` 대신 `ReaderScrollContent`를(또는
   반대로) 그림 — `currentOffset`은 그대로 유지되므로 같은 글자 위치에서 모드만 바뀜(4번/5번
   시나리오 참고)
4. **줄바꿈 정리 모드 변경**(원문 유지 ↔ 문단 재구성): ⚠️ `setLineBreakMode(value)` 호출은 설정만
   저장하고 끝입니다. 실제 재적용은 `ReaderViewModel.init`이 구독 중인 `settingsFlow.collect`가
   이전 값과 다르다는 걸 감지해 `reflowParagraphs(mode)`(`TextReflower.reflow`를
   `Dispatchers.Default`에서 재실행)를 호출하는 별도 경로로 일어납니다 — 설정 변경과 재계산이 한
   함수 호출로 안 묶여 있고 상태 흐름 관찰로 분리돼 있다는 걸 알아야 여기서 안 헤맵니다.
5. **화면 꺼짐 방지 / 화면 방향 고정 토글**: `setKeepScreenOnEnabled(value)` / `setOrientationLock(value)`도
   설정만 저장하고, 실제 적용은 `ReaderScreen`의 `DisposableEffect(settings.keepScreenOnEnabled)` /
   `DisposableEffect(settings.orientationLock)`가 값 변화를 감지해 각각
   `Window.addFlags(FLAG_KEEP_SCREEN_ON)`/`clearFlags`, `Activity.requestedOrientation`을 직접
   조작 — 화면을 벗어나면(`onDispose`) 둘 다 원래 상태로 되돌림

## 12. 자동 페이지 넘김(타이머) 켜기 (FEATURES.md §14)

1. `QuickSettingsSheet`에서 `autoAdvanceMode = TIMER` 선택 → `tts/AutoPageTurnController.kt`의
   `start(intervalSeconds)` 호출
2. 코루틴이 설정한 간격마다 `ReaderViewModel.next()`를 직접 호출 — 4번 시나리오와 동일한 경로가
   자동으로 반복됨
3. 모드를 벗어나면(`OFF`/`TTS`로 전환) `AutoPageTurnController.stop()`

## 13. TTS 켜기 → 낭독 (FEATURES.md §14)

1. `autoAdvanceMode = TTS` 선택 → `ReaderViewModel`이 현재 오프셋부터 500자(`ttsChunkChars`)씩 잘라
   `tts/TtsController.kt`의 `speak(chunk)` 호출
2. utterance 완료 콜백 → 그 청크 끝 오프셋으로 `jumpToOffset` → 다음 청크를 이어서 낭독(페이지도
   같이 따라 넘어감)
3. 모드가 TTS가 아니게 되면 `ttsController.stop()`

## 14. 화면을 벗어남(백그라운드 / 뒤로가기) → 읽기 위치 저장 (FEATURES.md §4)

1. 오프셋이 바뀔 때마다 `updateCurrentOffset`이 500ms 디바운스로 `pendingWriteOffset`을 예약
2. `ReaderScreen`의 Lifecycle observer가 `ON_STOP`을 받으면 디바운스를 기다리지 않고
   `flushPendingPosition()` 호출 → `BookRepository.updateReadPosition`으로 Room에 즉시 저장
3. 뒤로가기 등으로 `ReaderViewModel`이 소멸(`onCleared`)하면 `viewModelScope`가 곧 취소되므로
   `runBlocking`으로 마지막 위치를 한 번 더 강제 저장
   > ⚠️ `onCleared`에서만 쓰는 예외적인 패턴입니다. 평소처럼 `launch`를 쓰면 저장이 시작도 못 하고
   > 스코프가 취소돼버려서 여기만 일부러 블로킹으로 처리합니다 — 다른 곳에서 따라 하면 메인 스레드를
   > 막는 안티패턴이 됩니다.
4. VSCode 동기화가 켜져 있으면 같은 시점에 `syncNowToRemote()`도 호출됨(15번 시나리오의 7번)

## 15. VSCode 읽기 위치 동기화 (FEATURES.md §15, 기본 꺼짐)

**QR로 연결 (시크릿 수동 입력의 대안)**

0. VSCode에서 `showPairingQr` 커맨드 실행(팔레트 또는 상태 표시줄) → 기존 시크릿이 있으면 재사용,
   없으면 새로 생성해서 `{"type":"vscode_sync","secret":"..."}` QR을 웹뷰에 표시
1. `QuickSettingsSheet`의 "QR로 연결" 탭 → `ui/qr/QrScannerDialog.kt`(CameraX + ML Kit)로 QR 스캔 →
   `QrPairingPayload.parse`가 `VscodeSync(secret)`로 파싱 → 시크릿 입력칸에 자동 채움 → 아래 1번과
   동일하게 바로 연결 테스트 진행. 파싱 실패(모르는 QR, 필드 누락)면 `null`을 돌려줄 뿐이라 스캐너가
   계속 다음 프레임을 스캔하거나 사용자가 수동 입력으로 전환할 수 있습니다.

**연결 테스트**

1. `QuickSettingsSheet`의 "VSCode 읽기 위치 동기화" 영역에서 공유 시크릿 입력(또는 위 QR 스캔으로
   자동 채움) → "연결 테스트" 탭
2. `ReaderViewModel.testSupabaseConnection(secret)` → `data/sync/ReadingPositionSyncClient.kt`의
   `testConnection()`이 더미 경로로 upsert를 시도 → 2xx면 성공. 성공 시
   `ReaderSettingsRepository.updateSupabaseSharedSecret(secret, verifiedSecret = secret)`로
   시크릿과 검증 상태를 함께 커밋.
   > ⚠️ **실패하면**(시크릿이 틀려 RLS가 401/403으로 거부, 네트워크 없음 등) `testConnection()`이
   > `false`를 돌려줄 뿐 예외로 튀지 않습니다(`runCatching { ... }.getOrDefault(false)`). 아무것도
   > 저장되지 않아 `supabaseSharedSecret != supabaseVerifiedSecret` 상태 그대로 남고, 이후 3~7번의
   > 원격 조회/반영 경로는 `syncClientOrNull`이 매번 null을 돌려주며 전부 조용히 스킵됩니다 —
   > 기능이 켜진 적이 없는 것과 동일하게 동작해서 로컬 읽기 흐름엔 영향이 없습니다.

**책을 열 때 / 화면 재진입 시 원격 조회**

3. 3번 시나리오의 `loadBook`, 또는 `ReaderScreen`의 `ON_START`(`onReaderResumed`) →
   `checkRemoteAndMaybeNotify(relativePath, localOffset, settings)`
4. `syncClientOrNull`이 시크릿 검증 상태를 확인(검증 안 됐으면 조용히 스킵) →
   `ReadingPositionSyncClient.fetch(relativePath)`로 원격 오프셋 조회
5. 원격이 로컬보다 500자(`minOffsetDiffToNotify`) 넘게 앞서 있으면 `_uiState.externalFurtherOffset`
   세팅 → `ReaderScreen`이 "더 멀리 읽었습니다" 다이얼로그 표시. "이동" 탭 →
   `jumpToExternalPosition()` → `jumpToOffset`(6번 시나리오의 4번과 동일 경로)

**로컬에서 읽는 동안 원격에 반영**

6. 오프셋이 바뀔 때마다 `scheduleRemoteSyncCheckpoint(offset)`가 1분 유휴 타이머를 재설정 — 타이머가
   끝까지 살아남으면 `pushRemoteSync` → `ReadingPositionSyncClient.upsert` 호출
7. 화면을 벗어나는 시점(14번 시나리오)에는 타이머를 기다리지 않고 `syncNowToRemote()`가 즉시
   `pushRemoteSync`를 호출

## 16. PC 파일 동기화 (FEATURES.md §16, 기본 꺼짐)

**PC 서버 준비 (PC 쪽, Go)**

1. 사용자가 `moonkata-sync-server.exe` 실행 → 먼저 `single_instance_windows.go`가 이름 있는 뮤텍스로
   이미 실행 중인 인스턴스가 있는지 확인(있으면 알림만 띄우고 조용히 종료) → `main.go`가 설정 로드/
   시크릿 생성(`config.go`) → `tls.go`의 `loadOrCreateTLSCertificate`가 자체 서명 인증서 준비 →
   HTTPS 리스너를 `:58221`에 기동(`server.go`, `/pair` 포함) → `tray.go`가 트레이 아이콘과 "실행 중"
   알림(공유 폴더 + 시크릿, 클립보드에도 복사)을 표시. 모든 트레이 알림은 `showNotification`(Windows
   우측 하단 토스트)이라 확인을 누르지 않아도 서버는 계속 응답합니다 — 예전 `MessageBox` 모달처럼
   프로그램이 멈춘 것처럼 보이지 않습니다.

**QR로 연결 (호스트 찾기 + 시크릿 입력의 대안)**

2. PC 트레이 메뉴 "동기화 QR 보기" → `handleShowPairingQr`가 기본 브라우저로
   `https://127.0.0.1:{port}/pair`를 엶 → `pair.go`의 `handlePair`가 `localLanIP()`로 LAN IP를 추정하고
   호스트+시크릿+인증서 지문을 담은 `{"type":"pc_sync","host":...,"secret":...,"fingerprint":...}` QR
   PNG를 즉석에서 생성해 보여줌(이 엔드포인트는 인증 불필요 — QR 자체가 곧 인증 정보라서)
3. 안드로이드 `PcSyncSheet`의 "QR로 연결" 탭 → 같은 `QrScannerDialog`로 스캔 →
   `QrPairingPayload.PcSync`로 파싱 → 호스트/시크릿/지문을 한 번에 채우고, 지문까지 이미 받았으므로
   lenient TLS로 먼저 찔러보는 TOFU 단계 없이 곧바로 지문 고정(pinned) TLS로 연결 테스트를 진행

**PC 찾기 · 연결 테스트 (수동 경로, 안드로이드 쪽)**

4. `ui/library/PcSyncSheet.kt`에서 "PC 찾기" 탭 → `LibraryViewModel.scanForPcSyncHosts()` →
   `data/sync/PcHostScanner.kt`의 `scanLocalSubnet()`이 로컬 `/24` 대역 254개 후보를 64개씩 병렬로
   `PcSyncClient.isPcSyncServer(candidate)`(신뢰 검증 없는 lenient TLS로 `/ping` 호출) 시도 → 응답
   본문에 `"moonkata-sync-server"`가 있으면 후보로 채택. 아무것도 못 찾으면(다른 서브넷, PC 서버
   미실행, 방화벽 등) `scanLocalSubnet()`이 그냥 빈 리스트를 돌려줄 뿐 예외를 던지지 않습니다 — 호스트는
   수동 입력으로 계속 진행 가능
5. 호스트 + 시크릿 입력 후 "연결 테스트" → `LibraryViewModel.testPcSyncConnection` →
   `PcSyncClient(host, secret).testConnection()`이 lenient TLS로 `/list`를 호출(시크릿 헤더 포함) →
   성공하면 그 순간 받은 인증서 지문(`data/sync/PcTlsTrust.kt`의 `sha256Fingerprint`,
   `client.lastSeenFingerprint`)까지
   `ReaderSettingsRepository.updatePcSyncConnection(..., verified = true, fingerprint = ...)`로
   함께 저장 — TOFU(trust-on-first-use) 방식. 실패하면(호스트/시크릿 공백, PC 응답 없음, 시크릿
   불일치로 401) `testPcSyncConnection`이 `false`만 돌려주고 아무것도 저장하지 않아, "지금 동기화"는
   아래 6번에서 검증 안 된 상태로 막힙니다. (QR 경로에서는 3번이 이미 이 저장까지 끝내므로 5번을
   건너뜁니다.)

**지금 동기화**

6. "지금 동기화" 탭 → `LibraryViewModel.syncFromPc()` — 먼저 라이브러리 폴더가 선택돼 있는지
   확인(없으면 `pcSyncState.errorMessage`에 "먼저 라이브러리 폴더를 선택하세요"로 즉시 중단), 그 다음
   호스트/시크릿이 마지막 연결 테스트 성공 값과 정확히 같은지 재확인(하나라도 다르면 "먼저 연결
   테스트를 통과해야 합니다"로 중단 — 예를 들어 연결 테스트 이후 시크릿 입력칸만 다시 고친 경우)
7. `PcSyncClient(host, secret, pinnedFingerprint)`(이번엔 지문 고정 TLS)를 생성 →
   `data/sync/PcSyncFileManager.kt`의 `sync(treeUri)` 호출
8. `client.listFilesRecursively()`로 원격 파일 목록(`/list`), `data/sync/LocalLibraryScanner.kt`의
   `scanRecursively(treeUri)`로 로컬 SAF 트리 전체를 각각 조회
9. `computeSyncDelta(remote, local)`(I/O 없는 순수 함수, `data/sync/RelativePath.kt`의
   `normalizeRelativePath`로 매칭 키 정규화)가 `toWrite`/`toDelete`를 계산 — 원격에만 있거나 크기가
   다르면 `toWrite`, 로컬에만 있으면 `toDelete`.
   > ⚠️ 수정시각은 비교하지 않고 크기만 봅니다. 다운로드한 로컬 파일의 수정시각은 "받은 시점"이 돼
   > PC 원본 시각을 못 물려받기 때문에, 수정시각을 비교에 넣으면 안 바뀐 파일도 매 동기화마다 다시
   > 받아버리는 버그가 실제로 있었습니다.
10. `toWrite`의 각 파일: ⚠️ 기존 로컬 파일이 있으면 `writeIntoExisting`으로 **같은 `documentUri`를
   유지한 채 내용만 덮어씁니다** — 지우고 새로 만들면 `BookEntity`가 그 URI로 참조하던 읽기 위치
   기록이 고아가 됩니다. 없으면 `writeNewFile`(`resolveOrCreateFolder`로 하위 폴더를 만든 뒤 새
   문서 생성). 둘 다 `client.downloadFile(relativePath, outputStream)`로 `/file?path=...` 응답을
   그대로 스트리밍
11. `toDelete`의 각 파일: `DocumentFile.fromSingleUri(...).delete()`
12. 진행 상황은 `PcSyncProgress`로 `LibraryViewModel.pcSyncState`에 실시간 반영 → `PcSyncSheet`가
    진행률로 표시 → 완료 시 `PcSyncResult`(받음/갱신/삭제/실패 건수)로 교체되고 `loadCurrent()`가
    서재 목록을 새로고침

**실패 두 종류**

- **원격 목록 조회 자체가 실패**(8번 단계, PC가 꺼졌거나 네트워크가 끊김): `listFilesRecursively()`가
  `null`을 돌려주고 `PcSyncFileManager.sync()`도 그대로 `null`을 리턴 → `syncFromPc()`가
  `pcSyncState.errorMessage`에 "PC에 연결할 수 없습니다"를 세팅하고 종료 — 이번엔 아무 파일도
  건드리지 않습니다(부분 반영 없음).
- **개별 파일 단위 실패**(9~11번 단계 도중 특정 파일만 다운로드/삭제 실패): 그 파일만
  `PcSyncResult.failed`에 집계되고 나머지 파일은 계속 처리됩니다 — 동기화 전체가 중단되지 않습니다.

---

## 17. 서재 화면에서 바로 설정 열기 (FEATURES.md §1, §12)

**무엇이 달라졌나**: 예전엔 앱 설정(퀵설정/폰트/챕터패턴)을 리더 화면에서만 열 수 있어서, 책을 한
권도 안 연 상태에서는 접근할 방법이 없었습니다. 지금은 서재 화면 상단 바 오른쪽에 PC 동기화(§16)·
정렬·설정 아이콘 3개가 있어 책을 열지 않고도 곧장 열립니다.

1. 서재 상단 바의 설정 아이콘 탭 → `LibraryScreen.kt`가 `QuickSettingsSheet`를 띄우되, 이번엔
   `SettingsController` 구현으로 `ReaderViewModel`이 아니라 `LibraryViewModel`을 넘김
2. 시트 안에서 값을 바꾸면(예: 폰트 크기) `LibraryViewModel`의 `SettingsController` 구현이
   `ReaderSettingsRepository`에 바로 저장만 합니다 — `ReaderViewModel` 구현과 달리 재페이지네이션
   같은 세션 중 부수효과를 트리거할 게 없습니다(리더가 열려 있지 않으므로).
3. 정렬 아이콘 → `LibraryViewModel.setSortOption`으로 즉시 반영, PC 동기화 아이콘 → 16번 시나리오의
   `PcSyncSheet` 진입점과 동일한 흐름으로 이어집니다.

> `LibraryScreenSettingsAccessTest`가 이 경로로 연 시트에서 바꾼 값이 실제로 DataStore에 저장되는지
> 검증합니다.
