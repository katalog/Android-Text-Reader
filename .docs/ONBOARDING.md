# 온보딩 — 이 프로젝트에 처음 들어왔다면

이 문서는 "이 저장소에서 실제로 일하려면 뭘 알아야 하는가"만 다룹니다.
설계 이유는 [DESIGN_RATIONALE.md](DESIGN_RATIONALE.md), 기능별 구현은 [FEATURES.md](FEATURES.md),
시나리오별 실행 흐름은 [USER_SCENARIOS.md](USER_SCENARIOS.md)에 있습니다.

읽는 순서 제안: **이 문서 → DESIGN_RATIONALE.md → (필요할 때) 나머지.**

---

## 1. 30초 요약

로컬에 있는 `.txt` 소설을 읽는 안드로이드 앱입니다. 화면은 **두 개**뿐입니다 — 파일 고르는 서재,
글 읽는 리더. 그게 전부입니다.

"글자를 화면에 뿌리는 단순한 앱"처럼 보이지만 실제로는 세 가지가 까다롭습니다:
글자 크기만 바꿔도 페이지 나누기가 전부 달라진다는 것, 소설 한 권이 수백만 자라 무심코 "전부 처리"하면
앱이 멈춘다는 것, 그리고 요즘 안드로이드는 파일 경로로 파일을 못 읽는다는 것. **코드가 복잡해 보이는
부분은 거의 다 이 셋 중 하나 때문입니다.**

여기에 선택 기능으로 기기 간 동기화가 둘 붙어 있습니다(둘 다 기본 꺼짐): PC의 VSCode와 읽던 위치를
공유하는 것, 그리고 PC에서 책 파일 자체를 받아오는 것. 후자는 PC에서 돌리는 별도 Go 프로그램
(`external_library/sync_server/`)이 짝입니다.

---

## 2. 30분 안에 돌려보기

```bash
./gradlew assembleDebug
```

이게 되면 환경은 끝난 겁니다. 테스트는 두 종류입니다.

```bash
# 1. 순수 로직 — 기기 없이 JVM에서 몇 초 만에 끝남. 자주 돌리세요.
./gradlew :app:testDebugUnitTest

# 2. 계측 테스트 — 실제 안드로이드 런타임이 필요(에뮬레이터나 기기)
./gradlew :app:connectedDebugAndroidTest
```

### 계측 테스트는 에뮬레이터로 돌립니다

실기기 말고 **에뮬레이터(`Pixel_6`, API 33)** 를 쓰는 게 이 프로젝트의 기본입니다. 실기기는 잠금화면이
걸리면 Compose UI 테스트가 전부 "No compose hierarchies found"로 무너집니다 — 코드 문제가 아닌데 18개가
한꺼번에 빨간불이 됩니다.

```bash
# 에뮬레이터 띄우기 (백그라운드)
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd Pixel_6 &

# 시리얼 확인 — 보통 emulator-5554지만 이미 떠 있는 게 있으면 5556 등으로 밀립니다
adb devices

# 부팅 확인 (1 이 나오면 준비됨)
adb -s emulator-5554 shell getprop sys.boot_completed
```

> 아래 예시들은 시리얼을 `emulator-5554`로 씁니다. `adb devices`에 다른 번호가 찍히면 그걸로 바꾸세요.

> **⚠️ 처음에 다들 당하는 것:** Gradle의 `connectedDebugAndroidTest`는 **연결된 모든 기기에서** 테스트를
> 돌립니다. 폰을 USB로 꽂아둔 채 실행하면 에뮬레이터와 폰 양쪽에서 돌아갑니다. 하나만 지정하려면:
>
> ```bash
> ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
> ```

**한 클래스만 돌리기** (전체는 2분쯤 걸리니 개발 중엔 이걸 씁니다):

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.moonkata.textreader.data.parser.PaginatorTest
```

**PC 서버(Go) 테스트**는 Gradle과 완전히 별개입니다:

```bash
cd external_library/sync_server && go test ./...
```

---

## 3. 코드 지도 — "뭘 고치려면 어디로 가나"

파일 목록을 외울 필요 없습니다. 이 표만 있으면 됩니다.

| 고치고 싶은 것 | 가야 할 곳 |
|---|---|
| 페이지가 나뉘는 방식, 줄바꿈 정리, 챕터 인식 | `data/parser/` |
| 리더 화면의 **동작**(넘김, 점프, 검색, 상태) | `ui/reader/ReaderViewModel.kt` |
| 리더 화면의 **생김새** | `ui/reader/ReaderScreen.kt`, `*Sheet.kt` |
| 서재(파일 목록) 화면 | `ui/library/` |
| 저장되는 설정 추가·변경 | `data/datastore/` (아래 §5 레시피 참고) |
| 책 정보·읽기 위치(영구 저장) | `data/db/` |
| 파일 읽기, 인코딩 감지, zip | `data/file/` |
| 폰트 다운로드·적용 | `data/font/` |
| 기기 간 동기화 | `data/sync/` + `external_library/sync_server/` |

### 지금 몰라도 되는 것

- **`ReaderViewModel.kt`가 600줄이 넘습니다. 겁먹지 마세요.** 처음에 볼 함수는 `next()`,
  `previous()`, `updateCurrentOffset()` **세 개**뿐입니다. 나머지는 그 기능을 건드릴 때 찾아가면 됩니다.
- `external_library/sync_server/`(Go)는 앱과 완전히 분리돼 있습니다. 동기화 일을 안 한다면 안 열어봐도
  됩니다.
- `data/font/FontCatalog.kt`의 URL 목록은 그냥 데이터입니다.

---

## 4. 반드시 알고 시작해야 할 함정들

전부 실제로 시간을 날려본 것들입니다. 미리 읽어두면 하루씩 아낍니다.

**① 계측 테스트는 앱의 진짜 설정 파일을 씁니다**
`ReaderViewModel`을 띄우는 테스트는 실제 DataStore를 그대로 읽습니다. 기기에 남아 있던 설정(예:
`chapterJumpEnabled=true`)이 그대로 테스트에 섞여 들어가서, **내 코드는 멀쩡한데 테스트가 비결정적으로
깨집니다.** 그래서 기존 테스트들은 시작할 때 필요한 설정을 강제로 고정하고 `finally`에서 원래 값으로
복원합니다. 새 테스트를 쓸 때 이 패턴을 반드시 따라가세요(`PageNavigationRoundTripTest` 참고).

**② Compose에서 `Row`는 시맨틱 경계를 만들지 않습니다**
설정 화면에 스위치가 4개 있는데, 라벨 텍스트로 특정 스위치를 찾으려 하면 4개가 전부 걸립니다. `Row`가
그룹을 안 만들어서 라벨과 스위치가 전부 같은 층에 평탄화되기 때문입니다. 해결책은 `Row` 자체를
`Modifier.toggleable(role = Role.Switch)`로 감싸 하나로 합치는 것입니다(이미 그렇게 고쳐뒀습니다) —
접근성도 같이 좋아집니다.

**③ 한 화면에 같은 문구가 두 번 나오면 테스트가 못 찾습니다**
`onNodeWithText("지금 동기화")`가 섹션 제목과 버튼 양쪽에 걸려 실패한 적이 있습니다. 클릭 가능한 쪽만
집으려면 `onNode(hasText("...").and(hasClickAction()))`.

**④ `runBlocking` 안에서 `Thread.sleep` 폴링과 코루틴 구독을 같이 쓰지 마세요**
같은 단일 스레드 위에서 돌기 때문에, `Thread.sleep`이 방금 `launch`한 구독 코루틴의 실행 차례를
막아버립니다. 이벤트를 영원히 못 받습니다. Flow 구독을 검증할 땐
`async(start = CoroutineStart.UNDISPATCHED) { flow.first() }`를 쓰세요.

**⑤ `network_security_config.xml`이 있으면 `usesCleartextTraffic`이 조용히 무시됩니다**
매니페스트에 평문 허용을 켰는데 왜 안 되는지 반나절 헤맨 적이 있습니다. 둘이 같이 있으면 XML 쪽이
이깁니다. (지금은 HTTPS로 옮겨서 둘 다 필요 없어졌습니다.)

**⑥ 릴리스 버전은 `build.gradle.kts`가 아니라 git 태그에서 옵니다**
`v1.4.0` 태그를 push하면 그게 곧 앱 버전이 됩니다. 그리고 **서명 키가 없으면 조용히 디버그 키로
폴백합니다** — 에러가 안 나니 확인하세요. PC 서버는 태그 접두사가 달라서(`sync-server-v*`) 다른
워크플로우가 돕니다.

---

## 5. 첫 실전 레시피 — 설정 하나 추가하기

설정을 하나 추가하려면 **네 군데**를 고쳐야 합니다. 순서대로:

1. **`ReaderSettings.kt`** — 데이터 클래스에 필드와 기본값 추가
2. **`ReaderSettingsRepository.kt`의 `Keys`** — 저장용 키 추가
   > ⚠️ 키 이름은 한 번 정하면 바꾸지 마세요. 바꾸면 사용자 기기에 저장돼 있던 값이 날아갑니다.
   > 실제로 `chapterJumpEnabled`의 저장 키가 아직도 옛 이름(`chapter_skip_enabled`)인 이유가 이겁니다.
3. **같은 파일의 `settingsFlow`** — 읽어올 때 매핑 한 줄 추가
4. **같은 파일에 `updateXxx()`** — 쓰는 함수 추가

그다음 필요하면 `ReaderViewModel`에 setter 한 줄, UI에 컨트롤 하나. 끝입니다.

---

## 6. 변경 한 번의 전체 사이클

```bash
# 1. 순수 로직을 고쳤다면 — 몇 초, 기기 불필요
./gradlew :app:testDebugUnitTest

# 2. UI나 DB, 실제 측정이 얽혔다면 — 에뮬레이터로, 관련 클래스만
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=<고친 것과 관련된 테스트>

# 3. 커밋 전 한 번은 전체
ANDROID_SERIAL=emulator-5554 ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

**이 저장소의 불문율 몇 가지:**

- **테스트를 어디 둘지는 "안드로이드 런타임이 필요한가"로 정합니다.** 필요 없으면 `app/src/test`(빠름),
  필요하면 `app/src/androidTest`. 화면을 안 그리는 테스트라도 `Context`나 Room을 쓰면 후자입니다.
- **통과해도 회귀를 못 잡는 테스트는 일부러 안 만듭니다.** 실제 TTS 음성 재생 타이밍 같은 것들은
  자동화 범위 밖으로 두고, **왜 뺐는지를 TESTING.md에 적어둡니다.** 새로 뺄 게 생기면 똑같이 적어주세요.
- **버그를 고치면 그 버그를 겨냥한 테스트를 같이 남깁니다.** 지금 있는 테스트 중 상당수가 그렇게
  생겼습니다.
- **코드에 "왜"를 적습니다.** 이 저장소 주석은 "무엇을 하는지"보다 "왜 이렇게 했는지"가 많습니다.
  같은 함정을 다음 사람이 다시 밟지 않게 하는 게 목적입니다.

---

## 7. 첫 태스크로 좋은 것

[IDEAS.md](IDEAS.md)에 있는 실제 백로그 항목입니다:

> 챕터 점프에서, 챕터 패턴이 너무 가까이 붙어 있으면(예: 10~20줄 안에 다음 패턴) 그 사이를 N등분하지
> 말고 패턴에서 패턴으로 바로 넘어가게

**첫 태스크로 좋은 이유:** 고칠 곳이 `data/parser/ChapterJumpNavigator.kt`의 `breakpoints()` 함수
하나이고, 이건 **안드로이드 의존성이 전혀 없는 순수 함수**입니다. 기기도 UI도 필요 없고, 이미
`ChapterJumpNavigatorTest`가 있어서 거기에 케이스를 추가하며 개발할 수 있습니다. JVM 테스트라 몇 초 만에
돌아갑니다.

**접근 방법:**

1. `ChapterJumpNavigatorTest`를 먼저 열고, 기대하는 동작을 테스트로 씁니다
   (예: 챕터 두 개가 30자 간격이면 그 사이엔 등분 지점이 안 생겨야 한다).
2. 빨간불을 확인한 뒤 `breakpoints()`를 고칩니다.
3. "너무 가깝다"의 기준값은 상수로 빼고 **왜 그 값인지 주석**을 답니다.
4. `./gradlew :app:testDebugUnitTest` — 기존 테스트가 안 깨지는지 확인.

---

## 8. 자주 나오는 질문

**Q. 페이지 번호는 왜 아무 데도 없나요?**
저장하는 게 페이지가 아니라 "몇 번째 글자"라서 그렇습니다. 폰트만 바꿔도 페이지 번호는 의미가 달라지기
때문입니다. 자세한 건 DESIGN_RATIONALE.md §1.

**Q. `Paginator`가 왜 이렇게 복잡한가요?**
책 전체를 미리 페이지로 나누지 않고 **지금 보여줄 한 장만** 계산하기 때문입니다. 그 대가로 "이전
페이지"를 알아내는 로직이 두 갈래가 됐습니다(이력 스택 / 역산 추정). §2, §3.

**Q. DI 프레임워크(Hilt)가 왜 없나요?**
화면 두 개짜리 앱이라 생성자 주입 + Factory로 충분합니다. 테스트에 가짜 구현을 끼워 넣는 목적도 그것만으로
이미 됩니다(`FakeFolderBrowser`).

**Q. 동기화 서버를 왜 Go로 짰나요?**
사용자 PC에 런타임을 설치하게 만들지 않으려고요. Go는 표준 라이브러리만으로 HTTPS 서버와 인증서 생성이
되고, 결과물이 **exe 파일 하나**입니다.

**Q. 테스트가 갑자기 우수수 깨졌어요.**
먼저 의심할 것: (1) 폰이 잠겨 있거나 USB로 같이 붙어 있는지, (2) 기기에 남은 설정이 테스트에 새어들어간
건지(위 함정 ①). 코드보다 환경을 먼저 보세요.
