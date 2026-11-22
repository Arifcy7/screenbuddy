package com.screen.buddy.overlay

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Rectangle in the model's 0-1000 screenshot space. */
data class NBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        /** Where a panel goes when the model does not say: upper part of the screen. */
        val DEFAULT_PANEL = NBox(50, 90, 950, 560)
    }
}

/** Everything Buddy can put on screen. Positions are 0-1000 over the cropped screenshot. */
sealed class DrawOp {

    // --- Pointing at the app underneath (fade by themselves) ---

    data class Ring(val x: Int, val y: Int, val label: String? = null, val color: Int = Palette.RED) : DrawOp()

    data class HighlightBox(val box: NBox, val label: String? = null, val color: Int = Palette.BLUE) : DrawOp()

    // --- Whiteboard (stay until erased) ---

    data class Arrow(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val label: String? = null,
        val color: Int = Palette.GREEN,
    ) : DrawOp()

    data class TextMark(
        val x: Int,
        val y: Int,
        val text: String,
        val sizeSp: Int = 18,
        val color: Int = Palette.YELLOW,
    ) : DrawOp()

    data class Polyline(
        val points: List<Pair<Int, Int>>,
        val color: Int = Palette.TEAL,
        val strokeWidthDp: Float = 3.5f,
        val closed: Boolean = false,
        val fill: Boolean = false,
    ) : DrawOp()

    /** kind: rect, rounded, circle, ellipse, line, triangle, diamond. */
    data class Shape(
        val kind: String,
        val box: NBox,
        val color: Int = Palette.TEAL,
        val fill: Boolean = false,
        val label: String? = null,
    ) : DrawOp()

    data class Series(
        val points: List<Pair<Float, Float>>, // data coordinates; NaN y breaks the line
        val color: Int,
        val label: String? = null,
        val scatter: Boolean = false,
    )

    /** 2D chart with real axes; the device does the sampling and scaling. */
    data class Plot(
        val box: NBox,
        val title: String?,
        val xMin: Float,
        val xMax: Float,
        val yMin: Float,
        val yMax: Float,
        val series: List<Series>,
    ) : DrawOp()

    /** z = f(x, y) as a slowly rotating mesh. [heights] is n*n, row-major, in data units. */
    data class Surface3D(
        val box: NBox,
        val title: String?,
        val n: Int,
        val heights: FloatArray,
        val zMin: Float,
        val zMax: Float,
    ) : DrawOp()

    /** kind: cube, cuboid, sphere, cylinder, cone, pyramid, tetrahedron, torus, axes. */
    data class Solid3D(
        val box: NBox,
        val kind: String,
        val color: Int = Palette.ORANGE,
        val label: String? = null,
    ) : DrawOp()

    data class Node(val id: String, val label: String)

    data class Edge(val from: String, val to: String, val label: String? = null)

    /** Boxes and arrows, laid out automatically. layout: down, right, cycle. */
    data class Diagram(
        val box: NBox,
        val title: String?,
        val nodes: List<Node>,
        val edges: List<Edge>,
        val layout: String,
    ) : DrawOp()

    data class NeuralNet(
        val box: NBox,
        val title: String?,
        val layers: List<Int>,
        val labels: List<String>,
    ) : DrawOp()

    /** Generated illustration; [bitmap] is null while it is being made. */
    data class Picture(
        val box: NBox,
        val caption: String?,
        val bitmap: Bitmap? = null,
        val failed: Boolean = false,
    ) : DrawOp()

    /** Free-form line art already projected to 2D by the model. */
    data class Wireframe(
        val polylines: List<List<Pair<Int, Int>>>,
        val title: String? = null,
        val color: Int = Palette.ORANGE,
    ) : DrawOp()

    /** Pointer marks disappear on their own; whiteboard content stays until erased. */
    val isPointer: Boolean get() = this is Ring || this is HighlightBox
}

/** One thing on screen: the op plus identity and timing for animation. */
data class Drawn(val id: String, val op: DrawOp, val createdAt: Long)

/** Colours chosen to stay readable on both light and dark apps. */
object Palette {
    const val RED = 0xFFFF5252.toInt()
    const val ORANGE = 0xFFFFA726.toInt()
    const val YELLOW = 0xFFFFEE58.toInt()
    const val GREEN = 0xFF66BB6A.toInt()
    const val TEAL = 0xFF26C6DA.toInt()
    const val BLUE = 0xFF42A5F5.toInt()
    const val PURPLE = 0xFFAB7BFF.toInt()
    const val PINK = 0xFFFF6FB5.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GREY = 0xFFB0BEC5.toInt()

    /** Order used when several curves / layers need distinct colours. */
    val CYCLE = intArrayOf(TEAL, ORANGE, PINK, GREEN, PURPLE, YELLOW, BLUE, RED)

    private val NAMES = mapOf(
        "red" to RED, "orange" to ORANGE, "yellow" to YELLOW, "green" to GREEN,
        "teal" to TEAL, "cyan" to TEAL, "blue" to BLUE, "purple" to PURPLE,
        "violet" to PURPLE, "pink" to PINK, "magenta" to PINK, "white" to WHITE,
        "grey" to GREY, "gray" to GREY,
    )

    /** Accepts a colour name or #RRGGBB / #AARRGGBB. */
    fun parse(raw: String?): Int? {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return null
        NAMES[s]?.let { return it }
        val hex = s.removePrefix("#")
        return when (hex.length) {
            6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
            8 -> hex.toLongOrNull(16)?.toInt()
            else -> null
        }
    }
}

@Singleton
class DrawingState @Inject constructor() {
    private val _ops = MutableStateFlow<List<Drawn>>(emptyList())
    val ops: StateFlow<List<Drawn>> = _ops.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val counter = AtomicInteger(0)
    private var clearJob: Job? = null

    /** How long pointer marks (ring, highlight) stay. Set from Settings. */
    @Volatile var pointerTtlMs: Long = 8_000

    fun newId(): String = "d${counter.incrementAndGet()}"

    /** Add an op, or replace the one with the same id in place. Returns the id. */
    fun put(id: String?, op: DrawOp): String {
        val finalId = id?.takeIf { it.isNotBlank() } ?: newId()
        val drawn = Drawn(finalId, op, SystemClock.uptimeMillis())
        _ops.update { list ->
            val index = list.indexOfFirst { it.id == finalId }
            if (index < 0) list + drawn else list.toMutableList().also { it[index] = drawn }
        }
        if (op.isPointer) {
            scope.launch {
                delay(pointerTtlMs)
                _ops.update { list -> list.filterNot { it.id == finalId && it.createdAt == drawn.createdAt } }
            }
        }
        return finalId
    }

    fun add(op: DrawOp): String = put(null, op)

    /** Change an existing op without restarting its entrance animation. */
    fun update(id: String, transform: (DrawOp) -> DrawOp): Boolean {
        var found = false
        _ops.update { list ->
            list.map { if (it.id == id) it.copy(op = transform(it.op)).also { found = true } else it }
        }
        return found
    }

    fun find(id: String): DrawOp? = _ops.value.firstOrNull { it.id == id }?.op

    /** Remove the given ids; returns how many were on screen. */
    fun erase(ids: Collection<String>): Int {
        val before = _ops.value.size
        _ops.update { list -> list.filterNot { it.id in ids } }
        return before - _ops.value.size
    }

    fun clear() {
        clearJob?.cancel()
        _ops.value = emptyList()
    }

    /** Leave the board up for a while after a conversation, then tidy it away. */
    fun clearAfter(delayMs: Long) {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(delayMs)
            _ops.value = emptyList()
        }
    }

    fun cancelPendingClear() {
        clearJob?.cancel()
    }
}
