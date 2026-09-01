# 계획: VSCode ↔ 앱 읽기 위치 동기화 (Supabase 기반)

**상태**: 계획 확정, 구현 시작 전. 열린 질문 6개 모두 결론 남(§열린 질문 각 항목 참고). 다음 단계는
스테이지 1(Supabase 설정) 착수.

## 배경

같은 `.txt` 소설을 PC(VSCode)와 안드로이드(이 앱) 양쪽에서 읽는다. 두 기기는 Syncthing-Fork로 폴더를
동기화하고 있고, 이 앱이 서재로 열고 있는 SAF 폴더가 곧 그 동기화 폴더다. 문제는 어느 쪽에서 얼마나
읽었는지가 서로 안 보여서, 매번 스크롤/검색으로 마지막 위치를 다시 찾아야 한다는 것.

**로컬 사이드카 파일 방식은 폐기.** 책 파일 옆에 `.readpos.json` 같은 파일을 두는 초안을 먼저 짰었는데,
탐색기/이 앱의 파일 목록이 지저분해지는 문제가 있어 대신 무료 클라우드(Supabase)에 위치 데이터를
두는 방식으로 변경한다.

## 목표 (이번 범위)

**완전 양방향.** 앱과 VSCode 둘 다 자기 위치를 클라우드에 올리고, 둘 다 책을 열 때(앱) / 파일을 열거나
포커스할 때(VSCode) 상대방 위치와 비교해서 — 어느 쪽이든 자기보다 상대가 더 멀리 읽었으면 — 팝업/알림을
띄우고, 확인하면 더 먼 쪽(둘 중 최댓값) 위치로 점프한다. 즉 앱 쪽 UI와 VSCode 쪽 UI를 대칭적으로 둘 다
만든다 — 앱 팝업만 만들고 VSCode 쪽은 데이터만 쌓아두던 이전 초안에서 범위를 넓힘.

## 전체 구조

```
[VSCode 확장]  <--HTTP(REST, PostgREST)-->  Supabase (Postgres: reading_positions 테이블)  <--HTTP(REST)-->  [이 앱]
      │  읽기+쓰기+알림/점프                                                          읽기+쓰기+팝업/점프  │
```

무거운 Supabase SDK 대신 PostgREST(Supabase가 테이블마다 자동으로 열어주는 REST API)를 직접 호출하는
가벼운 방식을 쓴다 — 이 프로젝트가 DI 프레임워크 없이 손수 구성하는 기조와도 맞고, VSCode 확장 쪽도
`fetch` 하나면 충분해서 별도 SDK 의존성이 필요 없다.

## 공통 설계

아래 §1~§3은 스테이지에 상관없이 먼저 확정해야 하는 공통 스펙이다. 실제 구현 순서는 뒤의 "페이즈별
구현 계획" 참고.

## 1. Supabase 설정

- 새 프로젝트 하나, 테이블 `reading_positions`:

  | 컬럼 | 타입 | 설명 |
  |---|---|---|
  | `relative_path` | text (PK) | 동기화 루트 기준 상대 경로, 예: `novels/무정.txt` |
  | `char_offset` | integer | 문자 오프셋 (원본 디코딩 텍스트 기준, §2 참고) |
  | `source` | text | 마지막으로 쓴 주체 — `vscode` \| `android` |
  | `encoding` | text, nullable | 오프셋 계산에 쓴 인코딩 (참고/디버깅용) |
  | `updated_at` | timestamptz | 기본값 `now()` |

- **보안 — RLS + 커스텀 시크릿**: `anon` key는 공개 저장소의 VSCode 확장 소스나 디컴파일 가능한 APK에
  노출될 수 있는 값이라, RLS(Row Level Security)를 켜고 커스텀 시크릿(요청 헤더 등)을 검사하는 정책을
  둔다 — 데이터 자체는 민감도가 낮지만(그냥 소설 읽은 위치), 아무나 읽고 쓸 수 있는 상태는 피한다.

- **시크릿 관리 (§열린 질문 3 결론) — 빌드에 굽지 않고 설정 화면에서 입력받는다.** 처음엔 릴리스
  키스토어처럼 빌드 시점에 CI 시크릿으로 주입하는 방식을 계획했는데, 그러면 컴파일된 APK/vsix
  안에 값이 그대로 남아(키스토어 비밀번호는 서명에만 쓰이고 결과물엔 안 남는 것과 달리, Supabase 값은
  앱이 런타임에 그 값으로 API를 호출해야 하므로 바이너리에 박힘) 디컴파일하면 누구나 뽑아낼 수 있다는
  한계가 있었다 — 그래서 **아예 빌드에 넣지 않고, 앱/확장을 설치한 뒤 설정 화면에서 직접 입력받는
  방식으로 변경.** 값이 없으면 원래 계획대로 기능이 조용히 비활성화되고(§4/§5), 입력하는 순간부터
  동작한다.
  - **UI/구현 복잡도는 낮다** — 오히려 이전 계획(CI 레포 시크릿 + 릴리스 워크플로우 주입)보다 단순해짐:
    - **안드로이드**: 기존 설정 화면 계열(`QuickSettingsSheet.kt` 근처, 혹은 별도 "동기화 설정" 섹션)에
      텍스트 필드 3개(Supabase URL / anon key / 공유 시크릿) 추가하고 DataStore에 저장 — 앱이 폰트·여백·
      테마 같은 다른 설정을 다루는 것과 완전히 같은 패턴이라 새로 익힐 것이 없다.
    - **VSCode**: 오히려 더 쉽다 — `package.json`의 `contributes.configuration`에 설정 3개만 선언하면
      VSCode 기본 설정 화면이 자동으로 생겨서 커스텀 UI 코드 자체가 필요 없다(`vscode-chapter-jumper`의
      `configuration` 항목과 같은 패턴). 다만 시크릿 값 자체는 평범한 텍스트 설정(설정.json에 평문 저장,
      Settings Sync로 클라우드에도 올라갈 수 있음)보다는 **`vscode.SecretStorage` API**(OS 자격 증명
      저장소에 암호화 저장)로 받는 게 맞다 — 입력받는 커맨드 하나(`showInputBox({ password: true })` →
      `context.secrets.store(...)`)만 추가하면 되는 표준 패턴이라 어렵지 않다. URL처럼 민감하지 않은
      값은 일반 설정으로 둬도 무방.
  - **CI 시크릿 주입 파이프라인은 더 이상 필요 없다** — `vscode-moonkata-sync`에 릴리스 워크플로우를
    새로 만들 필요도, `Android-Text-Reader`/`vscode-moonkata-sync`에 레포 시크릿을 등록할 필요도
    없어짐(계획이 단순해짐). `S:\DATA\Dev\Git-Privates\Keys`는 여전히 유용하다 — 개발자 본인이 로컬
    개발 중이거나, 앱/확장을 새로 설치했을 때 설정 화면에 붙여넣을 값을 보관해두는 개인 사본 용도로.
  - **진짜 보안 이득**: 공개 배포되는 APK/vsix 자체에는 이 값들이 전혀 들어가지 않으므로, 디컴파일해도
    아무것도 안 나온다 — 이전 방식의 "컴파일된 결과물 안에 남는다"는 한계가 완전히 해소된다.
  - **트레이드오프**: 이 앱을 공개 저장소에서 받아 설치하는 다른 사람들은 이 3개 값을 알 방법이 없으니
    (개발자 개인 Supabase 프로젝트의 자격 증명이라 공유하지 않음), 그 사람들에게는 이 동기화 기능이
    그냥 계속 꺼진 채로 남는다 — 원래 이 기능이 개발자 본인의 PC/폰 두 기기용으로 만드는 것이라 이 정도
    제약은 문제 되지 않는다.

- **무료 티어 용량 검토**: 이 테이블은 로그가 아니라 책마다 행 하나를 upsert하는 구조라 저장 용량(500MB)은
  사용자가 아무리 늘어도 사실상 문제되지 않는다(수천 명이 써도 수십 MB 수준). 진짜 변수는 월 5GB
  egress인데, 아주 활발한 사용자를 넉넉히 잡아도(하루 2시간, 3초마다 위치 갱신) 월 34명 선에서야
  한계에 닿는 계산이 나오고, 실사용 빈도로는 훨씬 여유롭다 — 이 기능을 실제로 켜서 쓸 사람 규모를
  생각하면 사용자 수로 한계에 걸릴 가능성은 낮다. **오히려 반대 방향 리스크가 실질적이다**: 무료
  프로젝트는 7일간 API 호출이 없으면 자동으로 일시정지되는데, 사용자가 적고 뜸한 이 기능 특성상 아무도
  그 주에 안 쓰면 프로젝트가 멈추고 수동으로 대시보드에서 재개해야 하는 상황이 생길 수 있다 — 필요하면
  GitHub Actions로 주기적 healthcheck ping을 날리는 완화책을 고려할 수 있으나, 그 자체가 별도로
  유지보수해야 할 트레이드오프라 지금은 "알려진 리스크"로만 남겨둔다.

## 2. 오프셋 정의 (기존 계획과 동일)

`char_offset`은 파일을 텍스트로 디코딩한 상태(원본 그대로, 줄바꿈 재구성 전) 기준 0-베이스 문자
인덱스 — `ReaderViewModel.fullText`(= `BookRepository.openBookContent(book).text`, `TextReflower`
적용 전 원문)와 정의가 같아야 `BookEntity.lastReadCharOffset`과 바로 비교 가능하다. VSCode 쪽도 반드시
파일을 디코딩한 문자열 기준 오프셋을 써야 한다.

## 3. 상대 경로(relative_path) — 두 기기를 매칭하는 키

지금 앱은 파일별 상대 경로를 저장하지 않는다(`BookEntity`엔 `documentUri`만 있음, `BookEntity.kt:13`).
새 필드와 마이그레이션이 필요하다.

- **확보 시점**: `LibraryViewModel`이 폴더를 탭해서 내려갈 때 이미 `BrowseLocation` 스택(`path`)을
  들고 있다(`LibraryViewModel.kt:38`). 파일을 탭해 `findOrCreateBook`을 호출하는 지점
  (`LibraryViewModel.kt:167`)에서 이 스택의 폴더 이름들을 `/`로 이어붙이고 파일명을 더해 상대 경로를
  만들어 함께 넘긴다.
- **전제**: 안드로이드에서 고른 SAF 루트 폴더가 Syncthing 동기화 루트와 같아야 상대 경로가 VSCode
  쪽 경로와 정확히 일치한다 — 사용자가 이미 그렇다고 확인함.
- **VSCode 쪽은 워크스페이스에 기대지 않는다 (재검토 후 변경)**: 처음엔 `vscode.workspace.asRelativePath(document.uri)`로
  워크스페이스 루트 기준 상대 경로를 얻으려 했는데, 이건 **동기화 폴더 자체를 워크스페이스(폴더)로 열어야만**
  성립한다. 사용자가 폴더를 열지 않고 파일 하나만 단독으로 열 수도 있는데, 그 경우 열려 있는
  워크스페이스가 없어 `asRelativePath`가 기준을 못 잡고 절대경로를 그대로 돌려준다 — 매칭이 깨짐.
  그래서 대신 **확장 설정에 동기화 루트 절대경로를 명시적으로 저장**해두고(`moonkataSync.syncRootPath`,
  최초 실행 시 한 번 입력받거나 워크스페이스 폴더가 열려 있으면 그걸 기본값으로 제안), 파일을 열 때마다
  `path.relative(설정된 루트, document.uri.fsPath)`로 상대 경로를 계산한다 — 워크스페이스가 열려 있든
  파일 하나만 열려 있든 항상 같은 방식으로 계산되고, 안드로이드가 SAF 루트(사용자가 명시적으로 고른
  루트)를 기준으로 상대 경로를 만드는 것과도 대응 구조가 같아진다.
  - 계산된 경로가 루트 바깥을 가리키면(`..`로 시작하는 등) 동기화 대상이 아닌 파일로 보고 이 기능
    자체를 조용히 건너뛴다(팝업/저장 안 함).
- **정규화 규칙 (확정)**: git/rsync/Unison 등 기존 동기화 도구들의 관례를 따라 아래 순서로 변환한 값을
  Supabase 키로 쓴다 — 양쪽 클라이언트가 반드시 동일한 함수를 가져야 한다.
  1. 경로 구분자를 `/`로 통일 (Windows `\` → `/`)
  2. 유니코드 NFC 정규화 (Kotlin: `Normalizer.normalize(s, Normalizer.Form.NFC)`, TS/JS:
     `s.normalize('NFC')`) — Syncthing 자체의 `autoNormalize`(기본 켜짐)가 NFD로 들어온 파일명을 스캔
     시 이미 NFC로 고쳐 저장해주므로 이중 안전장치 성격에 가깝다.
  3. 전체를 소문자화 — Windows(NTFS, 대소문자 무시)와 안드로이드(보통 ext4/F2FS, 대소문자 구분)가
     섞여 있는 조합이라, Unison이 대소문자 구분 안 하는 파일시스템이 하나라도 있으면 비교 키를
     case-fold하는 것과 같은 방식을 택함.
  - 예: `상대경로.replace('\\', '/').normalize('NFC').toLowerCase()`
  - 파일 식별자로 별도 해시/ID 없이 경로 자체를 identity로 쓰는 것도 git/rsync/Unison과 같은 선택.
- **zip 안 파일은 제외**: `BookSource.ZipEntryTxt`는 VSCode에서 직접 열리는 경로가 아니므로 매칭
  대상에서 스킵.
- **기존에 이미 등록된 책들**은 `relativePath`가 비어있을 수 있음 — 강제 백필 스크립트 없이, 다음에
  서재 목록에서 그 파일을 다시 탭할 때(`findOrCreateBook` 재호출) 자연스럽게 채워지도록 둔다(§열린
  질문에 이 방식으로 충분한지 별도로 남김).

## 4. 안드로이드 앱 변경

- `BookEntity`에 `relativePath: String` 추가, Room 마이그레이션 2→3 (기존 `MIGRATION_1_2` 패턴 그대로
  따름, `AppDatabase.kt`).
- REST 클라이언트 하나 추가(예: `data/sync/ReadingPositionSyncClient.kt`) — OkHttp 등으로 PostgREST
  엔드포인트에 직접 GET/PATCH(upsert)만 하면 되므로 가볍게.
- **설정 화면**: Supabase URL / anon key / 공유 시크릿을 입력받는 텍스트 필드 3개를 설정 UI에 추가하고
  DataStore(`ReaderSettingsRepository`)에 저장(§1 "시크릿 관리" 참고). 세 값이 모두 채워져야 이 기능이
  켜짐.
- **읽기**: 책을 열 때(`ReaderViewModel.loadBook` 이후) `relativePath`로 조회 → 받아온 `char_offset`이
  `book.lastReadCharOffset`보다 크면 팝업 대상.
- **쓰기**: 앱이 이미 위치를 저장하는 지점(`schedulePositionWrite`/`flushPendingPosition`, 500ms
  디바운스)에 Supabase upsert 호출을 얹는다. 로컬 Room 저장과는 완전히 분리된 best-effort 호출 — 실패
  (오프라인 등)해도 로컬 저장/읽기 흐름에는 전혀 영향 없음.
- **팝업 UI**: 기존 "이어서 읽기" 다이얼로그(`ui/library/LibraryScreen.kt`의 `ResumeReadingDialog`)와
  같은 패턴. 확인 시 기존 `jumpToOffset` 재사용. 같은 프로세스에서 그 책에 대해 한 번 응답하면(확인이든
  취소든) 재노출 안 함.
- 네트워크 실패/설정 안 됨(Supabase 키 미설정 등)은 조용히 기능 비활성화 — 앱 기본 동작(오프라인)엔
  영향 없음. 설정 화면에서 세 값을 입력하는 순간부터 별도 재시작 없이 동작 시작.

## 5. VSCode 확장

- 완전히 분리된 별도 공개 저장소: [katalog/vscode-moonkata-sync](https://github.com/katalog/vscode-moonkata-sync)
  (현재는 계획 단계 플레이스홀더만 있음 — README/LICENSE/package.json 스켈레톤뿐, 실제 확장 코드는
  스테이지 3에서 작성).
- 대상: 동기화 폴더 안의 `.txt` 파일 — 워크스페이스로 열려 있든 파일 하나만 단독으로 열려 있든 동일하게
  동작해야 한다(§3 참고).
- 오프셋 계산: `document.offsetAt(editor.selection.active)`.
- 상대 경로: 설정(`moonkataSync.syncRootPath`)에 저장된 동기화 루트 절대경로 기준으로
  `path.relative(root, document.uri.fsPath)` 계산(§3). 설정이 비어있으면 `.txt` 파일을 처음 열 때
  `vscode.window.showOpenDialog({ canSelectFolders: true })`로 폴더 선택창을 띄워 고르게 하고(설정 직접
  입력보다 훨씬 덜 번거로움), 워크스페이스 폴더가 열려 있으면 그 경로를 다이얼로그의 시작 위치로 제안.
  선택한 경로는 `moonkataSync.syncRootPath`에 저장해 다음부터는 다시 안 물어봄(설정 화면에서 나중에
  변경 가능). 파일이 그 루트 바깥이면 기능을 건너뛴다.
- **설정**: `package.json`의 `contributes.configuration`에 `moonkataSync.supabaseUrl`(일반 설정)을
  선언하고, anon key/공유 시크릿은 `vscode.SecretStorage`로 받는다(커맨드 하나로
  `showInputBox({ password: true })` → `context.secrets.store(...)`, §1 "시크릿 관리" 참고). 세 값이
  모두 있어야 기능이 켜짐 — VSCode 설정 화면에 커스텀 UI를 따로 만들 필요는 없다.
- **쓰기**: 커서 이동마다 바로 올리면 API 호출이 너무 잦음 — 이 앱이 위치 저장에 쓰는 500ms 디바운스
  패턴과 동일하게 하고, 포커스를 잃거나 파일을 닫을 때 즉시 flush. Supabase REST(PostgREST) upsert
  호출 — `fetch` 하나로 충분, 별도 SDK 불필요.
- **읽기 & 비교**: `.txt` 파일을 열거나 에디터 포커스를 얻을 때 같은 `relative_path`로 조회해서, 받아온
  `char_offset`이 현재 커서 오프셋보다 크면 알림 대상.
- **알림/점프 UI**: `vscode.window.showInformationMessage`에 "이동" 액션 버튼을 붙여서, 누르면
  `editor.revealRange` + 커서를 그 오프셋(`document.positionAt(offset)`)으로 이동. 같은 파일을 계속
  열어두는 동안 반복 노출되지 않게, 마지막으로 응답한 오프셋을 세션 메모리에 기억해뒀다가 그보다 커진
  경우에만 다시 알림 (앱 쪽 "같은 프로세스에서 한 번만" 패턴과 대응).
- **인코딩 점검 & 변환 (§열린 질문 4 결론)**: 오프셋 비교보다 먼저, 파일을 열 때 원본 바이트가
  **유효한 UTF-8인지 엄격하게 검사**한다(청크 일부를 chardet류로 추측하는 게 아니라 디코딩 자체가
  깨지는지로 판단 — 오탐을 줄이기 위함, 예: `utf-8-validate` 같은 라이브러리로 바이트 시퀀스 검증).
  - 유효한 UTF-8이 아니면 `jschardet`/`chardet`(안드로이드의 juniversalchardet와 같은 부류)로 실제
    인코딩을 추정한 뒤, "이 파일은 UTF-8이 아닌 것 같습니다({추정 인코딩}). UTF-8로 변환할까요?" 알림을
    띄운다.
  - **변환을 선택하면**: 원본 바이트를 추정 인코딩으로 디코딩 → UTF-8로 재인코딩 → 파일에 그대로
    덮어쓴다. (VSCode가 이미 그 파일을 잘못된 인코딩으로 열어둔 상태일 수 있으므로, 디스크에 쓴 뒤
    문서를 다시 로드하도록 안내/트리거.) 이후 Syncthing이 이 변경분을 안드로이드 쪽에도 그대로
    퍼뜨리고, 안드로이드의 `EncodingDetector`도 UTF-8로 정상 인식하게 되므로 — **그 파일에 한해
    인코딩 불일치 문제가 근본적으로 해소**된다. 이미 UTF-8이 된 파일은 다음에 열 때 검사를 그냥
    통과하므로 반복해서 묻지 않는다.
  - **변환을 거절하면**: 원래 인코딩 그대로 두고, 그 파일에 한해 위치 동기화 팝업/점프만 비활성화
    (잘못된 위치로 튀는 사고를 막는 폴백 — Supabase에 올리는 `encoding` 필드를 안드로이드가 감지한
    인코딩과 비교해서 다르면 무시).
  - **리스크**: 파일을 실제로 덮어쓰는, 되돌리기 어려운 동작이다. 별도 `.bak` 파일은 만들지 않는 방향
    (§배경의 사이드카 파일 폐기 이유와 같은 맥락 — 폴더가 지저분해짐) — 대신 변환 확인 문구에 "원본
    인코딩으로는 되돌릴 수 없다"는 점을 명확히 알리는 정도로 처리. Syncthing 버전 관리 기능(활성화되어
    있다면)이 사실상의 되돌리기 수단이 될 수 있다는 점을 README/설정 안내에 남겨둔다.

## 페이즈별 구현 계획

### 스테이지 1 — Supabase 설정 + 값 가져오기

클라이언트(앱/확장) 코드를 하나도 안 짜고, 백엔드만 먼저 완성해서 검증한다.

1. Supabase 프로젝트 생성, `reading_positions` 테이블 생성 (§1 스키마)
2. RLS + 공유 시크릿 검사 정책 설정 (§1 보안) — 이 시크릿값 자체는 빌드에 안 넣고 앱/확장 설정 화면에서
   입력받을 값이므로(§1 "시크릿 관리"), 여기서는 정책만 만들어두고 실제 값은
   `S:\DATA\Dev\Git-Privates\Keys`에 개인 보관
3. ~~`relative_path` 정규화 규칙 확정~~ 완료 (§3 참고) — 스테이지 2/3 구현이 그대로 따르면 됨
4. curl/Postman 등으로 수동 upsert/select 호출을 날려서, 스키마·RLS·인증이 실제로 의도대로 동작하는지
   확인 (예: 가짜 `relative_path`로 행 하나 넣고 다시 읽어보기, 시크릿 없이 호출하면 거부되는지 확인)

**완료 기준**: 클라이언트 코드 없이도, curl 같은 도구로 그 프로젝트 테이블에 있는 값을 안정적으로
넣고 가져올 수 있다.

### 스테이지 2 — 안드로이드 앱 구현

스테이지 1에서 검증된 스펙을 그대로 따라 앱에 읽기+쓰기+팝업+점프를 전부 구현한다 (§4 상세).

1. `BookEntity.relativePath` 추가 + Room 마이그레이션(2→3) + `LibraryViewModel`에서 상대 경로 계산/전달
2. 설정 화면에 Supabase URL/anon key/공유 시크릿 입력 필드 추가 + DataStore 저장
3. 안드로이드 REST 클라이언트(`data/sync/ReadingPositionSyncClient.kt` 등)
4. 쓰기 — 기존 디바운스 위치 저장(`schedulePositionWrite`)에 Supabase upsert 연결
5. 읽기/비교 — 책 열 때 조회, 로컬 오프셋과 비교
6. 팝업 UI — "이어서 읽기" 다이얼로그 패턴 재사용, 확인 시 `jumpToOffset`

**완료 기준**: 앱만 갖고도(다른 안드로이드 기기나 Supabase 테이블을 수동으로 미리 채워둔 값 기준으로)
"더 먼 위치로 이동" 팝업과 점프가 실제로 동작한다. VSCode 확장이 아직 없어도 이 스테이지는 독립적으로
검증 가능(Supabase 테이블 값을 수동으로 갱신해보는 방식으로).

### 스테이지 3 — VSCode 확장 구현

[katalog/vscode-moonkata-sync](https://github.com/katalog/vscode-moonkata-sync) 저장소(현재는 플레이스홀더)에,
스테이지 2와 대칭되는 기능을 구현한다 (§5 상세).

1. 확장 프로젝트 실제 스캐폴딩 (`yo code` 등 — 지금 있는 건 최소 placeholder일 뿐, TS 빌드/테스트
   설정은 아직 없음)
2. 설정(`moonkataSync.supabaseUrl`) + `SecretStorage` 기반 anon key/공유 시크릿 입력 커맨드
3. 커서 위치 추적 → 오프셋 계산 → 디바운스 저장(쓰기)
4. 파일 열기/포커스 시 조회 → 비교 → 알림 → 확인 시 커서 이동(점프)

**완료 기준**: PC와 안드로이드 양쪽에서 같은 파일을 오가며 읽어도, 항상 더 멀리 읽은 쪽 위치로
"따라잡기" 팝업/알림이 뜨고 실제로 그 위치로 이동한다.

### 마무리 — End-to-End 검증

세 스테이지가 다 끝난 뒤, 실기기 + 실제 Syncthing 동기화 환경에서 두 기기를 오가며 수동으로 확인한다
(자동화 테스트로 커버하기 어려운 부분).

## 열린 질문 (구현 착수 전 결정 필요)

1. ~~VSCode 확장을 이 저장소 안에 둘지, 완전히 별도 저장소로 뺄지.~~ **결정: 완전 분리.**
   [katalog/vscode-moonkata-sync](https://github.com/katalog/vscode-moonkata-sync)(공개)로 새로 만듦 —
   현재는 계획 단계 플레이스홀더.
2. ~~상대 경로 정규화 규칙 확정~~ **결정: §3 "정규화 규칙 (확정)" 참고** — 구분자 `/` 통일 → NFC →
   소문자화 순으로 양쪽 클라이언트가 동일하게 적용.
3. ~~RLS 시크릿을 앱에 어떻게 안전하게 심을지~~ **결정: §1 "시크릿 관리" 참고** — 빌드에 굽지 않고
   앱/확장 설정 화면에서 직접 입력받는 방식(안드로이드: 설정 UI 텍스트 필드 3개, VSCode: 설정 +
   `SecretStorage`). 컴파일된 APK/vsix엔 값이 전혀 안 남아 이전 방식(CI 시크릿 주입)의 한계가 해소됨.
   대신 개발자 본인 외의 설치자에게는 이 기능이 계속 꺼져 있음(§1 트레이드오프 참고).
4. ~~인코딩 불일치 처리 수준~~ **결정: §5 "인코딩 점검 & 변환" 참고** — VSCode가 UTF-8이 아닌 파일을
   열면 감지해서 UTF-8 변환을 물어보고, 동의하면 파일 자체를 UTF-8로 덮어써 근본적으로 해소. 거절 시엔
   `encoding` 필드 비교로 팝업만 비활성화하는 폴백.
5. ~~"읽은 위치" 기준을 커서 위치로 할지, 스크롤 최하단 기준으로 할지.~~ **결정: 커서 위치.**
   `document.offsetAt(editor.selection.active)`(§5) 그대로. 오프셋 계산 부분만 분리해두면 나중에
   스크롤 기준으로 바꾸기도 쉬움.
6. ~~기존에 이미 등록된 책들의 `relativePath` 백필을 유도할지.~~ **결정: 자연스러운 재방문에 맡긴다
   (§3).** 서재 진입 시 강제 전체 재스캔은 하지 않음 — "SAF 폴더는 재귀 스캔하지 않는다"는 기존 설계
   원칙과 충돌하고, 니치한 개인용 기능이라 "그 폴더를 다시 한 번 탭하면 채워진다" 정도의 불편은 감수할
   만함. 재방문 전까지 그 책은 이 기능만 조용히 비활성 상태.
