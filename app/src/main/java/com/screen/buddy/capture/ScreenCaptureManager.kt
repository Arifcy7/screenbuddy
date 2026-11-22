package com.screen.buddy.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.abs

data class CaptureResult(
    val bitmap: Bitmap?,
    val jpeg: ByteArray?,
    val isSecureBlack: Boolean,
    val orientation: Int,
)

@Singleton
class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectionHolder: ProjectionHolder,
    private val frameEncoder: FrameEncoder,
) {
    @Volatile
    var lastDebugBitmap: Bitmap? = null
        private set

    suspend fun captureOnce(): CaptureResult = withContext(Dispatchers.IO) {
        val projection = projectionHolder.ensureProjection()
            ?: return@withContext CaptureResult(null, null, false, currentOrientation())

        val bounds = context.getSystemService(WindowManager::class.java)
            .currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val density = context.resources.displayMetrics.densityDpi
        val orientation = currentOrientation()

        val thread = HandlerThread("buddy-capture").also { it.start() }
        val handler = Handler(thread.looper)
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null

        try {
            val bitmap = suspendCancellableCoroutine { cont ->
                val resumed = AtomicBoolean(false)
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                reader!!.setOnImageAvailableListener({ imgReader ->
                    if (!resumed.compareAndSet(false, true)) {
                        imgReader.acquireLatestImage()?.close()
                        return@setOnImageAvailableListener
                    }
                    val image = imgReader.acquireLatestImage()
                    if (image == null) {
                        cont.resume(null)
                        return@setOnImageAvailableListener
                    }
                    try {
                        cont.resume(imageToBitmap(image, width, height))
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to convert image")
                        cont.resume(null)
                    } finally {
                        image.close()
                    }
                }, handler)

                display = projection.createVirtualDisplay(
                    "buddy-capture",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface,
                    null,
                    handler,
                )

                cont.invokeOnCancellation {
                    // cleaned in finally
                }
            }

            if (bitmap == null) {
                return@withContext CaptureResult(null, null, false, orientation)
            }

            val secure = isMostlyBlack(bitmap)
            lastDebugBitmap?.recycle()
            lastDebugBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

            if (secure) {
                bitmap.recycle()
                return@withContext CaptureResult(null, null, true, orientation)
            }

            val jpeg = frameEncoder.encode(bitmap)
            CaptureResult(bitmap, jpeg, false, orientation)
        } finally {
            try {
                display?.release()
            } catch (_: Exception) {
            }
            try {
                reader?.close()
            } catch (_: Exception) {
            }
            thread.quitSafely()
        }
    }

    private fun imageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap {
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

    /** Mean luminance near zero over a sampled grid ⇒ FLAG_SECURE black frame. */
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
