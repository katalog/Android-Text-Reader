package com.moonkata.textreader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moonkata.textreader.ui.theme.ReaderColors

@Composable
fun ReaderBottomBar(
    progress: Float,
    readerColors: ReaderColors,
) {
    Surface(
        color = readerColors.background,
        contentColor = readerColors.text,
        shadowElevation = 4.dp,
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text("%.3f%%".format(progress * 100), style = MaterialTheme.typography.bodySmall)
        }
    }
}
