package com.screen.buddy.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.screen.buddy.overlay.draw.DrawLayer
import kotlinx.coroutines.flow.StateFlow

class DrawingWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val lifecycleOwner: OverlayLifecycleOwner,
    private val drawingState: DrawingState,
    private val drawingDurationSec: StateFlow<Int>,
) {
    private var composeView: ComposeView? = null

    fun show() {
        if (composeView != null) return
        val bounds = windowManager.currentWindowMetrics.bounds
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            width = bounds.width()
            height = bounds.height()
        }

        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val ops by drawingState.ops.collectAsState()
                val durationSec by drawingDurationSec.collectAsState()
                val currentOrientation = context.resources.configuration.orientation
                val filtered = ops.filter {
                    it.captureOrientation == 0 ||
                        it.captureOrientation == currentOrientation ||
                        currentOrientation == Configuration.ORIENTATION_UNDEFINED
                }
                DrawLayer(
                    ops = filtered,
                    screenWidthPx = bounds.width(),
                    screenHeightPx = bounds.height(),
                    fadeAfterMs = durationSec * 1000L,
                    onFadeComplete = { drawingState.clear() },
                )
            }
        }
        composeView = view
        windowManager.addView(view, lp)
    }

    fun hide() {
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        composeView = null
    }
}
