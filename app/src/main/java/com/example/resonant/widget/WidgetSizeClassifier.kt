package com.example.resonant.widget

/**
 * Size class from `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT` (dp), design D8:
 * width < 180 -> compact; else height < 110 -> horizontal; else large.
 */
object WidgetSizeClassifier {
    const val COMPACT_MAX_WIDTH_DP = 180
    const val HORIZONTAL_MAX_HEIGHT_DP = 110

    fun classify(minWidthDp: Int, minHeightDp: Int): WidgetSize = when {
        // Some launchers report nothing before the first layout: assume the default 4x1/4x2 look.
        minWidthDp <= 0 && minHeightDp <= 0 -> WidgetSize.HORIZONTAL
        minWidthDp < COMPACT_MAX_WIDTH_DP -> WidgetSize.COMPACT
        minHeightDp < HORIZONTAL_MAX_HEIGHT_DP -> WidgetSize.HORIZONTAL
        else -> WidgetSize.LARGE
    }
}
