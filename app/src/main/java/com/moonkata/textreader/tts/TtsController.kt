package com.moonkata.textreader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class TtsController(
    context: Context,
    private val onUtteranceDone: (utteranceId: String) -> Unit,
) {
    private var tts: TextToSpeech? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val _isKoreanAvailable = MutableStateFlow(true)
    val isKoreanAvailable: StateFlow<Boolean> = _isKoreanAvailable

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                _isKoreanAvailable.value = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        // 이 콜백은 TTS 내부 스레드에서 호출됨 — 호출자가 메인 스레드로 마샬링해야 함
                        utteranceId?.let(onUtteranceDone)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
                _isReady.value = true
            }
        }
    }

    fun setRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }

    fun speak(utteranceKey: Int, text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "page_$utteranceKey")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
