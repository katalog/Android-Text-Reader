<img src="docs/app_icon.png" alt="문카타 리더 앱 아이콘" width="96" height="96">

# 문카타 리더 (Moonkata Reader)

**[English](README.md) | [한국어](README.ko.md)**

로컬 기기에 있는 `.txt` 소설을 읽기 위한 안드로이드 텍스트 리더 앱입니다.
서버/로그인/동기화가 전혀 없는 **완전 오프라인 단일 사용자 앱**으로, 처음부터 직접 설계하고 구현했습니다.

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
│   └── repository/               — BookRepository
├── model/                        — Paragraph, Chapter, PageBreak, FolderEntry 등 도메인 모델
├── ui/
│   ├── library/                  — 폴더 탐색기 화면, "이어서 읽기" 다이얼로그
│   ├── reader/                   — 리더 화면, 퀵설정/목차/검색/폰트/챕터패턴 바텀시트
│   └── theme/                    — 테마 프리셋
├── tts/                          — TtsController, AutoPageTurnController
└── util/                         — SAF/컬렉션 확장 함수
```

각 기능을 어떤 파일이 담당하고 구체적으로 어떻게 구현했는지는 [`docs/FEATURES.md`](docs/FEATURES.md)에 파일 단위로 정리해 두었습니다.

## 테스트

Android 런타임(Context, Room, DataStore, Compose, `TextMeasurer`) 필요 여부로 테스트를 두 소스셋에
나눠 둡니다.

- **`app/src/test`** — 순수 로직(문단 재구성, 챕터 인식, 인코딩 감지, 페이지네이션 보조 계산 등)을
  기기/에뮬레이터 없이 JVM에서 바로 검증하는 일반 JUnit 테스트.
- **`app/src/androidTest`** — Compose UI 렌더링, Room/DataStore, 실제 텍스트 측정이 필요한 계측
  테스트. 실제 소설 픽스처(로컬 전용)로 페이지네이션 방문 이력·챕터 자동인식·인코딩 감지를 검증하고,
  라이브러리/설정/검색/목차 시트도 실제 상호작용으로 검증합니다.
- 폰트 다운로드는 `MockWebServer`(가짜 로컬 서버)로 성공/실패 로직을 검증하는 것과 별개로, 실제
  배포처(GitHub 등)에서 진짜로 다운로드되고 뷰어에 적용까지 되는지 확인하는 실 네트워크 테스트도 둡니다
  — 실제로 이 테스트 덕분에 폰트 배포처 URL 3개가 조용히 깨져있던 걸 잡아서 고쳤습니다.
- IME 표시 여부, 실제 타이머/TTS 타이밍처럼 Compose 시맨틱 트리로는 신뢰성 있게 검증할 수 없는 플랫폼
  동작은 의도적으로 범위에서 제외하고 수동 확인으로 남겨둡니다 — 통과해도 실제 회귀를 못 잡는 테스트를
  굳이 만들지 않았습니다.

전체 계획과 각 테스트가 뭘 검증하는지는 [`TESTING.md`](TESTING.md)에 단계별로 기록해뒀습니다.

## 빌드 & 실행

```bash
git clone <this-repo>
cd android-text-reader
./gradlew assembleDebug
```

- `compileSdk` / `targetSdk` 36, `minSdk` 24, Java 11
- Android Studio에서 열어 실기기 또는 에뮬레이터에 바로 실행 가능
- 폰트 다운로드 기능에만 인터넷 연결이 필요하며, 그 외 모든 기능은 완전 오프라인으로 동작

## 앞으로 추가하고 싶은 것

향후 추가 아이디어는 [`IDEAS.md`](IDEAS.md)에 정리해두었습니다.

## 라이선스

[Apache License 2.0](LICENSE)
