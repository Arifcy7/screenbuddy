package com.screen.buddy.overlay

import android.graphics.Rect

/**
 * Model coords are integers 0..1000 over the **same** full-display bitmap we capture.
 * Origin is top-left of that bitmap (includes status bar / cutout region).
 */
fun normToPx(norm: Int, dimensionPx: Int): Float =
    (norm.coerceIn(0, 1000) / 1000f) * dimensionPx

fun screenBounds(bounds: Rect): Pair<Int, Int> = bounds.width() to bounds.height()

/** Parse "x,y;x,y;..." point lists from tool args. */
fun parsePointList(raw: String): List<Pair<Int, Int>> {
    if (raw.isBlank()) return emptyList()
    return raw.split(';').mapNotNull { pair ->
        val parts = pair.trim().split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return@mapNotNull null
        val x = parts[0].toFloatOrNull()?.toInt() ?: return@mapNotNull null
        val y = parts[1].toFloatOrNull()?.toInt() ?: return@mapNotNull null
        x.coerceIn(0, 1000) to y.coerceIn(0, 1000)
    }
}
