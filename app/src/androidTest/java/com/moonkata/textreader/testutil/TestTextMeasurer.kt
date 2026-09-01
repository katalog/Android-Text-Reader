package com.moonkata.textreader.testutil

import android.content.Context
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * 컴포저블 트리 없이도 실제로 글자 폭/줄바꿈을 측정하는 [TextMeasurer]를 직접 만든다.
 * `ReaderViewModel`/`Paginator`처럼 페이지네이션 로직만 검증하고 싶을 때, 화면 전체를 렌더링하지
 * 않고도 진짜 측정값(가짜/고정값이 아닌)으로 테스트할 수 있게 해준다.
 */
object TestTextMeasurer {
    fun create(context: Context): TextMeasurer {
        val density = Density(
            density = context.resources.displayMetrics.density,
            fontScale = context.resources.configuration.fontScale,
        )
        return TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = density,
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }
}
