package com.screen.buddy.overlay.draw

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screen.buddy.overlay.DrawOp
import com.screen.buddy.overlay.Drawn
import com.screen.buddy.overlay.NBox
import com.screen.buddy.overlay.Palette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

private val PanelFill = Color(0xF2111722)
private val PanelBorder = Color(0x33FFFFFF)
private val PillFill = Color(0xE6131A26)
private val Ink = Color(0xFFE8EDF5)
private val InkDim = Color(0xFF93A1B5)
private val GridLine = Color(0x1FFFFFFF)
private val AxisLine = Color(0x99FFFFFF)

/**
 * Renders Buddy's drawings in one Canvas. Each op animates in (lines are traced, text is
 * typed, diagrams build box by box) so the board feels drawn live rather than pasted.
 */
@Composable
fun DrawLayer(
    ops: List<Drawn>,
    contentOffsetXPx: Int,
    contentOffsetYPx: Int,
    contentWidthPx: Int,
    contentHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }

    // Tick only while something is still animating, so a static board costs nothing.
    LaunchedEffect(ops) {
        while (true) {
            withFrameMillis { now = SystemClock.uptimeMillis() }
            if (ops.none { it.needsFrames(now) }) break
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val board = Board(
            measurer = measurer,
            ox = contentOffsetXPx.toFloat(),
            oy = contentOffsetYPx.toFloat(),
            w = contentWidthPx.coerceAtLeast(1).toFloat(),
            h = contentHeightPx.coerceAtLeast(1).toFloat(),
        )
        for (drawn in ops) {
            val age = (now - drawn.createdAt).coerceAtLeast(0)
            when (val op = drawn.op) {
                is DrawOp.Ring -> ring(board, op, age)
                is DrawOp.HighlightBox -> highlight(board, op, age)
                is DrawOp.Arrow -> arrow(board, op, age)
                is DrawOp.TextMark -> textMark(board, op, age)
                is DrawOp.Polyline -> polyline(board, op, age)
                is DrawOp.Shape -> shape(board, op, age)
                is DrawOp.Plot -> plot(board, op, age)
                is DrawOp.Surface3D -> surface(board, op, age)
                is DrawOp.Solid3D -> solid(board, op, age)
                is DrawOp.Diagram -> diagram(board, op, age)
                is DrawOp.NeuralNet -> neuralNet(board, op, age)
                is DrawOp.Picture -> picture(board, op, age)
                is DrawOp.Wireframe -> wireframe(board, op, age)
            }
        }
    }
}

private fun Drawn.needsFrames(now: Long): Boolean = when (val o = op) {
    is DrawOp.Surface3D, is DrawOp.Solid3D -> true
    is DrawOp.Picture -> o.bitmap == null && !o.failed
    is DrawOp.Diagram -> now - createdAt < 800 + 160L * (o.nodes.size + o.edges.size)
    is DrawOp.TextMark -> now - createdAt < 400 + 30L * o.text.length
    else -> now - createdAt < 1_600
}

/** Maps the model's 0-1000 space onto the part of the display that shows app content. */
private class Board(val measurer: TextMeasurer, val ox: Float, val oy: Float, val w: Float, val h: Float) {
    fun x(n: Int) = ox + n.coerceIn(0, 1000) / 1000f * w
    fun y(n: Int) = oy + n.coerceIn(0, 1000) / 1000f * h
    fun point(nx: Int, ny: Int) = Offset(x(nx), y(ny))
    fun rect(b: NBox) = Rect(x(b.left), y(b.top), x(b.right), y(b.bottom))
    val bounds get() = Rect(ox, oy, ox + w, oy + h)
}

/** 0..1 with an ease-out, for an op of the given age. */
private fun progress(ageMs: Long, durationMs: Int, delayMs: Long = 0): Float {
    val t = ((ageMs - delayMs).toFloat() / durationMs).coerceIn(0f, 1f)
    val inv = 1f - t
    return 1f - inv * inv * inv
}

private fun Color.a(alpha: Float) = copy(alpha = (this.alpha * alpha).coerceIn(0f, 1f))

// -------------------------------------------------------------------------------------------
// Text helpers
// -------------------------------------------------------------------------------------------

private enum class AlignH { START, CENTER, END }
private enum class AlignV { TOP, CENTER, BOTTOM }

private fun DrawScope.label(
    board: Board,
    text: String,
    at: Offset,
    color: Color,
    sizeSp: Float = 12f,
    bold: Boolean = false,
    alignH: AlignH = AlignH.START,
    alignV: AlignV = AlignV.TOP,
    maxWidth: Float = Float.MAX_VALUE,
    maxLines: Int = Int.MAX_VALUE,
    pill: Boolean = false,
    centerText: Boolean = false,
): Rect {
    if (text.isEmpty()) return Rect(at, Size.Zero)
    val padH = if (pill) 8.dp.toPx() else 0f
    val padV = if (pill) 4.dp.toPx() else 0f
    val limit = (min(maxWidth, board.w) - 2 * padH).coerceAtLeast(24f)
    val layout = board.measurer.measure(
        text = text,
        style = TextStyle(
            color = color,
            fontSize = sizeSp.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = if (centerText) TextAlign.Center else TextAlign.Start,
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
        constraints = Constraints(maxWidth = limit.roundToInt()),
    )
    val boxW = layout.size.width + 2 * padH
    val boxH = layout.size.height + 2 * padV
    var left = when (alignH) {
        AlignH.START -> at.x
        AlignH.CENTER -> at.x - boxW / 2
        AlignH.END -> at.x - boxW
    }
    var top = when (alignV) {
        AlignV.TOP -> at.y
        AlignV.CENTER -> at.y - boxH / 2
        AlignV.BOTTOM -> at.y - boxH
    }
    if (pill) {
        // Keep callouts on screen.
        val b = board.bounds
        left = left.coerceIn(b.left, (b.right - boxW).coerceAtLeast(b.left))
        top = top.coerceIn(b.top, (b.bottom - boxH).coerceAtLeast(b.top))
        drawRoundRect(
            color = PillFill.a(color.alpha),
            topLeft = Offset(left, top),
            size = Size(boxW, boxH),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
    }
    drawText(layout, topLeft = Offset(left + padH, top + padV))
    return Rect(left, top, left + boxW, top + boxH)
}

/** Draw the first [fraction] of a path, so lines look traced by a pen. */
private fun DrawScope.trace(path: Path, color: Color, widthPx: Float, fraction: Float) {
    val style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (fraction >= 1f) {
        drawPath(path, color, style = style)
        return
    }
    if (fraction <= 0f) return
    val measure = PathMeasure()
    measure.setPath(path, false)
    val partial = Path()
    measure.getSegment(0f, measure.length * fraction, partial, true)
    drawPath(partial, color, style = style)
}

private fun DrawScope.arrowHead(tip: Offset, from: Offset, color: Color, sizePx: Float) {
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - sizePx * cos(angle - 0.42f), tip.y - sizePx * sin(angle - 0.42f))
        lineTo(tip.x - sizePx * cos(angle + 0.42f), tip.y - sizePx * sin(angle + 0.42f))
        close()
    }
    drawPath(path, color)
}

// -------------------------------------------------------------------------------------------
// Pointers
// -------------------------------------------------------------------------------------------

private fun DrawScope.ring(board: Board, op: DrawOp.Ring, age: Long) {
    val c = board.point(op.x, op.y)
    val color = Color(op.color)
    val p = progress(age, 320)
    val r = 30.dp.toPx()
    // Ripple that draws the eye to the spot.
    val ripple = ((age % 1_400) / 1_400f)
    if (age < 4_200) {
        drawCircle(color.a(0.55f * (1f - ripple)), r * (1f + ripple * 0.9f), c, style = Stroke(3.dp.toPx()))
    }
    val radius = r * (1.5f - 0.5f * p)
    drawCircle(Color.Black.a(0.35f * p), radius, c, style = Stroke(8.dp.toPx()))
    drawCircle(color.a(p), radius, c, style = Stroke(5.dp.toPx()))
    drawCircle(color.a(0.3f * p), 9.dp.toPx(), c)
    op.label?.let {
        label(
            board, it, Offset(c.x, c.y + r + 10.dp.toPx()), Ink.a(p), 13f, bold = true,
            alignH = AlignH.CENTER, pill = true, maxWidth = board.w * 0.7f,
        )
    }
}

private fun DrawScope.highlight(board: Board, op: DrawOp.HighlightBox, age: Long) {
    val pad = 5.dp.toPx()
    val r = board.rect(op.box).inflate(pad)
    val color = Color(op.color)
    val p = progress(age, 380)
    val corner = CornerRadius(10.dp.toPx())
    drawRoundRect(color.a(0.14f * p), r.topLeft, r.size, corner)
    drawRoundRect(Color.Black.a(0.3f * p), r.topLeft, r.size, corner, style = Stroke(6.dp.toPx()))
    val outline = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(r, corner))
    }
    trace(outline, color, 3.5.dp.toPx(), p)
    op.label?.let {
        val above = r.top - board.oy > 34.dp.toPx()
        label(
            board, it,
            Offset(r.left, if (above) r.top - 6.dp.toPx() else r.bottom + 6.dp.toPx()),
            Ink.a(p), 13f, bold = true,
            alignV = if (above) AlignV.BOTTOM else AlignV.TOP,
            pill = true, maxWidth = board.w * 0.7f,
        )
    }
}

// -------------------------------------------------------------------------------------------
// Whiteboard primitives
// -------------------------------------------------------------------------------------------

private fun DrawScope.arrow(board: Board, op: DrawOp.Arrow, age: Long) {
    val from = board.point(op.fromX, op.fromY)
    val to = board.point(op.toX, op.toY)
    val color = Color(op.color)
    val p = progress(age, 450)
    val tip = Offset(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)
    drawLine(Color.Black.a(0.3f), from, tip, 7.dp.toPx(), StrokeCap.Round)
    drawLine(color, from, tip, 4.dp.toPx(), StrokeCap.Round)
    if (p > 0.2f) arrowHead(tip, from, color, 15.dp.toPx())
    op.label?.let {
        val mid = Offset((from.x + to.x) / 2, (from.y + to.y) / 2 - 8.dp.toPx())
        label(
            board, it, mid, Ink.a(p), 12.5f, bold = true, alignH = AlignH.CENTER,
            alignV = AlignV.BOTTOM, pill = true, maxWidth = board.w * 0.6f,
        )
    }
}

private fun DrawScope.textMark(board: Board, op: DrawOp.TextMark, age: Long) {
    // Typed out character by character.
    val duration = (200 + 22 * op.text.length).coerceAtMost(1_400)
    val shown = ceil(op.text.length * progress(age, duration)).toInt().coerceIn(1, op.text.length)
    label(
        board, op.text.take(shown), board.point(op.x, op.y), Color(op.color),
        sizeSp = op.sizeSp.toFloat(), bold = true, pill = true,
        maxWidth = board.ox + board.w - board.x(op.x),
    )
}

private fun DrawScope.polyline(board: Board, op: DrawOp.Polyline, age: Long) {
    if (op.points.size < 2) return
    val color = Color(op.color)
    val path = Path()
    op.points.forEachIndexed { i, (nx, ny) ->
        val pt = board.point(nx, ny)
        if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
    }
    if (op.closed) path.close()
    val p = progress(age, 900)
    if (op.closed && op.fill) drawPath(path, color.a(0.2f * p))
    trace(path, Color.Black.a(0.25f), (op.strokeWidthDp + 3f).dp.toPx(), p)
    trace(path, color, op.strokeWidthDp.dp.toPx(), p)
}

private fun DrawScope.shape(board: Board, op: DrawOp.Shape, age: Long) {
    val r = board.rect(op.box)
    val color = Color(op.color)
    val path = Path()
    when (op.kind) {
        "circle" -> {
            val radius = min(r.width, r.height) / 2
            path.addOval(Rect(r.center, radius))
        }
        "ellipse" -> path.addOval(r)
        "line" -> {
            path.moveTo(r.left, r.top)
            path.lineTo(r.right, r.bottom)
        }
        "triangle" -> {
            path.moveTo(r.center.x, r.top)
            path.lineTo(r.right, r.bottom)
            path.lineTo(r.left, r.bottom)
            path.close()
        }
        "diamond" -> {
            path.moveTo(r.center.x, r.top)
            path.lineTo(r.right, r.center.y)
            path.lineTo(r.center.x, r.bottom)
            path.lineTo(r.left, r.center.y)
            path.close()
        }
        "rounded" -> path.addRoundRect(androidx.compose.ui.geometry.RoundRect(r, CornerRadius(14.dp.toPx())))
        else -> path.addRect(r)
    }
    val p = progress(age, 700)
    if (op.fill && op.kind != "line") drawPath(path, color.a(0.22f * p))
    trace(path, Color.Black.a(0.25f), 6.dp.toPx(), p)
    trace(path, color, 3.dp.toPx(), p)
    op.label?.let {
        label(
            board, it, r.center, Ink.a(p), 13f, bold = true, alignH = AlignH.CENTER,
            alignV = AlignV.CENTER, maxWidth = r.width - 8.dp.toPx(), maxLines = 3,
            pill = !op.fill, centerText = true,
        )
    }
}

private fun DrawScope.wireframe(board: Board, op: DrawOp.Wireframe, age: Long) {
    val color = Color(op.color)
    val p = progress(age, 1_000)
    op.polylines.forEach { line ->
        val path = Path()
        line.forEachIndexed { i, (nx, ny) ->
            val pt = board.point(nx, ny)
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        trace(path, Color.Black.a(0.25f), 6.dp.toPx(), p)
        trace(path, color, 3.dp.toPx(), p)
    }
    val first = op.polylines.firstOrNull()?.firstOrNull()
    if (op.title != null && first != null) {
        label(
            board, op.title, board.point(first.first, first.second) - Offset(0f, 10.dp.toPx()),
            Ink, 13f, bold = true, alignV = AlignV.BOTTOM, pill = true,
        )
    }
}

// -------------------------------------------------------------------------------------------
// Panels (charts, 3D, diagrams, pictures)
// -------------------------------------------------------------------------------------------

/** Dark card with an optional title; returns the area left for content. */
private fun DrawScope.panel(board: Board, box: NBox, title: String?, alpha: Float): Rect {
    val r = board.rect(box)
    val corner = CornerRadius(18.dp.toPx())
    drawRoundRect(PanelFill.a(alpha), r.topLeft, r.size, corner)
    drawRoundRect(PanelBorder.a(alpha), r.topLeft, r.size, corner, style = Stroke(1.dp.toPx()))
    val pad = 12.dp.toPx()
    var top = r.top + pad
    if (!title.isNullOrBlank()) {
        val t = label(
            board, title, Offset(r.left + pad, top), Ink.a(alpha), 13.5f, bold = true,
            maxWidth = r.width - 2 * pad, maxLines = 1,
        )
        top = t.bottom + 6.dp.toPx()
    }
    return Rect(r.left + pad, top, r.right - pad, (r.bottom - pad).coerceAtLeast(top + 1f))
}

private fun niceTicks(lo: Float, hi: Float, target: Int = 5): List<Float> {
    val span = (hi - lo).toDouble()
    if (span <= 0.0 || !span.isFinite()) return emptyList()
    val rough = span / target
    val magnitude = 10.0.pow(floor(log10(rough)))
    val normalized = rough / magnitude
    val step = magnitude * when {
        normalized < 1.5 -> 1.0
        normalized < 3.0 -> 2.0
        normalized < 7.0 -> 5.0
        else -> 10.0
    }
    val ticks = ArrayList<Float>()
    var v = ceil(lo / step) * step
    while (v <= hi + step * 1e-6 && ticks.size < 20) {
        ticks.add(if (abs(v) < step * 1e-6) 0f else v.toFloat())
        v += step
    }
    return ticks
}

private fun tickText(v: Float): String {
    val a = abs(v)
    return when {
        a == 0f -> "0"
        a >= 10_000f || a < 0.01f -> String.format("%.1e", v)
        a >= 100f -> v.roundToInt().toString()
        else -> String.format("%.2f", v).trimEnd('0').trimEnd('.')
    }
}

private fun DrawScope.plot(board: Board, op: DrawOp.Plot, age: Long) {
    val appear = progress(age, 300)
    val content = panel(board, op.box, op.title, appear)
    val area = Rect(
        content.left + 34.dp.toPx(),
        content.top + 4.dp.toPx(),
        content.right - 6.dp.toPx(),
        content.bottom - 18.dp.toPx(),
    )
    if (area.width < 20f || area.height < 20f) return
    val xSpan = (op.xMax - op.xMin).takeIf { it > 0f } ?: 1f
    val ySpan = (op.yMax - op.yMin).takeIf { it > 0f } ?: 1f
    fun px(x: Float) = area.left + (x - op.xMin) / xSpan * area.width
    fun py(y: Float) = area.bottom - (y - op.yMin) / ySpan * area.height

    // Grid and numbers.
    for (t in niceTicks(op.xMin, op.xMax)) {
        val x = px(t)
        drawLine(GridLine.a(appear), Offset(x, area.top), Offset(x, area.bottom), 1.dp.toPx())
        label(board, tickText(t), Offset(x, area.bottom + 3.dp.toPx()), InkDim.a(appear), 9.5f, alignH = AlignH.CENTER)
    }
    for (t in niceTicks(op.yMin, op.yMax)) {
        val y = py(t)
        drawLine(GridLine.a(appear), Offset(area.left, y), Offset(area.right, y), 1.dp.toPx())
        label(
            board, tickText(t), Offset(area.left - 5.dp.toPx(), y), InkDim.a(appear), 9.5f,
            alignH = AlignH.END, alignV = AlignV.CENTER,
        )
    }
    // Axes through zero when visible, otherwise along the edges.
    val axisX = if (op.yMin <= 0f && op.yMax >= 0f) py(0f) else area.bottom
    val axisY = if (op.xMin <= 0f && op.xMax >= 0f) px(0f) else area.left
    drawLine(AxisLine.a(appear), Offset(area.left, axisX), Offset(area.right, axisX), 1.5.dp.toPx())
    drawLine(AxisLine.a(appear), Offset(axisY, area.top), Offset(axisY, area.bottom), 1.5.dp.toPx())

    val p = progress(age, 1_100, delayMs = 150)
    clipRect(area.left, area.top, area.right, area.bottom) {
        op.series.forEach { s ->
            val color = Color(s.color)
            if (s.scatter) {
                val count = ceil(s.points.size * p).toInt()
                s.points.take(count).forEach { (x, y) ->
                    if (y.isFinite()) {
                        drawCircle(color, 4.dp.toPx(), Offset(px(x), py(y)))
                        drawCircle(Color.White.a(0.85f), 4.dp.toPx(), Offset(px(x), py(y)), style = Stroke(1.dp.toPx()))
                    }
                }
            } else {
                val path = Path()
                var pen = false
                var lastY = 0f
                for ((x, y) in s.points) {
                    val sy = if (y.isFinite()) py(y) else Float.NaN
                    // Lift the pen at gaps and at asymptotes (tan, 1/x) instead of joining them.
                    val jump = pen && abs(sy - lastY) > area.height * 1.5f
                    if (!sy.isFinite() || jump) {
                        pen = false
                        if (!sy.isFinite()) continue
                    }
                    val sx = px(x)
                    val clamped = sy.coerceIn(area.top - area.height, area.bottom + area.height)
                    if (pen) path.lineTo(sx, clamped) else path.moveTo(sx, clamped)
                    pen = true
                    lastY = sy
                }
                trace(path, color, 3.dp.toPx(), p)
            }
        }
    }

    // Legend.
    val named = op.series.filter { !it.label.isNullOrBlank() }.take(5)
    if (named.size > 1 || (named.size == 1 && op.title.isNullOrBlank())) {
        var y = area.top + 2.dp.toPx()
        named.forEach { s ->
            val text = label(
                board, s.label.orEmpty(), Offset(area.right - 4.dp.toPx(), y), Ink.a(appear), 10.5f,
                alignH = AlignH.END, maxWidth = area.width * 0.6f, maxLines = 1,
            )
            drawCircle(Color(s.color).a(appear), 4.dp.toPx(), Offset(text.left - 8.dp.toPx(), text.center.y))
            y = text.bottom + 2.dp.toPx()
        }
    }
}

// --- 3D ---

/** Turntable projection: rotate about the vertical axis, then look down at a fixed angle. */
private class Camera(yaw: Float, private val center: Offset, private val scale: Float) {
    private val cy = cos(yaw)
    private val sy = sin(yaw)
    private val ce = cos(0.5f)
    private val se = sin(0.5f)

    /** Depth of the last projected point: larger is nearer the viewer. */
    var depth = 0f
        private set

    fun project(x: Float, y: Float, z: Float): Offset {
        val xr = x * cy - y * sy
        val yr = x * sy + y * cy
        depth = -yr * ce + z * se
        return Offset(center.x + xr * scale, center.y + (yr * se - z * ce) * scale)
    }
}

private val HeightRamp = listOf(
    Color(0xFF4F6BFF), Color(0xFF26C6DA), Color(0xFF66BB6A), Color(0xFFFFEE58), Color(0xFFFF7043),
)

private fun rampColor(t: Float): Color {
    val scaled = t.coerceIn(0f, 1f) * (HeightRamp.size - 1)
    val i = scaled.toInt().coerceAtMost(HeightRamp.size - 2)
    return lerp(HeightRamp[i], HeightRamp[i + 1], scaled - i)
}

private fun DrawScope.surface(board: Board, op: DrawOp.Surface3D, age: Long) {
    val appear = progress(age, 300)
    val content = panel(board, op.box, op.title, appear)
    if (content.width < 40f || content.height < 40f) return
    val scale = min(content.width / 2f / 1.45f, content.height / 2f / 1.25f)
    val camera = Camera(0.7f + age / 1000f * 0.35f, content.center + Offset(0f, scale * 0.1f), scale)
    val grow = progress(age, 900, delayMs = 100)
    val n = op.n
    val zSpan = (op.zMax - op.zMin).takeIf { it > 0f } ?: 1f
    val zScale = 0.62f

    // Floor outline and axes, so the rotation reads as 3D.
    val floor = -zScale
    val corners = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
    for (i in corners.indices) {
        val (ax, ay) = corners[i]
        val (bx, by) = corners[(i + 1) % 4]
        drawLine(AxisLine.a(0.35f * appear), camera.project(ax, ay, floor), camera.project(bx, by, floor), 1.dp.toPx())
    }
    val origin = camera.project(-1f, -1f, floor)
    val axes = listOf(
        Triple("x", camera.project(1.15f, -1f, floor), Palette.RED),
        Triple("y", camera.project(-1f, 1.15f, floor), Palette.GREEN),
        Triple("z", camera.project(-1f, -1f, zScale + 0.2f), Palette.BLUE),
    )
    axes.forEach { (name, tip, c) ->
        drawLine(Color(c).a(0.9f * appear), origin, tip, 1.8.dp.toPx(), StrokeCap.Round)
        label(board, name, tip, Color(c).a(appear), 11f, bold = true, alignH = AlignH.CENTER, alignV = AlignV.CENTER)
    }

    // Normalised height (0..1) and projected position of every grid point.
    val t = FloatArray(n * n)
    val sx = FloatArray(n * n)
    val sy = FloatArray(n * n)
    for (j in 0 until n) {
        for (i in 0 until n) {
            val k = j * n + i
            val h = op.heights[k]
            if (!h.isFinite()) {
                t[k] = Float.NaN
                continue
            }
            val tn = ((h - op.zMin) / zSpan).coerceIn(0f, 1f)
            t[k] = tn
            val z = floor + tn * 2f * zScale * grow
            val pt = camera.project(i / (n - 1f) * 2f - 1f, j / (n - 1f) * 2f - 1f, z)
            sx[k] = pt.x
            sy[k] = pt.y
        }
    }
    val stroke = 1.4.dp.toPx()
    fun segment(a: Int, b: Int) {
        if (t[a].isNaN() || t[b].isNaN()) return
        drawLine(rampColor((t[a] + t[b]) / 2f).a(appear), Offset(sx[a], sy[a]), Offset(sx[b], sy[b]), stroke)
    }
    for (j in 0 until n) {
        for (i in 0 until n) {
            val k = j * n + i
            if (i + 1 < n) segment(k, k + 1)
            if (j + 1 < n) segment(k, k + n)
        }
    }
    label(
        board, "${tickText(op.zMin)} … ${tickText(op.zMax)}",
        Offset(content.right, content.bottom), InkDim.a(appear), 9.5f,
        alignH = AlignH.END, alignV = AlignV.BOTTOM,
    )
}

/** Wireframes in a unit cube, as polylines of x,y,z triples. Built once per kind. */
private val solidCache = HashMap<String, List<FloatArray>>()

private fun circle(radius: Float, z: Float, segments: Int = 40): FloatArray {
    val out = FloatArray((segments + 1) * 3)
    for (i in 0..segments) {
        val a = (i.toFloat() / segments) * 2f * Math.PI.toFloat()
        out[i * 3] = radius * cos(a)
        out[i * 3 + 1] = radius * sin(a)
        out[i * 3 + 2] = z
    }
    return out
}

private fun line(vararg v: Float) = v

private fun boxEdges(hx: Float, hy: Float, hz: Float): List<FloatArray> = listOf(
    line(-hx, -hy, -hz, hx, -hy, -hz, hx, hy, -hz, -hx, hy, -hz, -hx, -hy, -hz),
    line(-hx, -hy, hz, hx, -hy, hz, hx, hy, hz, -hx, hy, hz, -hx, -hy, hz),
    line(-hx, -hy, -hz, -hx, -hy, hz),
    line(hx, -hy, -hz, hx, -hy, hz),
    line(hx, hy, -hz, hx, hy, hz),
    line(-hx, hy, -hz, -hx, hy, hz),
)

private fun solidEdges(kind: String): List<FloatArray> = solidCache.getOrPut(kind) {
    val tau = 2f * Math.PI.toFloat()
    when (kind) {
        "cuboid" -> boxEdges(1f, 0.6f, 0.45f)
        "pyramid" -> {
            val b = 0.8f
            val base = -0.65f
            listOf(
                line(-b, -b, base, b, -b, base, b, b, base, -b, b, base, -b, -b, base),
                line(-b, -b, base, 0f, 0f, 0.9f, b, b, base),
                line(b, -b, base, 0f, 0f, 0.9f, -b, b, base),
            )
        }
        "tetrahedron" -> {
            val s = 0.75f
            listOf(
                line(s, s, s, -s, -s, s, -s, s, -s, s, s, s, s, -s, -s, -s, -s, s),
                line(-s, s, -s, s, -s, -s),
            )
        }
        "sphere" -> buildList {
            val r = 0.95f
            for (k in 1..5) {
                val lat = -Math.PI.toFloat() / 2 + k * Math.PI.toFloat() / 6
                add(circle(r * cos(lat), r * sin(lat)))
            }
            for (m in 0 until 8) {
                val lon = m * tau / 8
                val arc = FloatArray(41 * 3)
                for (i in 0..40) {
                    val lat = -Math.PI.toFloat() / 2 + i * Math.PI.toFloat() / 40
                    arc[i * 3] = r * cos(lat) * cos(lon)
                    arc[i * 3 + 1] = r * cos(lat) * sin(lon)
                    arc[i * 3 + 2] = r * sin(lat)
                }
                add(arc)
            }
        }
        "cylinder" -> buildList {
            add(circle(0.7f, -0.8f))
            add(circle(0.7f, 0.8f))
            for (m in 0 until 12) {
                val a = m * tau / 12
                add(line(0.7f * cos(a), 0.7f * sin(a), -0.8f, 0.7f * cos(a), 0.7f * sin(a), 0.8f))
            }
        }
        "cone" -> buildList {
            add(circle(0.8f, -0.75f))
            for (m in 0 until 12) {
                val a = m * tau / 12
                add(line(0.8f * cos(a), 0.8f * sin(a), -0.75f, 0f, 0f, 0.9f))
            }
        }
        "torus" -> buildList {
            val big = 0.68f
            val small = 0.27f
            for (m in 0 until 18) {
                val a = m * tau / 18
                val ring = FloatArray(25 * 3)
                for (i in 0..24) {
                    val b = i * tau / 24
                    val d = big + small * cos(b)
                    ring[i * 3] = d * cos(a)
                    ring[i * 3 + 1] = d * sin(a)
                    ring[i * 3 + 2] = small * sin(b)
                }
                add(ring)
            }
            for (k in 0 until 6) {
                val b = k * tau / 6
                add(circle(big + small * cos(b), small * sin(b), 48))
            }
        }
        "axes" -> listOf(
            line(-1f, 0f, 0f, 1f, 0f, 0f),
            line(0f, -1f, 0f, 0f, 1f, 0f),
            line(0f, 0f, -0.8f, 0f, 0f, 0.9f),
        )
        else -> boxEdges(0.75f, 0.75f, 0.75f)
    }
}

private fun DrawScope.solid(board: Board, op: DrawOp.Solid3D, age: Long) {
    val appear = progress(age, 300)
    val content = panel(board, op.box, op.label, appear)
    if (content.width < 40f || content.height < 40f) return
    val scale = min(content.width, content.height) / 2f / 1.35f * (0.6f + 0.4f * progress(age, 600))
    val camera = Camera(0.6f + age / 1000f * 0.5f, content.center, scale)
    val color = Color(op.color)
    val stroke = 2.dp.toPx()
    val edges = solidEdges(op.kind)
    val isAxes = op.kind == "axes"
    edges.forEachIndexed { index, poly ->
        val lineColor = if (isAxes) Color(intArrayOf(Palette.RED, Palette.GREEN, Palette.BLUE)[index % 3]) else color
        var prev = camera.project(poly[0], poly[1], poly[2])
        var prevDepth = camera.depth
        var i = 3
        while (i + 2 < poly.size) {
            val next = camera.project(poly[i], poly[i + 1], poly[i + 2])
            val depth = camera.depth
            // Far edges are fainter, which is what makes a wireframe read as solid.
            val near = (((prevDepth + depth) / 2f + 1.2f) / 2.4f).coerceIn(0f, 1f)
            drawLine(lineColor.a((0.28f + 0.72f * near) * appear), prev, next, stroke, StrokeCap.Round)
            prev = next
            prevDepth = depth
            i += 3
        }
        if (isAxes) {
            label(
                board, listOf("x", "y", "z")[index % 3], prev, lineColor.a(appear), 12f, bold = true,
                alignH = AlignH.CENTER, alignV = AlignV.BOTTOM,
            )
            arrowHead(prev, camera.project(0f, 0f, 0f), lineColor.a(appear), 9.dp.toPx())
        }
    }
}

// --- Diagrams ---

private fun DrawScope.diagram(board: Board, op: DrawOp.Diagram, age: Long) {
    val appear = progress(age, 300)
    val content = panel(board, op.box, op.title, appear)
    val count = op.nodes.size
    if (count == 0 || content.width < 40f || content.height < 40f) return
    val index = op.nodes.withIndex().associate { (i, n) -> n.id to i }

    // Rank = longest path from a source, so arrows mostly point forwards.
    val rank = IntArray(count)
    repeat(count) {
        for (e in op.edges) {
            val a = index[e.from] ?: continue
            val b = index[e.to] ?: continue
            if (a != b && rank[b] < rank[a] + 1 && rank[a] + 1 < count) rank[b] = rank[a] + 1
        }
    }
    val centers = arrayOfNulls<Offset>(count)
    val nodeW: Float
    val nodeH: Float
    if (op.layout == "cycle") {
        val rx = content.width / 2f * 0.72f
        val ry = content.height / 2f * 0.72f
        for (i in 0 until count) {
            val a = -Math.PI.toFloat() / 2 + i * 2f * Math.PI.toFloat() / count
            centers[i] = Offset(content.center.x + rx * cos(a), content.center.y + ry * sin(a))
        }
        val arc = (2f * Math.PI.toFloat() * min(rx, ry) / count)
        nodeW = min(arc * 0.9f, 130.dp.toPx()).coerceAtLeast(56.dp.toPx())
        nodeH = min(content.height * 0.2f, 44.dp.toPx())
    } else {
        val ranks = (rank.maxOrNull() ?: 0) + 1
        val perRank = (0 until ranks).map { r -> (0 until count).filter { rank[it] == r } }
        val widest = perRank.maxOf { it.size }.coerceAtLeast(1)
        val across = op.layout == "right"
        val mainLen = if (across) content.width else content.height
        val crossLen = if (across) content.height else content.width
        perRank.forEachIndexed { r, members ->
            members.forEachIndexed { k, node ->
                val main = (r + 0.5f) / ranks * mainLen
                val cross = (k + 0.5f) / members.size * crossLen
                centers[node] = if (across) {
                    Offset(content.left + main, content.top + cross)
                } else {
                    Offset(content.left + cross, content.top + main)
                }
            }
        }
        val cellW = if (across) content.width / ranks else content.width / widest
        val cellH = if (across) content.height / widest else content.height / ranks
        nodeW = min(cellW * 0.84f, 170.dp.toPx())
        nodeH = min(cellH * 0.6f, 50.dp.toPx())
    }

    fun nodeRect(i: Int): Rect {
        val c = centers[i] ?: content.center
        return Rect(c.x - nodeW / 2, c.y - nodeH / 2, c.x + nodeW / 2, c.y + nodeH / 2)
    }

    /** Where the line from this box's centre towards [toward] leaves the box. */
    fun border(i: Int, toward: Offset): Offset {
        val c = centers[i] ?: content.center
        val dx = toward.x - c.x
        val dy = toward.y - c.y
        if (dx == 0f && dy == 0f) return c
        val scale = min(
            if (dx != 0f) (nodeW / 2 + 3.dp.toPx()) / abs(dx) else Float.MAX_VALUE,
            if (dy != 0f) (nodeH / 2 + 3.dp.toPx()) / abs(dy) else Float.MAX_VALUE,
        )
        return Offset(c.x + dx * scale, c.y + dy * scale)
    }

    // Boxes appear one after another, then the arrows between them.
    val step = 150L
    op.edges.forEachIndexed { k, e ->
        val a = index[e.from] ?: return@forEachIndexed
        val b = index[e.to] ?: return@forEachIndexed
        if (a == b) return@forEachIndexed
        val p = progress(age, 380, delayMs = (maxOf(a, b) + 1) * step + k * 40L)
        if (p <= 0f) return@forEachIndexed
        val from = border(a, centers[b]!!)
        val to = border(b, centers[a]!!)
        val tip = Offset(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)
        drawLine(Ink.a(0.75f), from, tip, 1.8.dp.toPx(), StrokeCap.Round)
        if (p > 0.6f) arrowHead(tip, from, Ink.a(0.9f), 9.dp.toPx())
        if (e.label != null && p >= 1f) {
            label(
                board, e.label, Offset((from.x + to.x) / 2, (from.y + to.y) / 2), InkDim, 10f,
                alignH = AlignH.CENTER, alignV = AlignV.CENTER, pill = true, maxWidth = nodeW,
                maxLines = 1,
            )
        }
    }
    op.nodes.forEachIndexed { i, node ->
        val p = progress(age, 320, delayMs = i * step)
        if (p <= 0f) return@forEachIndexed
        val r = nodeRect(i)
        val color = Color(Palette.CYCLE[rank[i] % Palette.CYCLE.size])
        val corner = CornerRadius(10.dp.toPx())
        drawRoundRect(Color(0xFF1B2433).a(p), r.topLeft, r.size, corner)
        drawRoundRect(color.a(0.22f * p), r.topLeft, r.size, corner)
        drawRoundRect(color.a(p), r.topLeft, r.size, corner, style = Stroke(1.8.dp.toPx()))
        label(
            board, node.label, r.center, Ink.a(p), 11.5f, bold = true, alignH = AlignH.CENTER,
            alignV = AlignV.CENTER, maxWidth = nodeW - 10.dp.toPx(), maxLines = 2, centerText = true,
        )
    }
}

private fun DrawScope.neuralNet(board: Board, op: DrawOp.NeuralNet, age: Long) {
    val appear = progress(age, 300)
    val content = panel(board, op.box, op.title, appear)
    val layerCount = op.layers.size
    if (layerCount == 0 || content.width < 40f || content.height < 40f) return
    val area = Rect(content.left, content.top, content.right, content.bottom - 30.dp.toPx())
    if (area.height < 20f) return
    val maxShown = 7
    val shown = op.layers.map { min(it, maxShown) }
    val tallest = shown.max()
    val radius = min(min(area.width / layerCount * 0.16f, area.height / tallest * 0.36f), 13.dp.toPx())
    fun center(layer: Int, k: Int): Offset {
        val x = area.left + (layer + 0.5f) / layerCount * area.width
        val gap = min(area.height / tallest, radius * 3.2f)
        val y = area.center.y + (k - (shown[layer] - 1) / 2f) * gap
        return Offset(x, y)
    }
    val step = 220L
    // Connections first so the neurons sit on top.
    for (layer in 0 until layerCount - 1) {
        val p = progress(age, 400, delayMs = (layer + 1) * step)
        if (p <= 0f) continue
        for (a in 0 until shown[layer]) {
            for (b in 0 until shown[layer + 1]) {
                val from = center(layer, a)
                val to = center(layer + 1, b)
                val tip = Offset(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)
                drawLine(Ink.a(0.22f), from, tip, 1.dp.toPx())
            }
        }
    }
    for (layer in 0 until layerCount) {
        val p = progress(age, 320, delayMs = layer * step)
        if (p <= 0f) continue
        val color = Color(
            when (layer) {
                0 -> Palette.BLUE
                layerCount - 1 -> Palette.ORANGE
                else -> if (layer % 2 == 1) Palette.TEAL else Palette.PURPLE
            },
        )
        for (k in 0 until shown[layer]) {
            val c = center(layer, k)
            drawCircle(Color(0xFF1B2433).a(p), radius * p, c)
            drawCircle(color.a(0.35f * p), radius * p, c)
            drawCircle(color.a(p), radius * p, c, style = Stroke(1.8.dp.toPx()))
        }
        val default = when (layer) {
            0 -> "Input"
            layerCount - 1 -> "Output"
            else -> "Hidden"
        }
        val name = op.labels.getOrNull(layer) ?: default
        label(
            board, "$name\n${op.layers[layer]}",
            Offset(center(layer, 0).x, area.bottom + 2.dp.toPx()), InkDim.a(p), 9.5f,
            alignH = AlignH.CENTER, maxWidth = area.width / layerCount, maxLines = 2, centerText = true,
        )
    }
}

private fun DrawScope.picture(board: Board, op: DrawOp.Picture, age: Long) {
    val appear = progress(age, 300)
    val content = panel(board, op.box, op.caption, appear)
    val bitmap = op.bitmap
    if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
        val fit = min(content.width / bitmap.width, content.height / bitmap.height)
        val w = bitmap.width * fit
        val h = bitmap.height * fit
        drawImage(
            image = bitmap.asImageBitmap(),
            dstOffset = IntOffset((content.center.x - w / 2).roundToInt(), (content.center.y - h / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
        )
        return
    }
    val message = if (op.failed) {
        "Couldn't create the picture"
    } else {
        "Drawing the picture" + ".".repeat(1 + ((age / 400) % 3).toInt())
    }
    if (!op.failed) {
        // Sweeping bar so it is obvious something is on its way.
        val sweep = (age % 1_600) / 1_600f
        val barW = content.width * 0.3f
        val x = content.left + (content.width + barW) * sweep - barW
        clipRect(content.left, content.top, content.right, content.bottom) {
            drawRoundRect(
                Color(Palette.TEAL).a(0.5f),
                Offset(x, content.center.y + 18.dp.toPx()),
                Size(barW, 3.dp.toPx()),
                CornerRadius(2.dp.toPx()),
            )
        }
    }
    label(
        board, message, content.center, InkDim.a(appear), 12.5f,
        alignH = AlignH.CENTER, alignV = AlignV.CENTER,
    )
}
