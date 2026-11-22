package com.screen.buddy.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge to [BuddyAccessibilityService]. [isActive] is true while the user has Buddy switched
 * on under Settings > Accessibility; unlike screen sharing this survives pauses and restarts.
 */
@Singleton
class ScreenAccess @Inject constructor() {
    @Volatile private var service: BuddyAccessibilityService? = null

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun attach(s: BuddyAccessibilityService) {
        service = s
        _isActive.value = true
    }

    fun detach(s: BuddyAccessibilityService) {
        if (service === s) {
            service = null
            _isActive.value = false
        }
    }

    /** Whole-display screenshot as a software bitmap, or null if unavailable. */
    suspend fun screenshot(): Bitmap? {
        val s = service ?: return null
        repeat(4) {
            val (bitmap, error) = s.shoot()
            if (bitmap != null) return bitmap
            // The platform rate-limits screenshots; wait out the interval and try again.
            if (error != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                Timber.w("takeScreenshot failed code=%d", error)
                return null
            }
            delay(350)
        }
        return null
    }

    fun describeElements(geo: CaptureGeometry): String? = service?.describeElements(geo)
}
