package com.moonkata.textreader.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Timer-based auto page turn — operates independently of TTS auto-advance (the two are used mutually exclusively). */
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
