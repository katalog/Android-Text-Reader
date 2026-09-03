# 계획: PC 트레이 서버 기반 PC → 안드로이드 단방향 파일 동기화

**상태**: P1~P3, A1~A2/A4 전부 완료. HTTPS(자체 서명 인증서 + TOFU 지문 고정)로 전환 완료 — cleartext
설정 전부 제거함. 델타 비교에서 로컬 수정시각 의존성 제거(재동기화 때 안 바뀐 파일까지 매번 다시
받던 버그 수정). [SMB_FILE_SYNC_PLAN.md](SMB_FILE_SYNC_PLAN.md)를 대체. 여러 사용자 대상 QR 페어링
등 공개 배포용 후속 작업은 [SYNC_MULTIUSER_PLAN.md](SYNC_MULTIUSER_PLAN.md) 참고.

## 배경

[SMB_FILE_SYNC_PLAN.md](SMB_FILE_SYNC_PLAN.md)로 SMB 기반 동기화를 실제 구현하고 실기기로 검증하던
중, Windows 11(특히 24H2+) 기본값에서는 SMB 서명(signing)을 요구하는데 익명/게스트 SMB 세션은
서명이 원천적으로 불가능해서 접속 자체가 거부되는 걸 확인했다. 우회하려면:
- SMB 서명 요구 해제(`Set-SmbServerConfiguration -RequireSecuritySignature $false`) — 그 PC의
  보안 수준을 실제로 낮추는 설정 변경
- 또는 Windows 로컬 계정을 새로 만들어 자격증명으로 접속 — 로그인 계정이 Microsoft 계정 + PIN이면
  실제 그 계정 비밀번호를 알아야 하고(대부분 기억 못 함), 로컬 계정을 새로 만드는 추가 단계 필요

둘 다 "PC 쪽 설정 없이 동작해야 한다"는 원래 목표와 어긋난다. 그래서 SMB를 버리고, 우리가 프로토콜을
통째로 통제하는 **작은 PC 트레이 서버(신규 프로그램, Go)**로 방향을 바꿨다 — Windows 보안 정책이나
계정 체계에 전혀 얽히지 않고, Supabase 연동 때 이미 검증된 "공유 시크릿 + 연결 테스트" 패턴을 그대로
재사용할 수 있다.

## 왜 VSCode 확장에 얹지 않았는지

읽기 위치 동기화용 `moonkata-reader-sync` 확장에 파일 서버 기능까지 얹는 안도 검토했으나(코드 재사용은
큼), 그러면 "읽기 위치 동기화"와 "파일 전송 서버"라는 서로 다른 책임이 한 확장에 섞이고, 동기화하려면
그 순간 VSCode가 켜져 있어야 한다는 제약도 생겨서 기각. 완전히 독립된 작은 프로그램으로 분리한다.

## 목표 (이번 범위)

단방향(PC → 폰)은 그대로 유지. SMB 계획과 동일하게 파일 추가/삭제는 PC에서만 일어난다고 가정.

## 아키텍처

```
[PC 트레이 앱 (Go, 이 저장소 external_library/sync_server/)]  <--HTTP(공유 시크릿 헤더)-->  [이 안드로이드 앱]
   폴더를 HTTP로 노출 + 트레이 UI                                                    "PC 찾기" + "지금 동기화" 버튼
```

## 핵심 설계 결정

### 1. PC 트레이 앱 — Go, 이 저장소 안 `external_library/sync_server/` (모노레포, 새 저장소 아님)

VSCode 확장(`vscode-moonkata-reader-sync`)은 마켓 배포 단위가 완전히 달라서 별도 저장소로 뺐지만,
이 Go 서버는 그럴 이유가 없다고 판단 — **새 저장소를 만들지 않고 `Android-Text-Reader` 저장소 안
`external_library/sync_server/` 폴더에 둔다.** 브랜치도 SMB 때 쓰던 `smb-file-sync`는 잘못된 방향으로
만들어졌던 브랜치라 그대로 버리고, `sync-server`라는 새 브랜치 하나에서 Go 서버 코드와 안드로이드 쪽
변경을 같이 진행한다(어차피 같은 저장소 안에 있으니 브랜치도 하나로 묶는 게 자연스러움).

Go를 선택한 이유: 런타임 설치가 전혀 불필요한 단일 네이티브 exe로 컴파일되고(수 MB 수준), 배포가
"파일 하나 실행"으로 끝나서 일반 사용자 진입장벽이 가장 낮음.

- 시스템 트레이 아이콘 + 최소 설정 창: 공유할 폴더 선택, 공유 시크릿 표시/재생성/복사, "Windows 시작
  시 자동 실행" 체크박스, 종료.
- **시크릿은 PC 앱이 직접 생성**해서 보여준다(사용자가 직접 만들어 입력하는 대신) — 복사 버튼으로
  안드로이드 쪽 "공유 시크릿" 필드에 그대로 붙여넣기만 하면 되게. Supabase 시크릿 설정 때보다 더
  간단한 버전(그때는 개발자가 `.pass` 파일에 직접 만들어 보관했지만, 여기는 일반 사용자 대상이라
  앱이 대신 만들어줌).
- 첫 실행 시 Windows 방화벽이 "네트워크 접근 허용" 팝업을 한 번 띄울 수 있음 — 원클릭 허용, SMB
  서명/계정 설정보다 훨씬 익숙하고 가벼운 마찰.
- **보안 범위를 명확히 함**: 이 서버는 신뢰할 수 있는 로컬 네트워크(집 공유기 안)에서만 쓰는 걸
  전제로 한다 — 포트포워딩/인터넷 노출은 지원 범위 밖(문서에 명시).

### 2. 통신 프로토콜 — 고정 포트 + 공유 시크릿 헤더 (신규, 단순)

포트는 설정 항목으로 안 두고 양쪽에 고정값으로 박아둔다(Supabase URL/publishable key를 고정값으로
박아둔 것과 같은 이유 — 사용자가 틀리게 입력할 수 있는 필드를 하나 줄임). 포트: `58221`(IANA 동적/사설
포트 범위라 공식 등록된 서비스와 충돌 불가능, §열린 질문 참고).

| 엔드포인트 | 인증 | 설명 |
|---|---|---|
| `GET /ping` | 없음 | `{"app":"moonkata-sync-server","version":"..."}` — PC 찾기(포트 스캔) 때 "진짜 우리 서버가 맞는지" 확인용. 민감정보 없음. |
| `GET /list` | `x-moonkata-secret` 헤더 | 공유 폴더 아래 `.txt`/`.zip`을 재귀적으로 나열, JSON 배열(`relativePath`/`sizeBytes`/`lastModifiedMillis`) |
| `GET /file?path=...` | `x-moonkata-secret` 헤더 | 해당 상대경로 파일의 원본 바이트 그대로 |

시크릿이 없거나 틀리면 401, 파일이 없으면 404. `x-moonkata-secret` 헤더 이름은 Supabase 연동 때 쓰던
것과 동일하게 맞춰서 패턴 일관성 유지(다른 서비스라 값 자체는 물론 다름).

### 3. 안드로이드 쪽 — SMB 코드 걷어내고 HTTP로 교체 (재사용 가능한 부분 많음)

- **거의 그대로 재사용**: `LocalLibraryScanner`(로컬 SAF 트리 재귀 나열)는 전송 방식과 무관해서 변경
  없음. `SmbFileSyncManager`의 델타 계산/SAF 쓰기 로직도 원격 파일 목록의 타입만 바뀌지 구조는 동일 —
  이름만 정리(예: `PcSyncFileManager`).
- **새로 작성**: `SmbSyncClient` → `PcSyncClient`로 교체. SMB 프로토콜 대신 그냥 `HttpURLConnection`
  GET 요청 두 개(`/list`, `/file`)라 `ReadingPositionSyncClient`와 완전히 같은 스타일 — **새 의존성
  불필요**(smbj 걷어냄).
- **호스트 찾기**: 기존 `SmbHostScanner`의 로컬 서브넷 병렬 포트 스캔 방식 그대로 재사용, 대상 포트만
  445 → 58221로 변경. 추가로 `/ping` 응답까지 확인해서 "포트는 열려있지만 SMB/다른 서비스"인 오탐을
  거르는 것도 가능(SMB 스캔보다 개선된 점).
- **자격증명 삭제**: 실제 Windows 계정이 필요 없어졌으니 `SmbCredentialStore`(암호화 저장소),
  `androidx.security.crypto` 의존성, 사용자 이름/비밀번호 입력 필드 전부 제거. 공유 시크릿 하나만
  남아서 Supabase 때와 완전히 같은 패턴(평문 DataStore, "테스트 성공 시점 값과 비교"로 검증 상태 판단)
  으로 되돌아감 — 오히려 SMB 버전보다 설정 UI가 더 단순해짐.
- **`minSdk` 24로 원복**: `smbj` 때문에 26으로 올렸던 걸 되돌린다 — 새 방식엔 그 제약이 없음.

### 4. 설정 UI — 필드가 더 줄어듦

`SmbSyncSheet` → `PcSyncSheet`로 정리. 필드: **PC 주소**(PC 찾기 버튼) + **공유 시크릿** + "연결
테스트" + "지금 동기화" — 공유 이름/하위 경로/사용자 이름/비밀번호가 전부 없어짐(공유 이름·경로는
프로토콜상 필요 없고, 사용자 이름·비밀번호는 인증 방식이 바뀌어 필요 없어짐).

### 5. HTTPS + TOFU 인증서 지문 고정 (구현 후 추가된 보안 강화)

처음엔 http(평문)로 만들었었는데(§2), 사용자가 "같은 와이파이의 다른 기기가 시크릿/소설 내용을
스니핑할 수 있다"는 점을 지적해서 HTTPS로 바꿨다. 사설 IP엔 공인 CA가 인증서를 못 주므로 SSH 방식
(TOFU: 최초 접속 때 지문을 저장해두고 이후로는 그 지문과 정확히 같은지만 확인)을 쓴다:

- **PC 서버**: 처음 실행될 때 자체 서명 인증서(ECDSA P-256, 유효기간 20년 — 개인용 LAN 도구라 정기
  갱신 없이 길게)를 만들어 %APPDATA%에 저장해두고 재사용(재실행마다 바뀌면 안드로이드가 저장해둔
  지문이 계속 안 맞음). `http.ListenAndServeTLS` 하나로만 서비스 — 표준 라이브러리(`crypto/tls`,
  `crypto/x509`)만 쓰고 새 의존성 없음.
- **안드로이드**: `/ping`(탐색)과 "연결 테스트"는 인증서를 검증하지 않는 lenient `SSLContext`로 접속하되,
  "연결 테스트"가 성공하면 그때 실제로 받은 인증서의 SHA-256 지문을 `pcSyncPinnedFingerprint`로
  저장한다. 이후 "지금 동기화"(`/list`, `/file`)는 그 지문과 정확히 일치하는 인증서만 받아들이는
  pinned `SSLContext`로 접속 — 저장된 지문과 다른 인증서를 내미는 서버는 거부된다. 호스트명 검증은
  항상 통과시킴(IP로 접속하는 경우가 대부분이고, 신뢰 근거가 CN/SAN이 아니라 지문이므로 의미 없음).
- **cleartext 설정 전부 제거**: `AndroidManifest.xml`의 `usesCleartextTraffic`/`networkSecurityConfig`
  참조와 `res/xml/network_security_config.xml`을 삭제, `debug` 전용 설정도 MockWebServer 테스트용
  원래 범위(`localhost`/`127.0.0.1`만)로 되돌림 — 릴리스 빌드는 이제 Android 기본값(cleartext 전면
  차단) 그대로.
- **실기기 검증**: 안드로이드가 계산한 지문이 `openssl s_client ... | openssl x509 -fingerprint -sha256`로
  독립적으로 확인한 실제 서버 인증서 지문과 정확히 일치하는 것을 확인. lenient 모드(연결 테스트)와
  pinned 모드(실제 동기화) 둘 다 실제 파일 전송까지 성공하는 것까지 확인.

### 6. 델타 비교에서 로컬 수정시각 제거 (재동기화 버그 수정)

원래 원격/로컬 파일을 크기+수정시각 둘 다로 비교했는데, 실사용 중 "PC에서 몇 개만 지워도 나머지
전부 다시 받는 것 같다"는 문제가 보고됐다. 원인: 다운로드한 로컬 파일의 수정시각은 "받은 시점"이
되지 PC 원본의 수정시각을 못 물려받는다(SAF가 문서 수정시각을 임의로 설정하게 허용 안 하는 제공자가
많음) — 그래서 재동기화 때마다 로컬 시각(지난 동기화 시점)과 원격 시각(PC의 진짜 파일 시각)이 안 바뀐
파일도 항상 달라서 전부 "바뀐 파일"로 오판됐다. 수정시각 비교를 빼고 **크기만** 비교하도록
`PcSyncFileManager.kt`를 수정 — 소설 텍스트는 내용이 바뀌면 거의 항상 글자 수도 바뀌니 실용적으로
충분하다. 실기기에서 "동기화 → 즉시 재동기화" 시 두 번째가 `받음 0 · 갱신 0 · 삭제 0`으로 끝나는 것을
확인해서 고쳐진 것을 검증함.

### 7. 단일 인스턴스 강제 + 실행 시작 알림을 모달에서 풍선 알림으로 (v1.5.0-beta.3, 실사용 피드백)

- **중복 실행 방지**: 이름 있는 Windows 뮤텍스(`Global\moonkata-sync-server-single-instance`,
  `single_instance_windows.go`)로 판정 — `main()` 맨 앞에서 확인해서, 이미 실행 중이면 두 번째
  인스턴스는 설정도 안 읽고 포트도 안 열고 바로 종료한다. 포트 바인딩 실패는 원래도 처리돼 있었지만
  (§2 `checkSecret` 근처 에러 처리) 그건 서버만 못 뜰 뿐 트레이 아이콘 자체는 중복으로 뜨는 문제라
  이걸로는 안 막혔다 — 실제로 트레이 아이콘이 두 개 뜨는 걸 사용자가 보고해서 추가.
- **실행 시작 알림을 모달(`showMessage`, MessageBoxW)에서 풍선 알림(`showNotification`,
  `dialog_windows.go`)으로 교체**: "OK를 눌러야 다음으로 넘어가는" 모달이 그냥 정보성 알림에는 과했다는
  피드백. WinForms `NotifyIcon.ShowBalloonTip`을 PowerShell로 실행하는 방식이라 Windows 10+에서는
  자동으로 화면 우측 하단 토스트로 뜬다. 안내 문구도 QR 페어링(스테이지 6)이 생긴 뒤로는 시크릿
  전체를 안 보여줘도 되므로 "트레이 메뉴의 '동기화 QR 보기'로 연결하세요" 수준으로 줄임 — 자세한
  시크릿 값이 필요하면 "공유 시크릿 복사" 메뉴로 언제든 다시 가져갈 수 있다.
- **검증**: 헤드리스 모드로 같은 폴더에 두 인스턴스를 띄워 두 번째가 "이미 실행 중인 인스턴스가
  있어 종료합니다" 로그와 함께 즉시 종료(exit 1)하는 것을 확인. 풍선 알림 PowerShell 스크립트 자체는
  예외 없이 끝까지 실행되는 것까지만 확인함 — 실제로 화면에 풍선이 렌더링되는지는 P2와 같은 이유로
  (Windows 데스크톱을 직접 스크린샷할 방법이 없음) 사용자가 직접 봐줘야 한다.

## 브랜치 / 저장소 구조

- 저장소: `Android-Text-Reader` 그대로(새 저장소 안 만듦).
- 브랜치: `main`에서 새로 딴 `sync-server` 브랜치 하나에 PC 서버 코드(`external_library/sync_server/`)와
  안드로이드 쪽 변경사항을 같이 커밋. 기존 `smb-file-sync` 브랜치는 폐기 — 삭제할지 그냥 안 건드리고
  둘지는 사용자 확인 후 처리.
- Go 모듈은 `external_library/sync_server/`를 루트로 독립적인 `go.mod`를 가짐(안드로이드 Gradle
  빌드와 완전히 분리된 별도 빌드 파이프라인).

## 페이즈별 구현 계획

### PC 앱 (`external_library/sync_server/`)

| 페이즈 | 내용 |
|---|---|
| **P1. HTTP 서버 코어** ✅ | `/ping`, `/list`, `/file` + 시크릿 헤더 검사. `-headless` 플래그로 트레이 없이 콘솔 실행도 여전히 지원(테스트용) |
| **P2. 트레이 UI** ✅ | `github.com/getlantern/systray`(Windows에서 CGO 불필요, 빌드로 확인) — 폴더 변경/시크릿 복사·재생성/자동 실행 체크박스/종료. 시작할 때마다 시크릿을 클립보드에 복사 + 메시지박스로 보여줌(안드로이드 안내 문구와 맞춤). 폴더 선택은 PowerShell의 `System.Windows.Forms.FolderBrowserDialog`를 한 줄 실행(새 GUI 라이브러리 없이 Windows 내장 기능 재사용), 클립보드 복사는 내장 `clip.exe`, 자동 실행은 `HKCU\...\Run` 레지스트리(관리자 권한 불필요) — 전부 CGO 없이 순수 Go |
| **P3. 빌드/배포** | GitHub Actions로 Windows용 exe 빌드(`-ldflags="-H=windowsgui"`로 콘솔창 안 뜨게) + 릴리스 — `external_library/sync_server/**` 경로 변경 + 별도 태그 패턴(예: `sync-server-v*`)으로 트리거, 안드로이드 앱 릴리스(`v*`)와 태그 네임스페이스가 안 겹치게 함 |

### 안드로이드 앱 (같은 `sync-server` 브랜치)

| 페이즈 | 내용 |
|---|---|
| **A1. SMB 걷어내기 + HTTP 클라이언트** | smbj/security-crypto 의존성 제거, `minSdk` 24 원복, `SmbSyncClient`/`SmbCredentialStore` 삭제, `PcSyncClient`(HttpURLConnection 기반) 신규, 포트 스캐너 대상 포트/검증 방식 조정 |
| **A2. 델타 매니저 재배선** | `SmbFileSyncManager` → `PcSyncFileManager`로 정리, 새 클라이언트 타입에 맞게 시그니처만 조정(로직 대부분 그대로) |
| **A3. 설정 UI 단순화** | `PcSyncSheet` — 필드 축소(호스트+시크릿만), Supabase 시크릿 설정과 거의 동일한 패턴 |
| **A4. 실기기 검증** | 이번엔 PC 트레이 앱도 실제로 띄워서 진짜 엔드투엔드로 확인 |

**규모 가늠**: PC 앱은 완전히 새 코드(새 언어)라 별도 초기 구축 비용이 있지만, 기능 자체는 작아서(HTTP
서버 + 트레이 아이콘) 크지 않음. 안드로이드 쪽은 SMB 버전에서 이미 만든 델타 계산/SAF 쓰기 로직을
대부분 재사용해서 처음부터 새로 짜는 것보다 빠를 것으로 예상.

## 열린 질문 — 결론

1. **고정 포트 번호**: `58221`로 확정. IANA 동적/사설 포트 범위(49152~65535)라 공식 등록된 서비스와
   충돌할 수가 없음(그 범위는 애초에 IANA가 아무것도 할당하지 않는 구간) — 확인 완료.
2. **기존 `smb-file-sync` 브랜치**: 로컬+원격 모두 삭제.

## P2 트레이 UI 검증 범위

Claude Code 세션엔 Windows 데스크톱을 직접 스크린샷할 방법이 없어서(안드로이드 기기 화면/브라우저 창은
가능하지만 데스크톱 GUI는 안 됨), 트레이 아이콘/메뉴가 화면에 정확히 어떻게 보이는지는 직접 확인하지
못했다. 대신 간접적으로 확인한 것:
- 프로그램이 정상 기동하고 크래시 없이 계속 떠 있음(`tasklist`로 확인)
- 시작 시 뜨는 메시지박스가 실제로 나타남 — PowerShell로 창 제목("moonkata-sync-server")을 찾아
  포커스시킨 뒤 Enter를 보내서 닫히는 것으로 확인(`AppActivate` 성공)
- 그 메시지박스가 시크릿을 클립보드에 실제로 복사했는지 — 클립보드 내용이 `config.json`에 저장된
  시크릿과 정확히 일치하는 것으로 확인
- 메시지박스를 닫은 뒤에도 HTTP 서버가 계속 정상 응답(`/list`)하는 것으로 확인
- 레지스트리 `HKCU\...\Run` 키 방식 자체는 관리자 권한 없이 쓰기/읽기/삭제가 되는 것을 PowerShell로
  별도 확인(Go 코드가 쓰는 것과 동일한 키 경로/값 형식)

**직접 눌러보지 못한 것**: 트레이 아이콘 모양, 메뉴 항목 배치/텍스트, "공유 폴더 변경" 클릭 시 뜨는
폴더 선택창의 실제 동작, "자동 실행" 체크박스 클릭 반응 — 실제로 트레이 아이콘을 마우스로 눌러보는
확인은 사용자가 한 번 직접 실행해서 봐주는 게 필요함.

## 실기기 종단 검증 중 발견/수정한 것 (P1 헤드리스 서버 + A1/A2/A4)

실제 PC(Go 서버를 커맨드라인으로 실행, `-folder`로 실제 라이브러리 폴더 지정)와 실기기로 175개 파일
(수 MB~십수 MB 다수 포함)을 끝까지 동기화해서 확인 — "PC 찾기" → "연결 테스트" → "지금 동기화" 전
과정이 실제로 동작하고 로컬 SAF 트리에 파일이 정확히 반영됨(받음 170 · 갱신 0 · 삭제 5, 삭제는 테스트
폴더에 미리 있던 무관한 파일들이 대상이라 의도한 동작). 과정에서 나온 진짜 버그 세 개:

- **Cleartext(http://) 차단**: `targetSdk 36`이라 평범한 http 요청이 기본적으로 막혀서 "PC 찾기"가
  항상 빈 목록만 돌려줬다. `AndroidManifest.xml`에 `usesCleartextTraffic="true"`만 추가했다가도 여전히
  막혔는데, 원인은 `app/src/debug/res/xml/network_security_config.xml`(MockWebServer 테스트용으로
  예전부터 있던, `localhost`/`127.0.0.1`만 허용하는 설정)이 `usesCleartextTraffic`보다 우선해서 계속
  막고 있었던 것 — `networkSecurityConfig`가 있으면 `usesCleartextTraffic`은 통째로 무시된다. `main`에도
  `res/xml/network_security_config.xml`(`<base-config cleartextTrafficPermitted="true">`)을 추가하고
  debug용 설정도 같은 값으로 넓혀서 해결.
- **Syncthing 마커 파일 노출**: 실제 라이브러리 폴더로 테스트하다가 `.stfolder/syncthing-folder-*.txt`가
  그대로 동기화 대상에 잡히는 걸 발견 — Go 서버의 `listFilesRecursively`에 점(`.`)으로 시작하는
  폴더/파일을 건너뛰는 필터를 추가(폴더면 하위 전체를 `filepath.SkipDir`로 스킵).
- **동기화 후 폴더뷰가 안 새로고침됨**: "지금 동기화"가 성공해도 지금 보고 있는 폴더 목록은 처음 진입할
  때 한 번만 읽어온 상태 그대로라 새로 받은 파일이 화면엔 안 보였다(재실행하면 보임) — `syncFromPc()`
  끝에 `loadCurrent()` 재호출을 추가해 성공 시 지금 위치를 다시 읽어오게 함.
