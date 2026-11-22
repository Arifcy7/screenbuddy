package com.screen.buddy.overlay.draw

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.screen.buddy.overlay.DrawOp
import com.screen.buddy.overlay.normToPx
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DrawLayer(
    ops: List<DrawOp>,
    screenWidthPx: Int,
    screenHeightPx: Int,
    fadeAfterMs: Long,
    onFadeComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(ops) {
        if (ops.isEmpty()) return@LaunchedEffect
        alpha.snapTo(1f)
        delay(fadeAfterMs)
        alpha.animateTo(0f, tween(600))
        onFadeComplete()
    }

    Box(modifier = modifier.fillMaxSize()) {
        ops.forEach { op ->
            when (op) {
                is DrawOp.Ring -> Ring(op, screenWidthPx, screenHeightPx, alpha.value)
                is DrawOp.HighlightBox -> HighlightBox(op, screenWidthPx, screenHeightPx, alpha.value)
                is DrawOp.Arrow -> Arrow(op, screenWidthPx, screenHeightPx, alpha.value)
            }
        }
    }
}

@Composable
fun Ring(op: DrawOp.Ring, screenWidthPx: Int, screenHeightPx: Int, alpha: Float) {
    val cx = normToPx(op.x, screenWidthPx)
    val cy = normToPx(op.y, screenHeightPx)
    val color = Color(0xFFE53935).copy(alpha = alpha)
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = color,
            radius = 28.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 4.dp.toPx()),
        )
        drawCircle(
            color = color.copy(alpha = alpha * 0.35f),
            radius = 10.dp.toPx(),
            center = Offset(cx, cy),
        )
    }
    op.label?.let { Label(it, cx, cy + with(LocalDensity.current) { 36.dp.toPx() }, alpha) }
}

@Composable
fun HighlightBox(op: DrawOp.HighlightBox, screenWidthPx: Int, screenHeightPx: Int, alpha: Float) {
    val left = normToPx(op.xmin, screenWidthPx)
    val top = normToPx(op.ymin, screenHeightPx)
    val right = normToPx(op.xmax, screenWidthPx)
    val bottom = normToPx(op.ymax, screenHeightPx)
    val color = Color(0xFF1E88E5).copy(alpha = alpha)
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
            style = Stroke(width = 4.dp.toPx()),
        )
    }
    op.label?.let { Label(it, left, top - with(LocalDensity.current) { 8.dp.toPx() }, alpha) }
}

@Composable
fun Arrow(op: DrawOp.Arrow, screenWidthPx: Int, screenHeightPx: Int, alpha: Float) {
    val from = Offset(normToPx(op.fromX, screenWidthPx), normToPx(op.fromY, screenHeightPx))
    val to = Offset(normToPx(op.toX, screenWidthPx), normToPx(op.toY, screenHeightPx))
    val color = Color(0xFF43A047).copy(alpha = alpha)
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawLine(color, from, to, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        val angle = atan2(to.y - from.y, to.x - from.x)
        val head = 22.dp.toPx()
        val path = Path().apply {
            moveTo(to.x, to.y)
            lineTo(
                to.x - head * cos(angle - 0.45f).toFloat(),
                to.y - head * sin(angle - 0.45f).toFloat(),
            )
            lineTo(
                to.x - head * cos(angle + 0.45f).toFloat(),
                to.y - head * sin(angle + 0.45f).toFloat(),
            )
            close()
        }
        drawPath(path, color)
    }
    op.label?.let { Label(it, to.x, to.y - with(LocalDensity.current) { 20.dp.toPx() }, alpha) }
}

@Composable
fun Label(text: String, xPx: Float, yPx: Float, alpha: Float) {
    Text(
        text = text,
        color = Color.White.copy(alpha = alpha),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.offset { IntOffset(xPx.toInt(), yPx.toInt()) },
    )
}
