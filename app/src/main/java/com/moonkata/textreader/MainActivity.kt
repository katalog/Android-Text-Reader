package com.moonkata.textreader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.moonkata.textreader.navigation.AppNavigation
import com.moonkata.textreader.ui.theme.TextReaderTheme

class MainActivity : ComponentActivity() {

    /** [ReaderScreen]이 켜져 있을 때만 등록되는 볼륨키 핸들러. true를 반환하면 이벤트를 소비(시스템 볼륨 토스트 억제). */
    var volumeKeyHandler: ((Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TextReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handler = volumeKeyHandler
        if (handler != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (handler(keyCode)) return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
