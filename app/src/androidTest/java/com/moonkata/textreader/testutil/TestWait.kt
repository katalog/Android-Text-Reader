package com.moonkata.textreader.testutil

/**
 * A simple polling helper for waiting on an async state change without a ComposeTestRule (e.g. a
 * standalone ReaderViewModel test with no screen rendering). Inside a Compose test, use
 * `composeTestRule.waitUntil` instead.
 */
fun waitUntilTrue(timeoutMs: Long = 5_000, intervalMs: Long = 50, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(intervalMs)
    }
    check(condition()) { "Condition was not satisfied within ${timeoutMs}ms" }
}
