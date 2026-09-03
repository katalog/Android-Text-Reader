package com.moonkata.textreader.data.sync

import com.moonkata.textreader.BuildConfig

/**
 * VSCode 읽기 위치 동기화(.docs/VSCODE_SYNC_PLAN.md §1)용 Supabase 프로젝트 좌표.
 *
 * 값 자체는 비밀이 아니다 — Supabase의 신규 키 체계에서 publishable key는 애초에 클라이언트 번들에
 * 노출되는 걸 전제로 설계된 값이라(실제 방어선은 RLS 정책) 소스에 그대로 커밋해도 안전하다. 다만
 * 공개 저장소 히스토리에 그대로 남는 걸 피하려고 `local.properties`/CI 환경 변수로 주입한
 * `BuildConfig` 필드를 그대로 노출한다(`app/build.gradle.kts`, SYNC_MULTIUSER_PLAN.md 스테이지 3).
 * 값이 주입 안 되면 빈 문자열이고, 그 경우 VSCode 동기화 기능만 조용히 비활성화된다 — 빌드 자체는
 * 항상 성공한다. 진짜 지켜야 하는 공유 시크릿은 여기 없고 설정 화면에서 사용자가 직접 입력한다.
 */
object SupabaseConfig {
    val URL: String = BuildConfig.SUPABASE_URL
    val PUBLISHABLE_KEY: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY
}
