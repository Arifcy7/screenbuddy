package com.screen.buddy.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Buddy's eyes. Used for exactly two read-only things, and only when the user summons Buddy:
 * taking a screenshot, and reading the labels and positions of what is on screen so marks can
 * land exactly on them. It never performs gestures, clicks or text entry.
 */
@AndroidEntryPoint
class BuddyAccessibilityService : AccessibilityService() {

    @Inject lateinit var screenAccess: ScreenAccess

    private val executor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenAccess.attach(this)
        Timber.i("Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        screenAccess.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        screenAccess.detach(this)
        executor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Software bitmap of the whole display, or null plus the platform error code. */
    suspend fun shoot(): Pair<Bitmap?, Int> = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hw ->
                                hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Screenshot copy failed")
                            null
                        } finally {
                            buffer.close()
                        }
                        if (cont.isActive) cont.resume(bitmap to 0) else bitmap?.recycle()
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null to errorCode)
                    }
                },
            )
        } catch (e: Exception) {
            Timber.w(e, "takeScreenshot threw")
            if (cont.isActive) cont.resume(null to -1)
        }
    }

    /**
     * Labelled elements of the foreground app as lines of
     * `"label" [xmin,ymin,xmax,ymax]` in the 0-1000 space of the cropped screenshot.
     */
    fun describeElements(geo: CaptureGeometry, maxElements: Int = 50): String? {
        if (geo.contentWidth <= 0 || geo.contentHeight <= 0) return null
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Timber.w(e, "rootInActiveWindow failed")
            null
        } ?: return null

        val lines = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rect = Rect()
        var visited = 0
        val screenArea = geo.contentWidth.toLong() * geo.contentHeight
        try {
            while (queue.isNotEmpty() && lines.size < maxElements && visited < 2_000) {
                val node = queue.removeFirst()
                visited++
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::add)
                }
                if (!node.isVisibleToUser || node.isPassword) continue
                val label = (node.text ?: node.contentDescription)
                    ?.toString()?.replace('\n', ' ')?.trim().orEmpty()
                if (label.isEmpty()) continue
                node.getBoundsInScreen(rect)
                val left = rect.left - geo.contentOffsetX
                val top = rect.top - geo.contentOffsetY
                val right = rect.right - geo.contentOffsetX
                val bottom = rect.bottom - geo.contentOffsetY
                if (right <= 0 || bottom <= 0 || left >= geo.contentWidth || top >= geo.contentHeight) continue
                val area = (right - left).toLong() * (bottom - top)
                if (area <= 0 || area > screenArea * 9 / 10) continue // empty, or a whole-page container
                fun nx(v: Int) = (v.coerceIn(0, geo.contentWidth) * 1000L / geo.contentWidth).toInt()
                fun ny(v: Int) = (v.coerceIn(0, geo.contentHeight) * 1000L / geo.contentHeight).toInt()
                val kind = when {
                    node.isEditable -> " (input)"
                    node.isClickable -> " (tappable)"
                    else -> ""
                }
                lines.add("\"${label.take(40)}\"$kind [${nx(left)},${ny(top)},${nx(right)},${ny(bottom)}]")
            }
        } catch (e: Exception) {
            Timber.w(e, "Reading screen elements failed")
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
