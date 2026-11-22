package com.screen.buddy.live

import com.screen.buddy.overlay.DrawOp
import com.screen.buddy.overlay.DrawingState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRouter @Inject constructor(
    private val drawingState: DrawingState,
) {
    @Volatile
    var captureOrientation: Int = 0

    fun route(name: String, args: Map<String, JsonElement>): JsonObject {
        return try {
            when (name) {
                "point_at" -> {
                    val x = args.int("x")
                    val y = args.int("y")
                    val label = args.stringOrNull("label")
                    drawingState.add(
                        DrawOp.Ring(x, y, label, captureOrientation),
                    )
                    ok()
                }
                "highlight_box" -> {
                    drawingState.add(
                        DrawOp.HighlightBox(
                            ymin = args.int("ymin"),
                            xmin = args.int("xmin"),
                            ymax = args.int("ymax"),
                            xmax = args.int("xmax"),
                            label = args.stringOrNull("label"),
                            captureOrientation = captureOrientation,
                        ),
                    )
                    ok()
                }
                "draw_arrow" -> {
                    drawingState.add(
                        DrawOp.Arrow(
                            fromX = args.int("from_x"),
                            fromY = args.int("from_y"),
                            toX = args.int("to_x"),
                            toY = args.int("to_y"),
                            label = args.stringOrNull("label"),
                            captureOrientation = captureOrientation,
                        ),
                    )
                    ok()
                }
                "clear_drawings" -> {
                    drawingState.clear()
                    ok()
                }
                else -> JsonObject(mapOf("error" to JsonPrimitive("Unknown function: $name")))
            }
        } catch (e: Exception) {
            JsonObject(mapOf("error" to JsonPrimitive(e.message ?: "tool failed")))
        }
    }

    private fun ok() = JsonObject(mapOf("ok" to JsonPrimitive(true)))

    private fun Map<String, JsonElement>.int(key: String): Int {
        val v = this[key]?.jsonPrimitive?.intOrNull
            ?: error("Missing int arg: $key")
        require(v in 0..1000) { "$key out of range: $v" }
        return v
    }

    private fun Map<String, JsonElement>.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
