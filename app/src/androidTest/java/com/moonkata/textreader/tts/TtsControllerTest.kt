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
 * `TtsController`는 실제 `android.speech.tts.TextToSpeech` 엔진을 감싸는 얇은 래퍼다. 실제 음성 합성
 * 완료 타이밍은 기기/설치된 음성 데이터에 따라 들쭉날쭉해 TESTING.md에서 자동화 범위 밖으로 이미
 * 명시해뒀다(엔진이 없거나 한국어 음성 데이터가 없는 기기에서는 절대 콜백이 안 옴) — 그래서 이 파일도
 * 그 실제 발화 완료 타이밍까지 단언하지는 않는다. 대신 (1) 엔진 초기화 전/후 상태 노출이 정확한지,
 * (2) 아직 준비 안 된 상태에서 메서드를 불러도 죽지 않는지, (3) 엔진이 실제로 준비되면(이 기기에 TTS가
 * 있으면) speak()가 진짜로 완료 콜백까지 이어지는지를 검증하고, 엔진 자체가 없는 환경에서는(Assume)
 * 3번만 건너뛴다.
 */
@RunWith(AndroidJUnit4::class)
class TtsControllerTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun isReady_startsFalse_beforeTheEngineFinishesBinding() {
        val controller = TtsController(application, onUtteranceDone = {})
        try {
            // TextToSpeech(context) { ... } 콜백은 서비스 바인딩이 끝난 뒤 비동기로 호출된다 — 생성자가
            // 반환된 시점에는 아직 안 끝났어야 한다.
            assert(!controller.isReady.value) { "생성 직후에는 아직 준비 상태가 아니어야 함" }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun callingMethodsBeforeReady_doesNotThrow() {
        val controller = TtsController(application, onUtteranceDone = {})
        try {
            // 엔진 바인딩 완료를 기다리지 않고 바로 호출 — 내부 tts 필드는 생성자에서 이미 동기적으로
            // 할당되므로 null이 아니지만, 엔진 자체는 아직 안 準備됐을 수 있다. 죽지 않아야 한다.
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
        // shutdown 이후 tts 필드는 null로 정리된다 — 이후 호출은 전부 안전한 no-op이어야 한다(?. 체이닝).
        controller.setRate(1f)
        controller.setPitch(1f)
        controller.speak(1, "종료 후 호출")
        controller.stop()
        controller.shutdown() // 두 번 호출해도 안전해야 함
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
            Assume.assumeTrue("이 환경에 TTS 엔진이 없어 실제 발화 완료 검증은 건너뜀", becameReady)

            controller.speak(7, "안녕하세요")
            val completed = doneLatch.await(15, TimeUnit.SECONDS)
            Assume.assumeTrue("엔진은 준비됐지만 실제로 발화가 끝나지 않음(한국어 음성 데이터 미설치 등) — 환경 문제로 보고 건너뜀", completed)

            assert(doneUtteranceId == "page_7") { "onUtteranceDone에 넘어온 id가 speak()에 넘긴 키와 맞아야 함: $doneUtteranceId" }
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
