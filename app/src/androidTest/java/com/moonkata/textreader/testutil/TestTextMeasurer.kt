package com.moonkata.textreader.testutil

import android.content.Context
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Builds a [TextMeasurer] directly that measures real character width/line-wrapping without a
 * composable tree. Lets logic like `ReaderViewModel`/`Paginator`, where only the pagination logic
 * needs verifying, be tested against genuine measurements (not fake/fixed values) without
 * rendering the whole screen.
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
