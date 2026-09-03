<img src=".docs/app_icon.png" alt="문카타 리더 앱 아이콘" width="96" height="96">

# 문카타 리더 (Moonkata Reader)

**[English](README.md) | [한국어](README.ko.md)**

로컬 기기에 있는 `.txt` 소설을 읽기 위한 안드로이드 텍스트 리더 앱입니다.
계정 로그인이나 벤더 종속 없이 **오프라인 우선(offline-first) 단일 사용자 앱**으로, 처음부터 직접 설계하고 구현했습니다. 핵심 읽기 경험은 네트워크를 전혀 타지 않습니다. 다만 원할 때 PC와 연결할 수 있는 선택적(기본 꺼짐) 기능 두 가지가 있습니다 — VSCode와 읽기 위치를 공유하는 기능과, 작은 PC 동반 서버에서 책 파일을 받아오는 기능이며 아래에서 자세히 다룹니다.

![Kotlin](https://img.shields.io/badge/Kotlin-2.2.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.09-4285F4?logo=jetpackcompose&logoColor=white)
![Room](https://img.shields.io/badge/Room-2.7.2-3DDC84?logo=android&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-24-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey)

## 왜 만들었나

시중 뷰어 앱들이 제공하는 세세한 커스터마이징(챕터 점프, 폰트 다운로드, 다중 페이지 넘김 방식 등)을
직접 설계·구현해보고 싶어서 기획 단계부터 전체 기능 범위를 스스로 정의하고 만든 개인 프로젝트입니다.
DB 스키마, 페이지네이션 알고리즘, 오프라인 파일 처리(SAF, 인코딩 감지, zip) 등을 프레임워크 도움 없이
직접 설계한 부분에 특히 신경 썼습니다.

## 주요 기능

### 라이브러리 / 파일
- SAF(Storage Access Framework)로 선택한 폴더를 탭해서 들어가는 방식의 파일 탐색기(재귀 전체 스캔이 아님), 이름/날짜/크기 정렬 지원
- UTF-8 / EUC-KR / CP949 인코딩 자동 감지 (`juniversalchardet`)
- zip 압축 파일 내부도 폴더처럼 탐색해 그 안의 `.txt`를 바로 열기 지원
- 앱을 새로 켰을 때 마지막으로 읽던 책이 있으면 이어서 볼지 물어보는 다이얼로그
- 서재 화면 상단 바에서 PC 동기화·정렬·앱 설정에 바로 접근 — 책을 먼저 열지 않아도 됨

### 읽기 경험
- **스와이프 페이지 넘김**과 **세로 스크롤** 두 가지 읽기 모드
- 페이지 전환 애니메이션: 없음 / 슬라이드 / 덮기 중 선택
- 폰트 크기 / 줄간격 / 자간 / 좌우·상하 여백 세밀 조정
- 라이트 / 다크 / 세피아 테마 + 앱 내 밝기 슬라이더
- 터치 탭 존, 스와이프 방향, 볼륨키를 각각 원하는 방향으로 매핑 가능
- **무료 한글 폰트 다운로드** — 오픈 라이선스(OFL) 한글 폰트 카탈로그에서 골라 다운로드 후 바로 적용

### 진행 관리
- **문자 오프셋 기반 자동 이어읽기** — 폰트/여백을 바꿔 페이지 구성이 달라져도 정확한 위치 복원
- 정규식 기반 목차(챕터) 자동 인식 — 프리셋 패턴을 켜고 끄거나 커스텀 정규식을 직접 추가 가능
- 목차를 열면 지금 읽고 있는 챕터 근처로 자동 스크롤되고 강조 표시됨
- **챕터 점프 모드** — 챕터 하나를 N등분해서 그 지점들을 순서대로 점프하며 빠르게 훑어보기

### 편의 기능
- 본문 검색 — 입력 중이 아니라 검색 버튼(또는 키보드 검색 액션)을 눌렀을 때 실행되고, 마지막 검색 결과를 이어서 볼 수 있으며, 지금 읽는 위치와 가장 가까운 결과가 강조되어 보임
- 줄바꿈 정리 옵션(원문 유지 / 문단 재구성)
- 화면 꺼짐 방지, 화면 방향 고정
- 타이머 기반 자동 페이지 넘김 / TTS 음성 낭독 (상호 배타적으로 동작)

### 기기 간 동기화 (선택 기능, 기본 꺼짐)
- **VSCode와 읽기 위치 동기화** — PC에서도 같은 `.txt` 파일을 동반 VSCode 확장으로 읽는다면, 더 멀리 읽은 쪽 위치로 다른 쪽에 따라올지 물어봅니다. 계정 없이, 양쪽에 같은 공유 시크릿 문자열 하나만 있으면 되는데, 직접 타이핑/붙여넣기 해도 되고 상대편이 보여주는 QR을 카메라로 스캔해 바로 페어링해도 됩니다(안드로이드의 QR 스캐너는 아래 PC 파일 동기화와 화면을 공유합니다). 실제 방어선은 로그인이 아니라 Supabase 프로젝트의 접근 정책(RLS)이고, 이 프로젝트는 오프셋 하나만 중계합니다 — 모든 설치본이 개발자 개인 프로젝트 하나를 여전히 함께 쓰지만, 서버 트리거가 각 시크릿을 해시해 `user_key`로 삼고 그 값으로 행을 격리하기 때문에 설치본끼리 서로의 데이터를 볼 수 없습니다. best-effort로 설계돼 있어 실패하면(오프라인, 미검증 시크릿 등) 조용히 건너뛸 뿐 로컬 읽기/저장 흐름을 절대 막지 않습니다.
- **PC에서 파일 동기화** — PC에서 실행하는 작은 오픈소스 Windows 트레이 앱([`external_library/sync_server`](external_library/sync_server), 순수 Go, exe 하나 외엔 설치할 게 없음)이 폴더 하나를 같은 Wi-Fi 안에서 HTTPS로 공유하면, 안드로이드 앱이 라이브러리 폴더로 단방향(PC→폰) 미러링합니다. "지금 동기화" 버튼 하나로 동작하고, 클라우드 저장소나 계정 없이 PC 자체가 서버 역할을 합니다 — 인증은 한 번 복사해 붙여넣는 시크릿으로 하거나, 트레이 앱이 보여주는 QR 하나를 스캔해 호스트 주소·시크릿·TLS 인증서 지문을 한 번에 받아와도 됩니다(수동 입력 전부 생략). 사설 IP는 정식 인증서를 받을 수 없어서, TLS는 CA 검증 대신 SSH 방식(최초 접속 때 지문을 저장해두고 이후엔 그 지문과 정확히 같은지만 확인)으로 고정합니다. 트레이 앱의 모든 알림은 논블로킹 Windows 토스트라, 확인을 눌러야 넘어가는 모달 창에 서버가 멈춰있는 일이 없습니다.

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 언어 | Kotlin 2.2.0 |
| UI | Jetpack Compose (BOM 2024.09.00) + Material3 + Navigation-Compose |
| 비동기 | Kotlin Coroutines, `Dispatchers.Default`에서 백그라운드 페이지네이션 |
| 로컬 DB | Room 2.7.2 (KSP) |
| 설정 저장 | DataStore Preferences |
| 파일 처리 | Storage Access Framework, `java.util.zip`, `juniversalchardet` |
| 음성 | Android `TextToSpeech` |
| 아키텍처 | 수동 MVVM (`AndroidViewModel` + Repository), DI 프레임워크 없음 |

## 설계에서 신경 쓴 부분

**읽기 위치는 페이지 번호가 아니라 "전체 텍스트 내 문자 오프셋"으로 저장합니다.**
폰트 크기나 여백, 화면 크기가 바뀌면 페이지 나누기 자체가 매번 달라지기 때문에, 페이지 인덱스는
그때그때 다시 계산되는 파생값으로 취급하고 Room에는 오프셋만 영구 저장합니다.

**페이지 모드는 책 전체의 페이지 목록을 만들어두지 않습니다.**
수백만 자짜리 장편을 한 번에 전부 페이지네이션하면 느리고, 미리 계산해둔 리스트를 화면에 보이는
페이지 인덱스와 계속 동기화해야 하는 구조는 오히려 여러 종류의 어긋남을 만들기 쉽습니다. 대신
지금 보여줄 페이지 하나(문자 오프셋 구간)만 그때그때 계산합니다 — 다음 페이지는 현재 페이지 끝
오프셋부터 한 페이지만 새로 측정하고, 이전 페이지는 정방향으로 넘기며 쌓아온 방문 이력을 그대로
되짚어 쓰거나(즉시·정확), 검색 결과로 점프한 직후처럼 이력이 없을 때만 역산으로 추정합니다.
페이지 전환도 `HorizontalPager`의 인덱스 기반 스크롤이 아니라 `AnimatedContent`로 처리해, 페이지
개수·인덱스를 별도로 맞출 필요 자체가 없습니다.

**목차(챕터)는 DB에 저장하지 않고 세션마다 정규식으로 즉석에서 계산합니다.**
스키마 마이그레이션 부담 없이 챕터 인식 패턴을 자유롭게 추가/개선할 수 있고, 매칭이 0건이어도
정상적으로 "목차 없음" 상태로 처리됩니다.

**자동 넘김은 `OFF / TIMER / TTS` 3중 상태 하나로 모델링했습니다.**
타이머 자동넘김과 TTS를 각각 별도 boolean으로 두면 두 기능이 동시에 켜지는 충돌 상태가 생길 수 있어,
애초에 상호 배타적인 라디오 상태 하나로 설계해 그 가능성 자체를 차단했습니다.

## 프로젝트 구조

```
com.moonkata.textreader/
├── MainActivity.kt              — NavHost 호스트, onKeyDown 위임만 담당
├── navigation/                  — library ↔ reader 화면 전환
├── data/
│   ├── db/                      — Room: BookEntity, DAO
│   ├── datastore/                — ReaderSettings, ReaderSettingsRepository
│   ├── file/                     — SafFolderBrowser(SAF 탐색, 재귀 스캔 아님), EncodingDetector(UTF-8/EUC-KR/CP949 자동감지), BookSource(zip 내부도 탐색)
│   ├── font/                     — FontCatalog(무료 한글 폰트 목록) + FontDownloadManager + FontResolver
│   ├── parser/                   — TextReflower(줄바꿈 정리) → Paginator가 소비, ChapterDetector/ChapterPatternCatalog, ChapterJumpNavigator
│   ├── sync/                     — 선택적 기기 간 동기화: VSCode 읽기 위치 클라이언트(Supabase),
│   │                               PC 파일 동기화 클라이언트(HTTPS + TLS 지문 고정), 두 QR 스캔
│   │                               흐름이 함께 쓰는 페어링 페이로드 파서
│   └── repository/               — BookRepository
├── model/                        — Paragraph, Chapter, PageBreak, FolderEntry 등 도메인 모델
├── ui/
│   ├── library/                  — 폴더 탐색기 화면, "이어서 읽기" 다이얼로그, PC 동기화 시트
│   ├── reader/                   — 리더 화면, 퀵설정/목차/검색/폰트/챕터패턴 바텀시트
│   ├── qr/                       — 두 동기화 페어링 흐름이 공유하는 카메라 QR 스캐너
│   ├── theme/                    — 테마 프리셋
│   └── SettingsController.kt     — 리더·서재 화면이 공통 구현하는 인터페이스, 같은 설정 시트를
│                                   어느 화면에서 열든 재사용할 수 있게 함
├── tts/                          — TtsController, AutoPageTurnController
└── util/                         — SAF/컬렉션 확장 함수

external_library/
└── sync_server/                  — 위 파일 동기화 기능의 PC쪽 동반 트레이 앱(Go, 프레임워크 없음),
                                     자체 QR 페어링 페이지와 논블로킹 토스트 알림 포함
```

각 기능을 어떤 파일이 담당하고 구체적으로 어떻게 구현했는지는 [`docs/FEATURES.md`](.docs/FEATURES.md)에 파일 단위로 정리해 두었습니다. 사용자 시나리오별로 어떤 파일의 어떤 함수가 실행되는지는 [`docs/USER_SCENARIOS.md`](.docs/USER_SCENARIOS.md)에 순서대로 정리해 두었습니다.

## 테스트

Android 런타임(Context, Room, DataStore, Compose, `TextMeasurer`) 필요 여부로 테스트를 두 소스셋에
나눠 둡니다.

- **`app/src/test`** — 순수 로직(문단 재구성, 챕터 인식, 인코딩 감지, 페이지네이션 보조 계산 등)을
  기기/에뮬레이터 없이 JVM에서 바로 검증하는 일반 JUnit 테스트.
- **`app/src/androidTest`** — Compose UI 렌더링, Room/DataStore, 실제 텍스트 측정이 필요한 계측
  테스트. 저장소에 커밋된 퍼블릭 도메인 소설 픽스처로 페이지네이션 방문 이력·챕터 자동인식·인코딩 감지를 검증하고,
  라이브러리/설정/검색/목차 시트도 실제 상호작용으로 검증합니다.
- 폰트 다운로드는 `MockWebServer`(가짜 로컬 서버)로 성공/실패 로직을 검증하는 것과 별개로, 실제
  배포처(GitHub 등)에서 진짜로 다운로드되고 뷰어에 적용까지 되는지 확인하는 실 네트워크 테스트도 둡니다
  — 실제로 이 테스트 덕분에 폰트 배포처 URL 3개가 조용히 깨져있던 걸 잡아서 고쳤습니다.
- 기기 간 동기화 두 기능은 순수 로직 테스트(상대경로 정규화, PC 동기화 델타 계산, TLS 지문 해시)와
  두 클라이언트의 `MockWebServer` 기반 프로토콜 계약 테스트로 검증합니다 — HTTPS/지문 고정 클라이언트
  쪽은 즉석에서 만든 자체 서명 인증서로 실제 TLS 핸드셰이크까지 검증합니다. 실제 SAF 파일 쓰기, LAN
  서브넷 스캔, PC 트레이 앱 자체처럼 자동화하기 어려운 부분은 `TESTING.md`에 기록된 실기기 수동 검증으로
  남겨뒀습니다. PC 서버(Go)의 경로 탈출 방지 로직은 `external_library/sync_server`에 별도 `go test`
  스위트가 있습니다.
- IME 표시 여부, 실제 타이머/TTS 타이밍처럼 Compose 시맨틱 트리로는 신뢰성 있게 검증할 수 없는 플랫폼
  동작은 의도적으로 범위에서 제외하고 수동 확인으로 남겨둡니다 — 통과해도 실제 회귀를 못 잡는 테스트를
  굳이 만들지 않았습니다.

전체 계획과 각 테스트가 뭘 검증하는지는 [`TESTING.md`](.docs/TESTING.md)에 단계별로 기록해뒀습니다.

## 빌드 & 실행

```bash
git clone <this-repo>
cd android-text-reader
./gradlew assembleDebug
```

- `compileSdk` / `targetSdk` 36, `minSdk` 24, Java 11
- Android Studio에서 열어 실기기 또는 에뮬레이터에 바로 실행 가능
- 폰트 다운로드 기능에만 인터넷 연결이 필요하며, 그 외 모든 기능은 완전 오프라인으로 동작

`vX.Y.Z` 형식의 태그를 push하면 [`.github/workflows/release.yml`](.github/workflows/release.yml)이 자동으로 빌드해서 [Releases](../../releases) 페이지에 APK를 올립니다. Play 스토어에 배포하는 게 아니라서 릴리스 빌드도 디버그 키스토어로 서명합니다 — 별도 서명 설정 없이 `assembleRelease`만으로 바로 설치 가능한 APK가 나옵니다.

선택 기능인 PC 파일 동기화 동반 앱(`external_library/sync_server`)은 안드로이드 앱과 런타임 의존이 전혀 없는 독립 Go 모듈입니다 — 그 폴더 안에서 `go build`하거나, 같은 Releases 페이지에서 미리 빌드된 Windows `.exe`(`sync-server-vX.Y.Z` 태그, [`.github/workflows/release-sync-server.yml`](.github/workflows/release-sync-server.yml)가 빌드)를 받으면 됩니다.

## 앞으로 추가하고 싶은 것

향후 추가 아이디어는 [`IDEAS.md`](.docs/IDEAS.md)에 정리해두었습니다.

## 라이선스

[Apache License 2.0](LICENSE)
