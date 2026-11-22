package com.screen.buddy.capture

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/** One-shot VirtualDisplay + ImageReader capture. Implemented in M3. */
@Singleton
class ScreenCaptureManager @Inject constructor(
    private val projectionHolder: ProjectionHolder,
) {
    fun captureFrame(): Bitmap? {
        // M3
        return null
    }
}
