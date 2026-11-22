package com.screen.buddy.capture

import android.content.Context
import android.graphics.Bitmap
import android.view.WindowInsets
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class CaptureResult(
    val bitmap: Bitmap?,
    val jpeg: ByteArray?,
    val isSecureBlack: Boolean,
    val orientation: Int,
    /** Labelled on-screen elements with exact boxes in 0-1000 screenshot space, if readable. */
    val elements: String? = null,
)

/**
 * Full-display capture size plus the content region sent to the model
 * (system bars cropped). Drawing overlay maps 0-1000 into [offset + content].
 */
data class CaptureGeometry(
    val fullWidth: Int = 0,
    val fullHeight: Int = 0,
    val contentOffsetX: Int = 0,
    val contentOffsetY: Int = 0,
    val contentWidth: Int = 0,
    val contentHeight: Int = 0,
)

/**
 * One-shot screen captures through the accessibility service: a screenshot on demand, with no
 * running recording, no "sharing screen" indicator and no consent to renew.
 */
@Singleton
class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenAccess: ScreenAccess,
    private val frameEncoder: FrameEncoder,
) {
    @Volatile
    var lastDebugBitmap: Bitmap? = null
        private set

    private val captureMutex = Mutex()

    private val _geometry = MutableStateFlow(CaptureGeometry())
    val geometry: StateFlow<CaptureGeometry> = _geometry.asStateFlow()

    /** Recompute where app content sits on the display (also done on every capture). */
    fun refreshGeometry() {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds
        refreshGeometry(wm, bounds.width(), bounds.height())
    }

    suspend fun captureOnce(): CaptureResult = withContext(Dispatchers.IO) {
        captureMutex.withLock {
            val orientation = currentOrientation()
            val full = screenAccess.screenshot()
            if (full == null) {
                Timber.w("No screenshot (accessibility service off or capture failed)")
                return@withLock CaptureResult(null, null, false, orientation)
            }
            try {
                val wm = context.getSystemService(WindowManager::class.java)
                refreshGeometry(wm, full.width, full.height)
                val geo = _geometry.value
                val content = cropToContent(full, geo)
                if (content !== full) full.recycle()

                val secure = isMostlyBlack(content)
                lastDebugBitmap?.recycle()
                lastDebugBitmap = content.copy(content.config ?: Bitmap.Config.ARGB_8888, false)

                if (secure) {
                    content.recycle()
                    return@withLock CaptureResult(null, null, true, orientation)
                }

                val jpeg = frameEncoder.encode(content)
                CaptureResult(content, jpeg, false, orientation, screenAccess.describeElements(geo))
            } catch (e: Exception) {
                Timber.e(e, "Frame convert failed")
                CaptureResult(null, null, false, orientation)
            }
        }
    }

    private fun refreshGeometry(wm: WindowManager, width: Int, height: Int) {
        val insets = wm.currentWindowMetrics.windowInsets
        val bars = insets.getInsets(
            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
        )
        val nav = insets.getInsets(WindowInsets.Type.navigationBars())
        val top = bars.top
        val left = bars.left
        val right = bars.right
        val bottom = nav.bottom
        val contentW = (width - left - right).coerceAtLeast(1)
        val contentH = (height - top - bottom).coerceAtLeast(1)
        _geometry.value = CaptureGeometry(
            fullWidth = width,
            fullHeight = height,
            contentOffsetX = left,
            contentOffsetY = top,
            contentWidth = contentW,
            contentHeight = contentH,
        )
    }

    private fun cropToContent(full: Bitmap, geo: CaptureGeometry): Bitmap {
        if (geo.contentWidth <= 0 || geo.contentHeight <= 0) return full
        if (geo.contentOffsetX == 0 && geo.contentOffsetY == 0 &&
            geo.contentWidth == full.width && geo.contentHeight == full.height
        ) {
            return full
        }
        val w = geo.contentWidth.coerceAtMost(full.width - geo.contentOffsetX)
        val h = geo.contentHeight.coerceAtMost(full.height - geo.contentOffsetY)
        if (w <= 0 || h <= 0) return full
        return Bitmap.createBitmap(full, geo.contentOffsetX, geo.contentOffsetY, w, h)
    }

    fun isMostlyBlack(bitmap: Bitmap, sampleStep: Int = 32, threshold: Int = 8): Boolean {
        var sum = 0L
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sum += (r + g + b) / 3
                count++
                x += sampleStep
            }
            y += sampleStep
        }
        if (count == 0) return true
        return abs(sum / count) <= threshold
    }

    private fun currentOrientation(): Int =
        context.resources.configuration.orientation
}
