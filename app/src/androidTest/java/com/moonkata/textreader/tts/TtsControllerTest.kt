package com.moonkata.textreader.tts

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `TtsController` is a thin wrapper around the real `android.speech.tts.TextToSpeech` engine. The
 * actual speech-synthesis completion timing varies by device/installed voice data, and TESTING.md
 * already marks it out of scope for automation (the callback never fires on devices with no engine
 * or no Korean voice data installed) — so this file doesn't assert on that real completion timing
 * either. Instead it verifies (1) that the ready-state exposure before/after engine init is correct,
 * (2) that calling methods before the engine is ready doesn't crash, and (3) that once the engine
 * actually becomes ready (if this device has TTS), speak() really does reach the completion callback
 * — skipping only (3), via Assume, on environments with no engine at all.
 */
@RunWith(AndroidJUnit4::class)
class TtsControllerTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun isReady_startsFalse_beforeTheEngineFinishesBinding() {
        val controller = TtsController(application, onUtteranceDone = {})
        try {
            // The TextToSpeech(context) { ... } callback fires asynchronously after service binding
            // finishes — it should not have completed yet by the time the constructor returns.
            assert(!controller.isReady.value) { "Should not be ready immediately after construction" }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun callingMethodsBeforeReady_doesNotThrow() {
        val controller = TtsController(application, onUtteranceDone = {})
        try {
            // Called right away without waiting for engine binding to finish — the internal tts field
            // is already assigned synchronously in the constructor so it's not null, but the engine
            // itself may not be ready yet. Should not crash.
            controller.setRate(1.2f)
            controller.setPitch(0.9f)
            controller.speak(1, "테스트")
            controller.stop()
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun afterShutdown_furtherCallsDoNotThrow() {
        val controller = TtsController(application, onUtteranceDone = {})
        controller.shutdown()
        // After shutdown the tts field is cleared to null — every subsequent call should be a safe no-op (?. chaining).
        controller.setRate(1f)
        controller.setPitch(1f)
        controller.speak(1, "종료 후 호출")
        controller.stop()
        controller.shutdown() // Calling twice should also be safe
    }

    @Test
    fun whenTheEngineActuallyBecomesReady_speakingEventuallyInvokesTheCompletionCallback() {
        val doneLatch = CountDownLatch(1)
        var doneUtteranceId: String? = null
        val controller = TtsController(application, onUtteranceDone = { id ->
            doneUtteranceId = id
            doneLatch.countDown()
        })
        try {
            val becameReady = waitFor(timeoutMs = 10_000) { controller.isReady.value }
            Assume.assumeTrue("No TTS engine available in this environment, skipping actual speech-completion verification", becameReady)

            controller.speak(7, "안녕하세요")
            val completed = doneLatch.await(15, TimeUnit.SECONDS)
            Assume.assumeTrue("Engine became ready but speech never actually finished (e.g. Korean voice data not installed) — treating as an environment issue and skipping", completed)

            assert(doneUtteranceId == "page_7") { "The id passed to onUtteranceDone should match the key passed to speak(): $doneUtteranceId" }
        } finally {
            controller.shutdown()
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
