package com.screen.buddy.capture

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/** Downscale + JPEG encode. Implemented in M3. */
@Singleton
class FrameEncoder @Inject constructor() {
    fun encode(bitmap: Bitmap, maxSidePx: Int = 1280, quality: Int = 70): ByteArray {
        // M3
        return ByteArray(0)
    }
}
