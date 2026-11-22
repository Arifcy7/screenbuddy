package com.screen.buddy.capture

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class FrameEncoder @Inject constructor() {
    fun encode(bitmap: Bitmap, maxSidePx: Int = 1280, quality: Int = 70): ByteArray {
        val scaled = scale(bitmap, maxSidePx)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        if (scaled !== bitmap) scaled.recycle()
        return stream.toByteArray()
    }

    private fun scale(bitmap: Bitmap, maxSidePx: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSidePx) return bitmap
        val scale = maxSidePx.toFloat() / longest
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
