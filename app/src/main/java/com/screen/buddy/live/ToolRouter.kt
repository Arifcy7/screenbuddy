package com.screen.buddy.live

import com.screen.buddy.overlay.DrawOp
import com.screen.buddy.overlay.DrawingState
import com.screen.buddy.overlay.NBox
import com.screen.buddy.overlay.Palette
import com.screen.buddy.overlay.math.Expr
import com.screen.buddy.overlay.parsePointList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRouter @Inject constructor(
    private val drawingState: DrawingState,
    private val imageGenerator: ImageGenerator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * A validated tool call. [result] goes back to the model immediately; [apply] performs the
     * drawing and is run when the speech reaches [cue] (or straight away if there is none).
     */
    class Plan(val result: JsonObject, val apply: (() -> Unit)?, val cue: String?)

    /** Validates and applies immediately. */
    fun route(name: String, args: Map<String, JsonElement>): JsonObject {
        val plan = plan(name, args)
        plan.apply?.invoke()
        return plan.result
    }

    fun plan(name: String, args: Map<String, JsonElement>): Plan {
        Timber.d("Tool call: %s args=%s", name, args)
        return try {
            build(name, args)
        } catch (e: Exception) {
            Timber.w("Tool %s rejected: %s", name, e.message)
            Plan(JsonObject(mapOf("error" to JsonPrimitive(e.message ?: "tool failed"))), null, null)
        }
    }

    private fun build(name: String, args: Map<String, JsonElement>): Plan {
        val cue = args.stringOrNull("cue")?.takeIf { it.isNotBlank() }
        val color = Palette.parse(args.stringOrNull("color"))

        /** A drawing with a stable id the model can refer to later. */
        fun drawing(op: DrawOp): Plan {
            val id = args.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() } ?: drawingState.newId()
            return Plan(okWithId(id), { drawingState.put(id, op) }, cue)
        }

        return when (name) {
            "point_at" -> drawing(
                DrawOp.Ring(args.coord("x"), args.coord("y"), args.stringOrNull("label"), color ?: Palette.RED),
            )
            "highlight_box" -> drawing(
                DrawOp.HighlightBox(args.box(), args.stringOrNull("label"), color ?: Palette.BLUE),
            )
            "draw_arrow" -> drawing(
                DrawOp.Arrow(
                    fromX = args.coord("from_x"),
                    fromY = args.coord("from_y"),
                    toX = args.coord("to_x"),
                    toY = args.coord("to_y"),
                    label = args.stringOrNull("label"),
                    color = color ?: Palette.GREEN,
                ),
            )
            "draw_text" -> drawing(
                DrawOp.TextMark(
                    x = args.coord("x"),
                    y = args.coord("y"),
                    text = args.string("text").replace("\\n", "\n").take(400),
                    sizeSp = (args.doubleOrNull("size") ?: 18.0).toInt().coerceIn(12, 48),
                    color = color ?: Palette.YELLOW,
                ),
            )
            "draw_path" -> {
                val points = parsePointList(args.string("points"))
                require(points.size >= 2) { "draw_path needs at least 2 points as x,y;x,y" }
                drawing(
                    DrawOp.Polyline(
                        points = points,
                        color = color ?: Palette.TEAL,
                        closed = args.bool("closed"),
                        fill = args.bool("fill"),
                    ),
                )
            }
            "draw_shape" -> drawing(
                DrawOp.Shape(
                    kind = args.string("kind").lowercase(),
                    box = args.box(),
                    color = color ?: Palette.TEAL,
                    fill = args.bool("fill"),
                    label = args.stringOrNull("label"),
                ),
            )
            "plot_function", "draw_plot" -> {
                // Legacy draw_plot sent screen-space points; prefer formula plots when given.
                if (args.containsKey("expression")) {
                    planFunctionPlot(args, cue, color)
                } else if (args.containsKey("points") && !args.containsKey("xmin")) {
                    planPointPlot(args, cue, color)
                } else if (args.containsKey("points")) {
                    // Old screen-space polyline chart.
                    val points = parsePointList(args.string("points"))
                    require(points.size >= 2) { "draw_plot needs points" }
                    drawing(
                        DrawOp.Polyline(
                            points = points,
                            color = color ?: Palette.TEAL,
                            strokeWidthDp = 3.5f,
                        ),
                    )
                } else {
                    planFunctionPlot(args, cue, color)
                }
            }
            "plot_points" -> planPointPlot(args, cue, color)
            "draw_3d_surface" -> drawing(surface(args))
            "draw_3d_shape" -> drawing(
                DrawOp.Solid3D(
                    box = args.panel(),
                    kind = args.string("kind").lowercase(),
                    color = color ?: Palette.ORANGE,
                    label = args.stringOrNull("label"),
                ),
            )
            "draw_diagram" -> drawing(diagram(args))
            "draw_neural_network" -> {
                val layers = args.string("layers").split(',', ';', ' ', '-')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it > 0 }
                require(layers.size in 2..10) { "layers must list 2 to 10 layer sizes, e.g. '3,5,2'" }
                val labels = args.stringOrNull("labels").orEmpty().split(',', ';')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                drawing(DrawOp.NeuralNet(args.panel(), args.stringOrNull("title"), layers, labels))
            }
            "show_image" -> {
                val prompt = args.string("prompt")
                val id = args.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() } ?: drawingState.newId()
                val placeholder = DrawOp.Picture(args.panel(), args.stringOrNull("caption"))
                val result = JsonObject(
                    mapOf(
                        "ok" to JsonPrimitive(true),
                        "id" to JsonPrimitive(id),
                        "note" to JsonPrimitive("The picture appears in a few seconds; keep talking."),
                    ),
                )
                Plan(result, {
                    drawingState.put(id, placeholder)
                    scope.launch {
                        val bitmap = imageGenerator.generate(prompt)
                        drawingState.update(id) { current ->
                            if (current is DrawOp.Picture) {
                                current.copy(bitmap = bitmap, failed = bitmap == null)
                            } else {
                                current
                            }
                        }
                    }
                }, cue)
            }
            "draw_wireframe" -> {
                val polylines = args.string("polylines").split('|')
                    .map { parsePointList(it) }
                    .filter { it.size >= 2 }
                require(polylines.isNotEmpty()) { "draw_wireframe needs polylines as x,y;x,y|x,y;x,y" }
                drawing(DrawOp.Wireframe(polylines, args.stringOrNull("title"), color ?: Palette.ORANGE))
            }
            "erase" -> {
                val ids = args.string("ids").split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                require(ids.isNotEmpty()) { "erase needs ids" }
                Plan(ok(), { drawingState.erase(ids) }, cue)
            }
            "clear_drawings" -> Plan(ok(), { drawingState.clear() }, cue)
            else -> throw IllegalArgumentException("Unknown function: $name")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Charts
    // ---------------------------------------------------------------------------------------

    private fun planFunctionPlot(args: Map<String, JsonElement>, cue: String?, color: Int?): Plan {
        val source = args.string("expression")
        val expr = Expr.parse(source)
        val reqXMin = args.doubleOrNull("x_min")
        val reqXMax = args.doubleOrNull("x_max")
        val xMin = reqXMin ?: -5.0
        val xMax = reqXMax ?: 5.0
        require(xMax > xMin) { "x_max must be greater than x_min" }
        require(sample(expr, xMin, xMax).any { !it.second.isNaN() }) {
            "'$source' has no real values between x=$xMin and x=$xMax"
        }
        val yMin = args.doubleOrNull("y_min")
        val yMax = args.doubleOrNull("y_max")
        val id = args.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() } ?: drawingState.newId()
        val label = args.stringOrNull("label") ?: source.trim().take(28)
        val title = args.stringOrNull("title")
        val box = args.panelOrNull()

        return Plan(okWithId(id), {
            val existing = drawingState.find(id) as? DrawOp.Plot
            if (existing != null) {
                // Another curve on the same axes: keep the chart's x range.
                val points = sample(expr, existing.xMin.toDouble(), existing.xMax.toDouble())
                val series = DrawOp.Series(points, color ?: seriesColor(existing.series.size), label)
                val all = existing.series + series
                val (lo, hi) = yRange(all, yMin, yMax)
                drawingState.update(id) {
                    existing.copy(series = all, yMin = lo, yMax = hi, title = title ?: existing.title)
                }
            } else {
                val points = sample(expr, xMin, xMax)
                val series = listOf(DrawOp.Series(points, color ?: seriesColor(0), label))
                val (lo, hi) = yRange(series, yMin, yMax)
                drawingState.put(
                    id,
                    DrawOp.Plot(
                        box = box ?: NBox.DEFAULT_PANEL,
                        title = title,
                        xMin = xMin.toFloat(),
                        xMax = xMax.toFloat(),
                        yMin = lo,
                        yMax = hi,
                        series = series,
                    ),
                )
            }
        }, cue)
    }

    private fun planPointPlot(args: Map<String, JsonElement>, cue: String?, color: Int?): Plan {
        val points = args.string("points").split(';').mapNotNull { pair ->
            val parts = pair.trim().split(',', ' ').filter { it.isNotBlank() }
            val x = parts.getOrNull(0)?.toFloatOrNull() ?: return@mapNotNull null
            val y = parts.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
            x to y
        }
        require(points.isNotEmpty()) { "plot_points needs data as x,y;x,y" }
        val connect = args.bool("connect")
        val id = args.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() } ?: drawingState.newId()
        val label = args.stringOrNull("label")
        val title = args.stringOrNull("title")
        val box = args.panelOrNull()

        return Plan(okWithId(id), {
            val existing = drawingState.find(id) as? DrawOp.Plot
            val index = existing?.series?.size ?: 0
            val series = DrawOp.Series(
                points = if (connect) points.sortedBy { it.first } else points,
                color = color ?: seriesColor(index),
                label = label,
                scatter = !connect,
            )
            val all = existing?.series.orEmpty() + series
            val xs = all.flatMap { s -> s.points.map { it.first } }
            val (xLo, xHi) = padded(xs.min(), xs.max())
            val (yLo, yHi) = yRange(all, null, null)
            val plot = DrawOp.Plot(
                box = existing?.box ?: box ?: NBox.DEFAULT_PANEL,
                title = title ?: existing?.title,
                xMin = if (existing != null) minOf(existing.xMin, xLo) else xLo,
                xMax = if (existing != null) maxOf(existing.xMax, xHi) else xHi,
                yMin = yLo,
                yMax = yHi,
                series = all,
            )
            if (existing != null) drawingState.update(id) { plot } else drawingState.put(id, plot)
        }, cue)
    }

    private fun seriesColor(index: Int) = Palette.CYCLE[index % Palette.CYCLE.size]

    private fun sample(expr: Expr, xMin: Double, xMax: Double, n: Int = 240): List<Pair<Float, Float>> =
        (0..n).map { i ->
            val x = xMin + (xMax - xMin) * i / n
            val y = expr.eval(x)
            x.toFloat() to (if (y.isFinite()) y.toFloat() else Float.NaN)
        }

    /** A y range that shows the curves well even when they blow up (tan, 1/x). */
    private fun yRange(series: List<DrawOp.Series>, yMin: Double?, yMax: Double?): Pair<Float, Float> {
        val ys = series.flatMap { s -> s.points.map { it.second } }.filter { it.isFinite() }.sorted()
        if (ys.isEmpty()) return (yMin ?: -1.0).toFloat() to (yMax ?: 1.0).toFloat()
        var lo = ys.first()
        var hi = ys.last()
        val pLo = ys[(ys.size * 0.03f).toInt().coerceIn(0, ys.lastIndex)]
        val pHi = ys[(ys.size * 0.97f).toInt().coerceIn(0, ys.lastIndex)]
        // Clip only when a few extreme samples would flatten everything else.
        if (pHi > pLo && (hi - lo) > 6 * (pHi - pLo)) {
            lo = pLo
            hi = pHi
        }
        val (padLo, padHi) = padded(lo, hi)
        val finalLo = yMin?.toFloat() ?: padLo
        val finalHi = yMax?.toFloat() ?: padHi
        return if (finalHi > finalLo) finalLo to finalHi else padLo to padHi
    }

    private fun padded(lo: Float, hi: Float): Pair<Float, Float> {
        if (hi - lo < 1e-6f) return (lo - 1f) to (hi + 1f)
        val pad = (hi - lo) * 0.08f
        return (lo - pad) to (hi + pad)
    }

    // ---------------------------------------------------------------------------------------
    // 3D and diagrams
    // ---------------------------------------------------------------------------------------

    private fun surface(args: Map<String, JsonElement>): DrawOp.Surface3D {
        val source = args.string("expression")
        val expr = Expr.parse(source)
        val xMin = args.doubleOrNull("x_min") ?: -3.0
        val xMax = args.doubleOrNull("x_max") ?: 3.0
        val yMin = args.doubleOrNull("y_min") ?: -3.0
        val yMax = args.doubleOrNull("y_max") ?: 3.0
        require(xMax > xMin && yMax > yMin) { "Ranges must have max greater than min" }
        val n = 26
        val heights = FloatArray(n * n)
        for (j in 0 until n) {
            for (i in 0 until n) {
                val x = xMin + (xMax - xMin) * i / (n - 1)
                val y = yMin + (yMax - yMin) * j / (n - 1)
                val z = expr.eval(x, y)
                heights[j * n + i] = if (z.isFinite()) z.toFloat() else Float.NaN
            }
        }
        val finite = heights.filter { it.isFinite() }.sorted()
        require(finite.isNotEmpty()) { "'$source' has no real values in that range" }
        var lo = finite.first()
        var hi = finite.last()
        val pLo = finite[(finite.size * 0.03f).toInt()]
        val pHi = finite[(finite.size * 0.97f).toInt().coerceAtMost(finite.lastIndex)]
        if (pHi > pLo && (hi - lo) > 6 * (pHi - pLo)) {
            lo = pLo
            hi = pHi
        }
        if (hi - lo < 1e-6f) {
            lo -= 1f
            hi += 1f
        }
        return DrawOp.Surface3D(
            box = args.panel(),
            title = args.stringOrNull("title") ?: "z = ${source.trim().take(28)}",
            n = n,
            heights = heights,
            zMin = lo,
            zMax = hi,
        )
    }

    private fun diagram(args: Map<String, JsonElement>): DrawOp.Diagram {
        val nodes = LinkedHashMap<String, DrawOp.Node>()
        args.string("nodes").split(';', '\n').forEach { raw ->
            val item = raw.trim()
            if (item.isEmpty()) return@forEach
            val key = item.substringBefore(':').trim()
            val label = item.substringAfter(':', key).trim().ifEmpty { key }
            if (key.isNotEmpty()) nodes[key] = DrawOp.Node(key, label.take(40))
        }
        require(nodes.isNotEmpty()) { "draw_diagram needs nodes as key:Label;key:Label" }
        require(nodes.size <= 16) { "Too many boxes (max 16); split into smaller diagrams" }
        val edges = args.stringOrNull("edges").orEmpty().split(';', '\n').mapNotNull { raw ->
            val item = raw.trim().replace("->", ">")
            if (!item.contains('>')) return@mapNotNull null
            val from = item.substringBefore('>').trim()
            val rest = item.substringAfter('>')
            val to = rest.substringBefore(':').trim()
            val label = rest.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }
            if (from.isEmpty() || to.isEmpty()) return@mapNotNull null
            // Arrows may mention boxes that were not declared; show them rather than fail.
            nodes.getOrPut(from) { DrawOp.Node(from, from) }
            nodes.getOrPut(to) { DrawOp.Node(to, to) }
            DrawOp.Edge(from, to, label?.take(24))
        }
        val layout = args.stringOrNull("layout")?.lowercase()?.takeIf { it in setOf("down", "right", "cycle") }
            ?: "down"
        return DrawOp.Diagram(args.panel(), args.stringOrNull("title"), nodes.values.toList(), edges, layout)
    }

    // ---------------------------------------------------------------------------------------
    // Argument helpers (the model sends numbers as ints, doubles or strings)
    // ---------------------------------------------------------------------------------------

    private fun ok() = JsonObject(mapOf("ok" to JsonPrimitive(true)))

    private fun okWithId(id: String) =
        JsonObject(mapOf("ok" to JsonPrimitive(true), "id" to JsonPrimitive(id)))

    private fun Map<String, JsonElement>.primitive(key: String): JsonPrimitive? =
        runCatching { this[key]?.jsonPrimitive }.getOrNull()

    private fun Map<String, JsonElement>.doubleOrNull(key: String): Double? {
        val prim = primitive(key) ?: return null
        return prim.doubleOrNull ?: prim.contentOrNull?.trim()?.toDoubleOrNull()
    }

    /** Screen coordinate, clamped into 0-1000. */
    private fun Map<String, JsonElement>.coord(key: String): Int {
        val v = doubleOrNull(key) ?: throw IllegalArgumentException("Missing number: $key")
        return v.toInt().coerceIn(0, 1000)
    }

    private fun Map<String, JsonElement>.box(): NBox {
        val x1 = coord("xmin")
        val y1 = coord("ymin")
        val x2 = coord("xmax")
        val y2 = coord("ymax")
        return NBox(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    /** Panel rectangle if the model gave a usable one. */
    private fun Map<String, JsonElement>.panelOrNull(): NBox? {
        if (listOf("xmin", "ymin", "xmax", "ymax").any { doubleOrNull(it) == null }) return null
        val b = box()
        // Too small to read: fall back to the default panel.
        return b.takeIf { it.right - it.left >= 150 && it.bottom - it.top >= 100 }
    }

    private fun Map<String, JsonElement>.panel(): NBox = panelOrNull() ?: NBox.DEFAULT_PANEL

    private fun Map<String, JsonElement>.bool(key: String): Boolean {
        val prim = primitive(key) ?: return false
        return prim.booleanOrNull ?: prim.contentOrNull?.trim()?.equals("true", ignoreCase = true) ?: false
    }

    private fun Map<String, JsonElement>.string(key: String): String =
        stringOrNull(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing text: $key")

    private fun Map<String, JsonElement>.stringOrNull(key: String): String? =
        primitive(key)?.contentOrNull
}
