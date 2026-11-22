package com.screen.buddy.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.screen.buddy.capture.CaptureGeometry
import com.screen.buddy.overlay.draw.DrawLayer
import kotlinx.coroutines.flow.StateFlow

class DrawingWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val lifecycleOwner: OverlayLifecycleOwner,
    private val drawingState: DrawingState,
    private val captureGeometry: StateFlow<CaptureGeometry>,
) {
    private var composeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

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
            x = 0
            y = 0
            width = bounds.width()
            height = bounds.height()
            alpha = 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }

        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val ops by drawingState.ops.collectAsState()
                val geo by captureGeometry.collectAsState()
                val fullW = geo.fullWidth.takeIf { it > 0 } ?: bounds.width()
                val fullH = geo.fullHeight.takeIf { it > 0 } ?: bounds.height()
                Box(Modifier.fillMaxSize()) {
                    DrawLayer(
                        ops = ops,
                        contentOffsetXPx = geo.contentOffsetX,
                        contentOffsetYPx = geo.contentOffsetY,
                        contentWidthPx = geo.contentWidth.takeIf { it > 0 } ?: fullW,
                        contentHeightPx = geo.contentHeight.takeIf { it > 0 } ?: fullH,
                    )
                }
            }
        }
        composeView = view
        params = lp
        windowManager.addView(view, lp)
    }

    fun bringToFront() {
        val view = composeView ?: return
        val lp = params ?: return
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
        view.invalidate()
    }

    fun hide() {
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        composeView = null
        params = null
    }
}
