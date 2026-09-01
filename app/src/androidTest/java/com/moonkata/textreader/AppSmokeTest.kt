package com.moonkata.textreader

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 앱이 실제로 켜져서 라이브러리 화면까지 크래시 없이 뜨는지 확인하는 최소한의 스모크 테스트.
 * 폴더를 이전에 골라둔 적 있는지(다른 테스트/실사용 이력)에 따라 FAB 문구가 "폴더 추가"/"폴더 변경"
 * 둘 중 하나로 갈리므로, 특정 상태를 강제로 리셋하는 대신 둘 중 하나만 있으면 통과하게 해서
 * 환경(다른 개발자 PC, 이전 테스트가 남긴 상태)에 관계없이 안정적으로 돌게 한다.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunches_libraryScaffoldRenders() {
        // ExtendedFloatingActionButton은 아이콘+텍스트를 하나의 병합된 시맨틱 노드로 합치지 않아서,
        // 병합 트리 기준으로 찾는 기본 동작으로는 안의 텍스트를 못 찾는다 — 병합 전 트리에서 찾는다.
        composeTestRule
            .onNode(hasText("폴더 추가") or hasText("폴더 변경"), useUnmergedTree = true)
            .assertExists()
    }
}
