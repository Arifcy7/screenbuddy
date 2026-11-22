package com.screen.buddy.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class CaptureResult(
    val bitmap: Bitmap?,
    val jpeg: ByteArray?,
    val isSecureBlack: Boolean,
    val orientation: Int,
)

/**
 * Keeps one VirtualDisplay for the life of the MediaProjection.
 * Android 14+ forbids createVirtualDisplay more than once on the same projection instance.
 */
@Singleton
class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectionHolder: ProjectionHolder,
    private val frameEncoder: FrameEncoder,
) {
    @Volatile
    var lastDebugBitmap: Bitmap? = null
        private set

    private val lock = Any()
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayWidth = 0
    private var displayHeight = 0
    private val latestImage = AtomicReference<Image?>(null)

    fun startPipeline() {
        synchronized(lock) {
            if (virtualDisplay != null) return
            val projection = projectionHolder.ensureProjection() ?: run {
                Timber.w("startPipeline: no MediaProjection")
                return
            }
            val bounds = context.getSystemService(WindowManager::class.java)
                .currentWindowMetrics.bounds
            val width = bounds.width()
            val height = bounds.height()
            val density = context.resources.displayMetrics.densityDpi

            val thread = HandlerThread("buddy-capture").also { it.start() }
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ imgReader ->
                val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                latestImage.getAndSet(image)?.close()
            }, handler)

            try {
                val display = projection.createVirtualDisplay(
                    "buddy-capture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler,
                )
                captureThread = thread
                captureHandler = handler
                imageReader = reader
                virtualDisplay = display
                displayWidth = width
                displayHeight = height
                Timber.d("Capture pipeline started %dx%d", width, height)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start capture pipeline")
                reader.close()
                thread.quitSafely()
                projectionHolder.revokeConsent()
            }
        }
    }

    fun stopPipeline() {
        synchronized(lock) {
            latestImage.getAndSet(null)?.close()
            try {
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            try {
                imageReader?.close()
            } catch (_: Exception) {
            }
            captureThread?.quitSafely()
            virtualDisplay = null
            imageReader = null
            captureHandler = null
            captureThread = null
            displayWidth = 0
            displayHeight = 0
            Timber.d("Capture pipeline stopped")
        }
    }

    suspend fun captureOnce(): CaptureResult = withContext(Dispatchers.IO) {
        if (virtualDisplay == null) {
            startPipeline()
        }
        if (virtualDisplay == null) {
            return@withContext CaptureResult(null, null, false, currentOrientation())
        }

        val orientation = currentOrientation()
        // Drop stale frame, wait for a fresh one after summon.
        latestImage.getAndSet(null)?.close()
        val image = withTimeoutOrNull(1_500) {
            var frame: Image? = null
            while (frame == null) {
                frame = latestImage.getAndSet(null)
                if (frame == null) delay(16)
            }
            frame
        }

        if (image == null) {
            Timber.w("Timed out waiting for screen frame")
            return@withContext CaptureResult(null, null, false, orientation)
        }

        try {
            val bitmap = imageToBitmap(image, displayWidth, displayHeight)
            val secure = isMostlyBlack(bitmap)
            lastDebugBitmap?.recycle()
            lastDebugBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

            if (secure) {
                bitmap.recycle()
                return@withContext CaptureResult(null, null, true, orientation)
            }

            val jpeg = frameEncoder.encode(bitmap)
            CaptureResult(bitmap, jpeg, false, orientation)
        } catch (e: Exception) {
            Timber.e(e, "Frame convert failed")
            CaptureResult(null, null, false, orientation)
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }
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
