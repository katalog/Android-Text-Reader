# 사용자 시나리오별 코드 실행 흐름

`FEATURES.md`가 기능별로 "이 기능은 이 파일들로 구현되어 있다"를 정리한 카탈로그라면, 이 문서는 반대
방향입니다 — "사용자가 이렇게 조작하면 어떤 파일의 어떤 함수가 어떤 순서로 실행되는가"를 시나리오
단위로 추적합니다. 코드를 처음 보는 사람이 특정 동작 하나를 따라가며 전체 흐름을 파악할 때 쓰는 용도로,
설계 배경(왜 이렇게 만들었는지)은 각 시나리오 끝에 붙은 `FEATURES.md` 절 번호를 참고하세요.

파일 경로는 모두 `app/src/main/java/com/moonkata/textreader/` 기준 상대경로이고, PC 서버 쪽은
`external_library/sync_server/` 기준입니다.

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

## 2. 앱 재실행 → 이어서 읽기 다이얼로그 (FEATURES.md §2)

1. `LibraryViewModel`의 `observeLibrary()`가 Room `BookDao`를 최근순으로 조회 → 첫 항목에
   `lastOpenedAt`이 있으면 `resumeCandidate`에 세팅
2. `LibraryScreen`이 `resumeCandidate`를 관찰해 `ResumeReadingDialog` 표시
3. "이어서 읽기" 탭 → `reader/{bookId}`로 네비게이션(아래 3번 시나리오로 이어짐)
4. "취소" → `resumeCandidate`만 비우고 서재 화면 유지

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
   - `redetectChapters(settings)`를 백그라운드로 트리거(첫 페이지 표시를 막지 않음) —
     `data/parser/ChapterDetector.kt`의 `detect` 결과가 나중에 `chapters`에 채워짐
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
3. **챕터 점프 모드가 켜져 있으면**(`ReaderSettings.chapterJumpEnabled`): `ChapterJumpNavigator`가
   계산한 breakpoint 오프셋 목록 중 현재 위치 기준 다음/이전 지점을 골라 `jumpToPageAt(target)`으로
   이동 — 방문 이력 스택 경로를 타지 않는다. 같은 지점을 다시 잡지 않도록 마지막으로 실제 이동한
   목표 오프셋을 `lastChapterJumpOffset`에 따로 기억해둔다(9번 시나리오 참고).
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
2. 검색어 입력 후 검색 버튼 또는 IME 검색 액션(타이핑만으로는 실행되지 않음) →
   `ReaderViewModel.search(query)` → `fullText`에 대소문자 무시 `indexOf` 반복(최대 200건) →
   `model/SearchResult.kt` 리스트 생성, 스니펫은 매칭 전후 20자
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
4. VSCode 동기화가 켜져 있으면 같은 시점에 `syncNowToRemote()`도 호출됨(15번 시나리오의 7번)

## 15. VSCode 읽기 위치 동기화 (FEATURES.md §15, off by default)

**연결 테스트**

1. `QuickSettingsSheet`의 "VSCode 읽기 위치 동기화" 영역에서 공유 시크릿 입력 → "연결 테스트" 탭
2. `ReaderViewModel.testSupabaseConnection(secret)` → `data/sync/ReadingPositionSyncClient.kt`의
   `testConnection()`이 더미 경로로 upsert를 시도 → 2xx면 성공. 성공 시
   `ReaderSettingsRepository.updateSupabaseSharedSecret(secret, verifiedSecret = secret)`로
   시크릿과 검증 상태를 함께 커밋

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

## 16. PC 파일 동기화 (FEATURES.md §16, off by default)

**PC 서버 준비 (PC 쪽, Go)**

1. 사용자가 `moonkata-sync-server.exe` 실행 → `main.go`가 설정 로드/시크릿 생성(`config.go`) →
   `tls.go`의 `loadOrCreateTLSCertificate`가 자체 서명 인증서 준비 → HTTPS 리스너를 `:58221`에
   기동(`server.go`) → `tray.go`가 트레이 아이콘과 "실행 중" 알림(공유 폴더 + 시크릿, 클립보드에도
   복사)을 표시

**PC 찾기 · 연결 테스트 (안드로이드 쪽)**

2. `ui/library/PcSyncSheet.kt`에서 "PC 찾기" 탭 → `LibraryViewModel.scanForPcSyncHosts()` →
   `data/sync/PcHostScanner.kt`의 `scanLocalSubnet()`이 로컬 `/24` 대역 254개 후보를 64개씩 병렬로
   `PcSyncClient.isPcSyncServer(candidate)`(신뢰 검증 없는 lenient TLS로 `/ping` 호출) 시도 → 응답
   본문에 `"moonkata-sync-server"`가 있으면 후보로 채택
3. 호스트 + 시크릿 입력 후 "연결 테스트" → `LibraryViewModel.testPcSyncConnection` →
   `PcSyncClient(host, secret).testConnection()`이 lenient TLS로 `/list`를 호출(시크릿 헤더 포함) →
   성공하면 그 순간 받은 인증서 지문(`data/sync/PcTlsTrust.kt`의 `sha256Fingerprint`,
   `client.lastSeenFingerprint`)까지
   `ReaderSettingsRepository.updatePcSyncConnection(..., verified = true, fingerprint = ...)`로
   함께 저장 — TOFU(trust-on-first-use) 방식

**지금 동기화**

4. "지금 동기화" 탭 → `LibraryViewModel.syncFromPc()` — 호스트/시크릿이 마지막 연결 테스트 성공 값과
   정확히 같은지 재확인 후 진행
5. `PcSyncClient(host, secret, pinnedFingerprint)`(이번엔 지문 고정 TLS)를 생성 →
   `data/sync/PcSyncFileManager.kt`의 `sync(treeUri)` 호출
6. `client.listFilesRecursively()`로 원격 파일 목록(`/list`), `data/sync/LocalLibraryScanner.kt`의
   `scanRecursively(treeUri)`로 로컬 SAF 트리 전체를 각각 조회
7. `computeSyncDelta(remote, local)`(I/O 없는 순수 함수, `data/sync/RelativePath.kt`의
   `normalizeRelativePath`로 매칭 키 정규화)가 `toWrite`/`toDelete`를 계산 — 원격에만 있거나 크기가
   다르면 `toWrite`, 로컬에만 있으면 `toDelete`(수정시각은 비교하지 않음)
8. `toWrite`의 각 파일: 기존 로컬 파일이 있으면 `writeIntoExisting`(같은 `documentUri`를 유지한 채
   내용만 덮어씀 — `BookEntity`가 그 URI로 읽기 위치를 참조하므로), 없으면
   `writeNewFile`(`resolveOrCreateFolder`로 하위 폴더를 만든 뒤 새 문서 생성) — 둘 다
   `client.downloadFile(relativePath, outputStream)`로 `/file?path=...` 응답을 그대로 스트리밍
9. `toDelete`의 각 파일: `DocumentFile.fromSingleUri(...).delete()`
10. 진행 상황은 `PcSyncProgress`로 `LibraryViewModel.pcSyncState`에 실시간 반영 → `PcSyncSheet`가
    진행률로 표시 → 완료 시 `PcSyncResult`(받음/갱신/삭제/실패 건수)로 교체되고 `loadCurrent()`가
    서재 목록을 새로고침
