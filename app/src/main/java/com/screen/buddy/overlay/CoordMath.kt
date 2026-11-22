package com.screen.buddy.overlay

import android.graphics.Rect

/** Model coords are integers 0..1000, origin top-left of the screenshot. */
fun normToPx(norm: Int, dimensionPx: Int): Float =
    (norm.coerceIn(0, 1000) / 1000f) * dimensionPx

fun screenBounds(bounds: Rect): Pair<Int, Int> = bounds.width() to bounds.height()
