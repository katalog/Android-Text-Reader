package com.moonkata.textreader.testutil

/**
 * ComposeTestRule 없이(예: 화면 렌더링 없는 ReaderViewModel 단독 테스트) 비동기 상태 변화를 기다릴 때 쓰는
 * 단순 폴링 헬퍼. Compose 테스트 안에서는 이 대신 `composeTestRule.waitUntil`을 쓰면 된다.
 */
fun waitUntilTrue(timeoutMs: Long = 5_000, intervalMs: Long = 50, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(intervalMs)
    }
    check(condition()) { "조건이 ${timeoutMs}ms 안에 충족되지 않음" }
}
