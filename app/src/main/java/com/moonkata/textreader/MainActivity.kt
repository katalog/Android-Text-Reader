package com.moonkata.textreader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.moonkata.textreader.data.datastore.ReaderSettings
import com.moonkata.textreader.data.datastore.ReaderSettingsRepository
import com.moonkata.textreader.navigation.AppNavigation
import com.moonkata.textreader.ui.theme.TextReaderTheme

class MainActivity : ComponentActivity() {

    /** Volume key handler registered only while [ReaderScreen] is active. Returning true consumes the event (suppresses the system volume toast). */
    var volumeKeyHandler: ((Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsRepository = remember { ReaderSettingsRepository(applicationContext) }
            val settings by settingsRepository.settingsFlow.collectAsState(initial = ReaderSettings())
            TextReaderTheme(settings = settings) {
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
