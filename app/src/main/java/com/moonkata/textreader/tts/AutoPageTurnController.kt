package com.moonkata.textreader.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 타이머 기반 자동 페이지 넘김 — TTS 자동 넘김과는 별개로 동작(둘은 상호 배타적으로 사용). */
class AutoPageTurnController(
    private val scope: CoroutineScope,
    private val onTick: () -> Unit,
) {
    private var job: Job? = null

    fun start(intervalSeconds: Int) {
        stop()
        job = scope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000L)
                onTick()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
