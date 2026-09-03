# 계획: 동기화 기능 공개 배포 준비 (멀티테넌시 + QR 페어링 + 시크릿 관리)

**상태**: 스테이지 1~6(Supabase 멀티테넌시 전환, 요청량 튜닝, Supabase 설정값 저장소 노출 정리,
안드로이드 공용 QR 스캐너, VSCode 읽기위치 동기화 QR 페어링, PC 파일 동기화 QR 페어링) ✅
완료(2026-09-03). `Android-Text-Reader`/`vscode-moonkata-reader-sync` 둘 다 `sync-qrcode` 브랜치에서
작업 중. [VSCODE_SYNC_PLAN.md](VSCODE_SYNC_PLAN.md)와 [PC_SYNC_SERVER_PLAN.md](PC_SYNC_SERVER_PLAN.md)가
각각 완료한 1인 개발자용 버전을 여러 사용자가 동시에 안전하게 쓸 수 있는 형태로 확장하는 후속 계획.
다음은 스테이지 7(통합 실기기 검증) — 코드 작업은 여기서 끝, 남은 건 전부 실기기 확인.

## 배경

두 동기화 기능(§VSCode 읽기 위치, §PC 파일)을 공개 배포할 계획인데, 검토 중 세 가지가 드러났다:

1. **`reading_positions` 테이블에 사용자 구분이 없다.** 실제 RLS 정책(`text-reader-supabase-schema.sql`,
   private)을 열어보니, `x-moonkata-secret` 헤더가 **하드코딩된 문자열 하나**와 정확히 일치해야만
   통과한다:
   ```sql
   using (
     current_setting('request.headers', true)::json ->> 'x-moonkata-secret'
       = '65831b77501d3f3c1993daef182f4d34c5926f18942cfebd482d45c475f371c2'
   )
   ```
   즉 지금은 개발자 본인 외 **아무도 이 기능을 쓸 수 없다**(다른 시크릿을 입력하면 그냥 401/403).
   설령 이 검사를 "아무 문자열이나 허용"으로 완화해도, 테이블 자체가 `relative_path` 하나만으로
   행을 식별하기 때문에 서로 다른 사용자가 우연히 같은 상대경로(`무협/1권.txt` 등 흔한 구조)를 가진
   책을 읽으면 서로의 위치를 덮어쓴다.
2. **시크릿을 양쪽(폰/PC)에 손으로 복붙하는 과정이 번거롭고 오타/불일치가 나기 쉽다.**
3. 사용량 확대 대비 자연스럽게 나온 두 가지 추가 요구: **DB 쓰기/읽기 빈도를 더 줄이는 것**, 그리고
   **Supabase URL/publishable key를 공개 저장소 히스토리에서 빼는 것**(실제 보안 경계는 RLS+시크릿이라
   이 값 자체는 노출돼도 무방하다고 이미 결론 낸 상태 — [VSCODE_SYNC_PLAN.md](VSCODE_SYNC_PLAN.md) §1 —
   그래도 공개 저장소에 그대로 보이는 걸 피하고 싶다는 요청).

**사용량 자체는 걱정만큼 급한 문제가 아니다** — Supabase 무료 티어는 요청 수가 아니라 월 이그레스(5GB)가
병목이고, 이 앱의 데이터 크기(사용자당 파일 몇 개, 행 하나에 수백 바이트)라면 활성 사용자 수천 명까지도
여유가 있다는 걸 이미 계산으로 확인했다. 그래서 이 계획의 핵심은 "쿼터 대비책"이 아니라 **"여러 사용자가
써도 서로 안 섞이게"**(스테이지 1, 진짜 필수)와 **"사용자 경험 개선"**(QR 페어링)이다.

## 목표 (이번 범위)

- VSCode 읽기 위치 동기화 + PC 파일 동기화 둘 다, 서로 다른 사용자가 각자 독립적으로 켜서 써도 데이터가
  안 섞이게 한다.
- 페어링을 시크릿 복붙 대신 **QR 스캔 한 번**으로 끝낸다 — 두 기능 다 같은 안드로이드 스캐너 컴포넌트를
  공유한다.
- 원격 DB 쓰기/읽기 빈도를 지금보다 더 낮춘다.
- Supabase URL/publishable key를 저장소 커밋 히스토리에서 뺀다(단, 컴파일된 앱/vsix에서 완전히
  못 뽑아내게 하는 건 원리적으로 불가능 — 그건 목표가 아니라는 것을 명확히 해둔다).

## 스테이지

### 스테이지 1 — Supabase 멀티테넌시 전환 ✅ 완료 (2026-09-03)

다른 모든 스테이지보다 먼저 끝나야 한다 — 이게 안 끝난 채로 공개 배포하면 첫 두 번째 사용자부터 바로
충돌이 난다.

**실제 적용 SQL** (`text-reader-supabase-schema.sql`, private — SQL Editor에서 실행 완료. 아래는 적용
중 겪은 문제까지 반영한 최종본):

```sql
create extension if not exists pgcrypto;

-- 1. PK를 relative_path 단독 → (user_key, relative_path) 복합으로
alter table reading_positions drop constraint reading_positions_pkey;
alter table reading_positions add column user_key text;

-- 기존 행은 지금까지 유일하게 통과 가능했던 고정 시크릿으로 쓰인 것이므로, PK를 걸기 전에 그 해시로 백필
-- (컬럼만 추가하고 바로 PK를 걸면 기존 행의 user_key가 NULL이라 NOT NULL 위반으로 실패함 — 적용 중 발견)
update reading_positions set user_key = encode(
  digest('65831b77501d3f3c1993daef182f4d34c5926f18942cfebd482d45c475f371c2', 'sha256'), 'hex'
);

alter table reading_positions add primary key (user_key, relative_path);

-- 2. INSERT/UPDATE 전에 요청 헤더로부터 user_key 자동 계산 (클라이언트 변경 불필요)
create or replace function set_reading_positions_user_key()
returns trigger language plpgsql as $$
begin
  new.user_key := encode(digest(
    current_setting('request.headers', true)::json ->> 'x-moonkata-secret', 'sha256'
  ), 'hex');
  return new;
end;
$$;

drop trigger if exists trg_reading_positions_user_key on reading_positions;
create trigger trg_reading_positions_user_key
before insert or update on reading_positions
for each row execute function set_reading_positions_user_key();

-- 3. RLS: "고정 문자열과 일치" → "요청 헤더 해시와 내 user_key가 같은 행만"
drop policy if exists "shared secret required" on reading_positions;
create policy "own user_key only" on reading_positions for all
using (user_key = encode(digest(current_setting('request.headers', true)::json ->> 'x-moonkata-secret', 'sha256'), 'hex'))
with check (user_key = encode(digest(current_setting('request.headers', true)::json ->> 'x-moonkata-secret', 'sha256'), 'hex'));

-- 4. 사용자당 최근 갱신된 10개 파일만 유지 (계속 보는 파일은 매번 updated_at이 갱신되므로 항상 남음)
create or replace function prune_old_reading_positions() returns trigger as $$
begin
  delete from reading_positions
  where user_key = new.user_key
    and relative_path not in (
      select relative_path from reading_positions
      where user_key = new.user_key
      order by updated_at desc
      limit 10
    );
  return new;
end;
$$ language plpgsql;

drop trigger if exists trg_prune_reading_positions on reading_positions;
create trigger trg_prune_reading_positions
after insert or update on reading_positions
for each row execute function prune_old_reading_positions();
```

- **보안 모델이 바뀐다는 걸 분명히 인지한다**: "고정 비밀번호 하나가 전체를 지킨다"에서 "각자 고른
  문자열의 해시가 곧 자기 데이터 접근권이다"로 바뀐다. 데이터 민감도가 낮다는 전제(그냥 소설 읽은
  위치)는 그대로 유효하니 이 완화 자체는 허용 가능 — [VSCODE_SYNC_PLAN.md](VSCODE_SYNC_PLAN.md) §1의
  기존 판단과 같은 선상.
- **개발자 본인의 기존 시크릿도 그대로 유지된다** — 적용 순간부터 그 시크릿의 해시가 그냥 하나의
  `user_key`가 될 뿐, 기존 행이 삭제/이관될 필요는 없다(트리거가 UPDATE 시에도 재계산하므로 다음
  upsert 때 자동으로 채워짐 — 마이그레이션 스크립트 불필요).
- **클라이언트(안드로이드/VSCode) 코드 변경 없음** — 둘 다 이미 매 요청에 시크릿 헤더를 보내고 있고,
  `user_key` 계산은 전부 서버 트리거가 담당한다.
- **10개 제한값**은 SQL에 하드코딩(`limit 10`) — 나중에 필요해지면 설정 가능하게 승격.

- **적용 전, 라이브 DB에 이 문서가 모르는 트리거/함수/정책이 있는지 반드시 먼저 확인한다** — 실제로
  `pg_trigger`/`pg_proc`/`pg_policies`를 조회해서 이 문서의 전제(트리거 1개 `set_reading_positions_updated_at`,
  정책 1개 `shared secret required`)와 라이브 DB가 정확히 일치하는지 검증한 뒤 적용함(drift 없음
  확인됨).

**완료 기준 → 충족, curl로 실측 검증함**: 서로 다른 두 시크릿(`testA`/`testB`)으로 같은
`relative_path`(`collision_test.txt`)에 각각 111, 222를 upsert한 뒤 각자 자기 시크릿으로 다시
조회했더니 정확히 자기 값만 보임(`testA` → 111, `testB` → 222) — 이전 스키마였다면 둘 다 222로
덮였어야 할 상황. 11번째 파일 pruning 자동 삭제는 실사용(사용자 다수·최근목록 10개 초과)이 생기면
그때 자연 검증되는 성격이라 별도 인위적 테스트는 생략.

### 스테이지 2 — 요청량 튜닝 ✅ 완료 (2026-09-03)

지금도 이벤트 기반(폴링 아님)이라 여유가 크지만, 사용자가 늘어날 걸 감안해 가볍게 더 줄여뒀다.

- **체크포인트 간격 1분 → 5분**: `ReaderViewModel.remoteSyncIdleMs`(`60_000L` → `300_000L`) /
  `positionTracker.ts`의 `CHECKPOINT_IDLE_MS`(`60_000` → `300_000`). 화면 이탈 시 즉시 반영 경로는
  그대로라 체감 지연은 없음 — 오래 머무를 때만 덜 자주 확인.
- **원격 조회 쿨다운 추가**: 마지막 조회 후 30초 이내면 다시 안 물어보게 — 안드로이드는
  `ReaderViewModel.remoteFetchCooldownMs`(`checkRemoteAndMaybeNotify` 진입 시 체크), VSCode는
  `positionTracker.ts`의 `REMOTE_FETCH_COOLDOWN_MS`(`DocState.lastRemoteFetchAt`으로 문서별 추적,
  `checkRemote` 진입 시 체크) — 빠른 백그라운드↔포그라운드/탭 전환 반복 시 중복 조회 방지.

**완료 기준 → 충족**: `./gradlew :app:compileDebugKotlin` 클린, VSCode 확장 `npm run compile`/`lint`
클린. 두 상수(체크포인트 간격 5분, 조회 쿨다운 30초) 모두 안드로이드/VSCode 양쪽에서 같은 값으로
대칭 유지(기존 500자 데드존 상수와 같은 패턴). 실기기/Extension Development Host로 새 간격이
실제 타이밍대로 동작하는지는 스테이지 7(통합 실기기 검증)에서 다른 변경들과 함께 확인.

### 스테이지 3 — Supabase 설정값 저장소 노출 정리 ✅ 완료 (2026-09-03)

- **안드로이드**: `app/build.gradle.kts`가 `SUPABASE_URL`/`SUPABASE_PUBLISHABLE_KEY`를 환경 변수 →
  `local.properties`(gitignore 대상) 순으로 읽어 `buildConfigField`로 주입(`buildFeatures.buildConfig
  = true` 추가). `SupabaseConfig.kt`는 이제 리터럴이 아니라 `BuildConfig.SUPABASE_URL` /
  `BuildConfig.SUPABASE_PUBLISHABLE_KEY`를 그대로 노출. `.github/workflows/release.yml`의
  "Build release APK" 스텝에 같은 이름의 레포 시크릿 2개를 env로 추가 — 기존 `RELEASE_KEYSTORE_PATH`
  등과 완전히 같은 패턴.
- **VSCode 확장**: 이쪽은 번들러가 없어(`tsc`로 직접 컴파일) Gradle의 `buildConfigField` 같은 내장
  주입 지점이 없다. 대신 `scripts/generate-supabase-config.js`가 환경 변수 → `.env.local`(gitignore
  대상, `KEY=VALUE` 줄바꿈 형식) 순으로 읽어 `src/supabaseConfig.ts`를 그때그때 새로 써주고,
  `npm run compile`/`watch`가 `tsc` 실행 전에 이 스크립트를 먼저 돌리도록 배선(`vscode:prepublish`도
  `compile`을 타므로 `vsce package`에도 자동 적용). `src/supabaseConfig.ts`와 `.env.local`은
  `.gitignore`에 추가하고 기존 추적 파일은 `git rm --cached`로 내림 — 저장소엔 이제 이 두 값이 전혀
  안 남는다.
- **로컬 개발**: 두 저장소 다 이 값들이 없으면 **빌드가 실패하지 않고 빈 문자열로 채워진다** — 계획
  초안에서는 "없으면 빌드 실패"로 잡았었는데, 다시 보니 이 프로젝트가 VSCode 동기화의 모든 실패
  경로를 이미 "조용히 기능만 비활성화, 로컬 흐름엔 영향 없음"으로 일관되게 다루고 있어서
  (`ReadingPositionSyncClient`의 모든 호출이 `runCatching`으로 감싸져 있음 —
  [VSCODE_SYNC_PLAN.md](VSCODE_SYNC_PLAN.md) §4 참고) 이 값만 예외적으로 빌드를 막을 이유가 없다고
  판단해 릴리스 서명과 같은 "조용한 폴백" 쪽으로 바꿨다. 빈 URL로 나가는 요청은 그냥 실패해서 기존의
  "시크릿 미검증"과 똑같이 처리된다.
- **한계를 문서에 명시한다**: 이 변경은 "GitHub 저장소 히스토리에서 안 보이게" 하는 것이지, "배포된
  APK/vsix에서 못 뽑아내게" 하는 게 아니다 — 컴파일된 산출물에 최종적으로 박히는 값이라 디컴파일하면
  여전히 나온다(그래서 애초에 이 값은 노출돼도 되게 설계된 값 — 진짜 방어선은 스테이지 1의 RLS다).

**완료 기준 → 충족**: 안드로이드 `./gradlew :app:compileDebugKotlin`/`testDebugUnitTest` 클린(이
저장소의 `local.properties`에 실값을 채워 로컬 빌드도 계속 동작). VSCode `npm run compile`(생성된
`src/supabaseConfig.ts`에 실값이 정확히 채워짐 확인)/`lint` 클린. `git status`로 두 저장소 모두
소스에 리터럴 값이 더 이상 없는 것 확인.

### 스테이지 4 — 안드로이드 공용 QR 스캐너 컴포넌트 ✅ 완료 (2026-09-03)

스테이지 5·6이 공유해서 쓸 카메라 스캔 화면을 먼저 만들었다.

- **라이브러리**: `com.google.mlkit:barcode-scanning:17.3.0`(모델을 앱에 번들 — 오프라인 동작,
  Play Services 동적 다운로드 변형은 안 씀) + CameraX `1.5.1`(`camera-core`/`camera-camera2`/
  `camera-lifecycle`/`camera-view`).
- **파싱**: `data/sync/QrPairingPayload.kt` — sealed class `VscodeSync`/`PcSync`, `type` 필드로
  분기하는 공용 스키마:
  ```json
  {"type": "vscode_sync", "secret": "..."}
  {"type": "pc_sync", "host": "192.168.0.12:58221", "secret": "...", "fingerprint": "AB:CD:..."}
  ```
  필수 필드 누락/파싱 실패/모르는 `type`은 전부 `null`로 통일(예외를 던지지 않음) — 호출부가 "잘못된
  QR"로 한 갈래로 처리할 수 있게. `app/src/test`에 `QrPairingPayloadTest`(6케이스: 정상 파싱 2종,
  콜론이 여러 개 섞인 fingerprint/host 값도 정확히 보존되는지, 모르는 type/필드 누락/깨진 JSON/type
  필드 자체 누락 전부 null).
  > **⚠️ JVM 유닛 테스트에서 `org.json`을 쓰려면 실구현이 따로 필요하다** — Android SDK가 제공하는
  > `org.json`은 유닛 테스트 환경(android.jar 스텁)에서는 메서드 호출 시 예외를 던진다. 이 프로젝트에
  > Robolectric이 없어서, `org.json:json:20260814`(진짜 참조 구현, 같은 패키지명)을
  > `testImplementation`으로만 추가해 테스트 클래스패스에서 실구현이 쓰이게 했다 — 앱 실행 시(기기
  > 위)는 여전히 Android가 제공하는 클래스가 쓰인다. `data/sync/*`의 다른 JSON 파싱 로직도 앞으로
  > 필요하면 같은 방식으로 순수 유닛 테스트할 수 있다.
- **UI**: `ui/qr/QrScannerDialog.kt` — 전체 화면 `Dialog`(내비게이션 그래프에 새 라우트를 안 추가해도
  아무 화면에서나 띄울 수 있음). 카메라 권한이 없으면 요청하고, 거부되면 바로 `onDismiss` 호출 —
  호출한 쪽이 기존 수동 입력 필드로 폴백하게 함. 권한이 있으면 CameraX `Preview` +
  `ImageAnalysis`(`STRATEGY_KEEP_ONLY_LATEST`)를 뷰의 라이프사이클에 바인딩하고, 프레임마다 ML Kit
  `BarcodeScanner`로 QR을 읽어 `QrPairingPayload.parse`가 성공하는 순간 딱 한 번만(`handled` 플래그)
  `onResult`를 호출한다. 파싱에 실패한 QR은 조용히 무시하고 계속 스캔 — 에러 토스트로 사용자를 방해하지
  않음.
- `AndroidManifest.xml`에 `CAMERA` 권한 + `<uses-feature ... required="false">`(카메라 없는 기기도
  설치 가능해야 하므로 선택 기능으로 선언).

**완료 기준 → 충족**: `QrPairingPayloadTest` 6케이스 전부 통과, `compileDebugKotlin` 클린. 실제
카메라로 QR을 스캔하는 것 자체는 실기기가 필요해 자동화 범위 밖(TESTING.md의 "실제 시스템 SAF 폴더
선택창 자동화... 보류"와 같은 이유) — 스테이지 7(통합 실기기 검증)에서 스테이지 5·6과 함께 실기기로
확인한다.

> **⚠️ 스테이지 7에서 실제로 잡은 회귀**: `connectedDebugAndroidTest` 전체를 처음 돌렸을 때
> `kspDebugAndroidTestKotlin`부터 실패했다 — CameraX(`camera-core`)가 `androidx.concurrent:
> concurrent-futures`를 `1.1.0`으로 strictly 고정하는데, `espresso-core`/`androidx.test:core`는
> `1.2.0`을 요구해서 AGP의 "consistent resolution"이 androidTest 클래스패스를 못 맞췄다(이 스테이지
> 전엔 CameraX가 없어서 안 드러났던 문제). `app/build.gradle.kts`에 `configurations.all {
> resolutionStrategy { force(...) } }`로 두 아티팩트를 `1.2.0`으로 강제해서 해결 — 메인 컴파일만
> 클린하다고 안심하면 안 되고, androidTest까지 실제로 돌려봐야 이런 클래스패스 충돌이 드러난다는
> 교훈.

### 스테이지 5 — VSCode 읽기위치 동기화 QR 페어링 ✅ 완료 (2026-09-03)

- **VSCode**: 새 커맨드 `moonkata-reader-sync.showPairingQr`("Moonkata Sync: QR로 연결",
  `src/pairingQr.ts`) — 이미 저장된 시크릿이 있으면 그대로 재사용하고, 없을 때만
  `crypto.randomBytes(24).toString('hex')`로 새로 생성한다.
  > ⚠️ **재생성이 아니라 "다시 보여주기"인 이유**: 매번 새 시크릿을 만들면 이미 이 시크릿으로 페어링된
  > 다른 기기(예전에 스캔해둔 폰)가 조용히 끊어진다(스테이지 1에서 시크릿 해시가 곧 `user_key`라
  > 시크릿이 바뀌면 완전히 다른 네임스페이스가 됨). 기존 시크릿을 재사용하면 두 번째 폰을 추가로
  > 페어링할 때도 이 커맨드를 안전하게 다시 실행할 수 있다.
  > 
  > 시크릿을 저장한 뒤 VSCode 쪽에서도 `testConnection()`을 바로 한 번 돌려서(Android가 스캔하기 전에도)
  > 상태 표시줄이 정확한 상태를 보이게 한다. QR 페이로드는 `{"type":"vscode_sync","secret":"..."}`를
  > `qrcode`(순수 JS, `toString({type:'svg'})`)로 SVG를 만들어 `enableScripts: false`인 정적 webview에
  > 인라인으로 그린다(외부 CDN·클라이언트 JS 없이 완전히 로컬에서 렌더링). 카메라를 못 쓰는 사용자를
  > 위해 시크릿 원문도 같이 표시.
  > 
  > `secretManager.ts`에 `setSecret()`(저장 + 상태 표시줄 갱신만, 검증은 안 함)을 새로 뽑아
  > `promptForSecret()`과 QR 플로우가 공유하게 정리.
  > 
  > `@types/qrcode`의 타입 선언이 (안 쓰는) canvas 오버로드용으로 `HTMLCanvasElement`를 참조하는데,
  > 이 확장은 Node 전용이라 `tsconfig.json`의 `lib`에 DOM이 없어 타입 에러가 났다 — 우리 코드가 아니라
  > 서드파티 `.d.ts` 문제라 `skipLibCheck: true`로 우회(실제 쓰는 API는 canvas와 무관).
- **안드로이드**: `QuickSettingsSheet`의 VSCode 동기화 섹션에 "QR로 연결" 버튼 추가 — 스테이지 4
  스캐너를 띄우고, `vscode_sync` 페이로드를 받으면 시크릿 필드를 자동 채운 뒤 즉시 "연결 테스트"까지
  같은 코드 경로(`runSyncTest`로 버튼 클릭과 공유)로 자동 실행 — 스캔 한 번으로 끝난다. 기존 "시크릿
  직접 입력" 필드는 그대로 유지(QR 실패 시 폴백, 이미 개발자 본인 워크플로도 이 방식).

**완료 기준 → 코드 레벨 충족**: 안드로이드 `compileDebugKotlin`/`testDebugUnitTest` 클린, VSCode
`npm run compile`/`lint` 클린. **실기기 E2E(VSCode에서 QR 표시 → 안드로이드로 스캔 → 별도 입력 없이
"연결됨" 배지까지 자동으로 뜨는 것)는 스테이지 7에서 스테이지 6과 함께 확인** — 카메라·webview 렌더링
둘 다 실제 화면이 필요해 지금 단계에서는 자동화로 검증할 수 없다.

### 스테이지 6 — PC 파일 동기화 QR 페어링 ✅ 완료 (2026-09-03)

- **PC 서버**(`external_library/sync_server/`, Go): 이미 떠 있는 HTTPS 리스너에 `GET /pair`(인증
  불필요 — 시크릿 자체가 이미 이 응답 안에 들어있어서 지킬 게 없고, `/list`·`/file`처럼 시크릿을
  요구하면 "QR을 보려고 시크릿이 먼저 필요"해지는 모순이 생김) 엔드포인트를 `pair.go`에 새로 추가.
  현재 PC의 LAN IPv4(`net.InterfaceAddrs()`에서 루프백이 아닌 첫 IPv4)와 포트, `AppState`의 시크릿,
  시작할 때 로드한 인증서의 SHA-256 지문(`certificateFingerprint`, `tls.go`)을 JSON으로 묶어
  `github.com/skip2/go-qrcode`로 PNG QR을 만들고, `data:` URI로 인라인 임베드한 정적 HTML 페이지를
  서빙한다(카메라 없는 사용자를 위해 주소/시크릿 원문도 같이 표시). 트레이 메뉴에 "동기화 QR 보기"
  항목을 추가해 `cmd /c start`로 기본 브라우저에서 `https://127.0.0.1:<port>/pair`를 연다(새 GUI
  프레임워크 없이 Windows 내장 명령 재사용, 기존 `pickFolder`/`copyToClipboard`와 같은 패턴).
  > ⚠️ **자체 서명 인증서라 브라우저가 "안전하지 않은 연결" 경고를 보여준다** — TOFU 방식 자체의
  > 성격이라(§PC_SYNC_SERVER_PLAN.md §5) 이 화면만 따로 없앨 방법이 없다. 그래서 QR 보기를 누르면
  > 먼저 안내 메시지로 "고급 → 계속 진행"을 눌러야 한다고 알려준다. 처음 한 번만 겪는 문제(브라우저가
  > 그 인증서를 기억함)라 별도 HTTP-only 리스너를 추가하는 복잡도까지는 감수하지 않기로 함.
  > **⚠️ v1.5.0-beta.1 실사용 중 발견한 진짜 버그 두 개(v1.5.0-beta.2에서 수정)**:
  > 1. **QR 이미지가 안 보이고 깨진 아이콘만 떴다.** 원인: Go의 `html/template`이 `<img src>`처럼
  >    URL이 오는 자리에 별도 살균(sanitize) 필터를 거는데, 이 필터가 `data:` URI를 신뢰하지 않아서
  >    그냥 문자열로 넘기면 렌더링 시 `#ZgotmplZ`로 통째로 지워진다. `QrDataURI` 필드 타입을
  >    `string`에서 `template.URL`("이미 검증된 URL"이라는 표시, 우리가 직접 만든 값이라 안전)로
  >    바꿔서 해결.
  > 2. **QR에 실린 주소가 `169.254.x.x`(링크-로컬, DHCP 실패 시 자동 할당되는 주소)였다.**
  >    `net.InterfaceAddrs()`를 순서대로 훑어 "첫 번째 루프백 아님 IPv4"를 그냥 골랐더니, VPN/가상
  >    어댑터가 진짜 Wi-Fi/이더넷보다 먼저 걸렸다. 실제로 인터넷 방향 트래픽이 나가는 인터페이스를
  >    UDP 소켓으로 라우팅 테이블에 물어보는 표준 Go 트릭(`net.Dial("udp", "8.8.8.8:80")`으로 실제
  >    패킷은 안 보내고 `LocalAddr()`만 읽음)으로 바꿔서 해결, 완전 오프라인이라 이것도 실패하면
  >    링크-로컬을 제외한 인터페이스 훑기로 폴백.
- **안드로이드**: 스테이지 4 스캐너로 `pc_sync` 페이로드를 받으면:
  - 호스트가 이미 QR에 들어있으므로 **서브넷 스캔("PC 찾기")을 생략**
  - 지문도 QR에 들어있으므로, 지금처럼 "lenient TLS로 먼저 접속해 그때 본 지문을 신뢰(TOFU)"하는 대신
    새 `LibraryViewModel.testPcSyncConnectionWithFingerprint(host, secret, fingerprint)`가
    **처음부터 pinned TLS로 연결**한다 — QR이라는 별도 채널로 이미 지문을 받았기 때문에, TOFU보다
    신뢰 근거가 더 강해진다(TOFU는 "처음 본 걸 믿는다"인데, QR은 "PC가 직접 보여준 값을 믿는다"이므로
    중간자가 끼어들 여지가 원천적으로 더 적음). 성공하면 QR의 지문을 그대로
    `pcSyncPinnedFingerprint`로 커밋.
  - `PcSyncSheet`에 "QR로 연결" 버튼 추가 — "PC 주소"/"공유 시크릿" 필드와 "연결 테스트" 버튼을
    자동으로 채우고 실행한 것과 같은 효과를 한 번에 낸다.
- 기존 "PC 찾기 + 수동 시크릿 입력" 흐름은 폴백으로 유지.

**완료 기준 → 코드 레벨 충족**: `go build ./...`/`go test ./...`/`go vet ./...` 클린(Go 서버),
안드로이드 `compileDebugKotlin`/`testDebugUnitTest` 클린. **실기기 E2E(PC에서 QR 표시 → 안드로이드
스캔 → 서브넷 스캔 없이 바로 "연결됨" → "지금 동기화"까지, QR의 지문과 실제 서버 인증서 지문이 정말
일치하는지 `openssl s_client`로 교차 검증)는 스테이지 7에서 확인** — 실제 카메라·브라우저·두 기기
간 네트워크가 필요해 지금 단계에서는 자동화로 검증할 수 없다.

### 스테이지 7 — 통합 실기기 검증

코드 작업(스테이지 1~6)은 전부 끝났고, 자동화로 못 잡는 부분만 남았다. 아래 체크리스트 순서대로
확인하면 된다.

**A. VSCode QR 페어링 (스테이지 4·5)**
- [ ] VSCode에서 "Moonkata Sync: QR로 연결" 실행 → webview에 QR + 시크릿 텍스트가 뜨는지
- [ ] 안드로이드 퀵설정 → "QR로 연결" → 카메라 권한 요청이 뜨는지(최초 1회) → 거부하면 스캐너가 닫히고 수동 입력 필드로 돌아오는지
- [ ] 권한을 허용하고 QR을 스캔 → 시크릿 필드가 자동으로 채워지고 "연결 테스트"가 자동 실행돼 "연결됨"까지 뜨는지(입력 조작 없이)
- [ ] VSCode 쪽 상태 표시줄도 "연결됨"으로 바뀌는지(QR 생성 시 자체적으로 한 번 테스트하므로)
- [ ] **QR로 연결 후에도 기존 "시크릿 직접 입력" 필드가 정상 동작하는지**(폴백 경로가 안 깨졌는지)

**B. PC 파일 동기화 QR 페어링 (스테이지 4·6)**
- [ ] PC에서 `moonkata-sync-server` 실행 → 트레이 메뉴 "동기화 QR 보기" 클릭 → 기본 브라우저가 열리는지
- [ ] **"안전하지 않은 연결" 경고가 뜨는지 확인** → "고급" → "계속 진행"으로 실제 QR 페이지가 보이는지(사전 안내 메시지와 실제 동작이 맞는지)
- [ ] 안드로이드 서재 → PC 동기화 시트 → "QR로 연결" → 스캔 → **"PC 찾기" 버튼을 누르지 않았는데도** 호스트/시크릿이 채워지고 "연결됨"까지 자동으로 뜨는지
- [ ] "지금 동기화"가 정상 동작하는지(파일이 실제로 받아지는지)
- [ ] `openssl s_client -connect <PC IP>:58221 -showcerts 2>/dev/null | openssl x509 -fingerprint -sha256 -noout`로 뽑은 지문이 QR에 실렸던 지문과 정확히 같은지 교차 검증
- [ ] 기존 "PC 찾기 + 수동 입력" 흐름도 여전히 동작하는지(폴백 경로)

**C. Supabase 멀티테넌시 (스테이지 1) — 실제 클라이언트로 재현**
- [ ] 서로 다른 시크릿 두 세트("가상 사용자" 두 명)로 VSCode 동기화를 동시에 켜서, 같은 상대경로를
  가진 책이 있어도 서로 안 섞이는지(curl로는 이미 검증했으니, 여기선 실제 앱/확장 UI로 같은 결과가
  나오는지만 재확인)

**D. 요청량 튜닝 (스테이지 2)**
- [ ] 페이지를 계속 넘겨도 5분 안에는 원격에 안 올라가고, 화면을 벗어나면 즉시 반영되는지(체크포인트
  간격이 1분에서 5분으로 바뀐 것 확인 — 예전 E2E 로그와 같은 방식으로 타이밍 관찰)
- [ ] 앱을 빠르게 백그라운드↔포그라운드 전환해도(최근 앱 목록을 열었다 바로 닫기 등) 30초 안에는
  원격 조회가 중복으로 안 나가는지

**E. 스테이지 3(시크릿 저장소 노출 정리) — 배포 파이프라인**
- [ ] GitHub 저장소에 `SUPABASE_URL`/`SUPABASE_PUBLISHABLE_KEY` 시크릿을 등록했는지(아직 안 했다면
  다음 릴리스 태그부터 VSCode 동기화가 빈 값으로 빌드되어 비활성화된 채로 배포됨)
- [ ] 등록 후 실제 릴리스 태그를 하나 눌러(또는 워크플로우 수동 트리거로) 빌드된 APK에서 VSCode
  동기화가 정상 동작하는지

**회귀 확인 → 완료(2026-09-03, `Pixel_6` API 33 에뮬레이터)**: `testDebugUnitTest` +
`connectedDebugAndroidTest` 전체 142개 중 141개 통과. 1개(`LibraryZipAndBreadcrumbNavigationTest.
breadcrumb_navigatesBackUpAndReloadsTheCorrectListing`)가 전체 스위트에서만 실패했는데, 그 클래스만
따로 다시 돌리니 통과 — 오늘 건드린 코드(브레드크럼 네비게이션은 이 세션에서 손댄 적 없음)와 무관한
기존 flaky 테스트로 판단. 클래스패스 충돌 하나는 실제로 잡아서 위에 기록한 대로 고침.

## 열린 질문

1. **최근 파일 유지 개수(10)를 나중에 설정 가능하게 할지** — 지금은 SQL 상수로 고정하고, 실사용
   피드백이 생기면 그때 설정 항목으로 승격하는 쪽으로 잠정 결론.
2. **QR 스캔 실패 시 폴백을 계속 1급 경로로 유지할지, 아니면 나중엔 숨겨진 대안으로만 남길지** — 지금은
   1급으로 유지(카메라 없는 폼팩터/권한 거부 대비, 개발자 본인도 지금 방식을 계속 씀).
3. **스테이지 1(멀티테넌시)과 스테이지 3(키 은닉) 중 어느 걸 먼저 할지** — 순서는 안 바뀌어도 되지만,
   스테이지 1이 진짜 배포 차단 요소이고 스테이지 3은 위생 개선에 가까우므로 우선순위는 스테이지 1이
   높다. 이 문서의 번호 순서가 곧 권장 착수 순서.
