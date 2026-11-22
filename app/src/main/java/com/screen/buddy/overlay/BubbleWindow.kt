package com.screen.buddy.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.screen.buddy.live.SessionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs

class BubbleWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val lifecycleOwner: OverlayLifecycleOwner,
    private val sessionState: StateFlow<SessionState>,
) {
    private val _gestures = MutableSharedFlow<BubbleGesture>(extraBufferCapacity = 16)
    val gestures: SharedFlow<BubbleGesture> = _gestures.asSharedFlow()

    private var composeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var holding = false
    private var holdRunnable: Runnable? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (composeView != null) return
        val metrics = windowManager.currentWindowMetrics.bounds
        val sizePx = (56 * context.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.width() - sizePx - 24
            y = metrics.height() / 3
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        params = lp

        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val state by sessionState.collectAsState()
                BubbleContent(state)
            }
            setOnTouchListener { v, event ->
                handleTouch(event)
            }
        }
        composeView = view
        windowManager.addView(view, lp)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val lp = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = lp.x
                startY = lp.y
                moved = false
                holding = false
                holdRunnable = Runnable {
                    holding = true
                    _gestures.tryEmit(BubbleGesture.HoldStart)
                }
                composeView?.postDelayed(holdRunnable, 280)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                if (abs(dx) > 12 || abs(dy) > 12) {
                    moved = true
                    composeView?.removeCallbacks(holdRunnable)
                    lp.x = startX + dx
                    lp.y = startY + dy
                    windowManager.updateViewLayout(composeView, lp)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                composeView?.removeCallbacks(holdRunnable)
                if (holding) {
                    _gestures.tryEmit(BubbleGesture.HoldEnd)
                } else if (!moved) {
                    _gestures.tryEmit(BubbleGesture.Tap)
                } else {
                    snapToEdge()
                }
                return true
            }
        }
        return false
    }

    private fun snapToEdge() {
        val lp = params ?: return
        val bounds = windowManager.currentWindowMetrics.bounds
        val mid = bounds.width() / 2
        lp.x = if (lp.x + (lp.width / 2) < mid) 16 else bounds.width() - lp.width - 16
        lp.y = lp.y.coerceIn(0, bounds.height() - lp.height)
        windowManager.updateViewLayout(composeView, lp)
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

@Composable
private fun BubbleContent(state: SessionState) {
    val color = when (state) {
        is SessionState.Idle -> Color(0xFF2A3544)
        is SessionState.Connecting -> Color(0xFFF5B942)
        is SessionState.Listening -> Color(0xFF2DD4BF)
        is SessionState.Thinking -> Color(0xFF42A5F5)
        is SessionState.Speaking -> Color(0xFFFF6B6B)
        is SessionState.Error -> Color(0xFFFF5252)
    }
    val active = state !is SessionState.Idle
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.14f else 1f,
        animationSpec = infiniteRepeatable(tween(if (active) 650 else 2_000), RepeatMode.Reverse),
        label = "scale",
    )
    val ring by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = if (active) 0.85f else 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "ring",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .scale(scale * 1.18f)
                .clip(CircleShape)
                .background(color.copy(alpha = ring * 0.28f)),
        )
        Box(
            modifier = Modifier
                .size(56.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "B",
                color = Color(0xFF070B10),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
