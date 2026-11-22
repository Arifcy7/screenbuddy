package com.screen.buddy.live

import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool

object BuddyTools {
    private val pointAt = FunctionDeclaration(
        name = "point_at",
        description = "Draw a ring at a point on the screenshot. Coordinates 0-1000 from top-left.",
        parameters = mapOf(
            "x" to Schema.integer("X coordinate 0-1000"),
            "y" to Schema.integer("Y coordinate 0-1000"),
            "label" to Schema.string("Optional short label"),
        ),
        optionalParameters = listOf("label"),
    )

    private val highlightBox = FunctionDeclaration(
        name = "highlight_box",
        description = "Draw a rounded highlight box. Coordinates 0-1000 from top-left.",
        parameters = mapOf(
            "ymin" to Schema.integer("Top Y 0-1000"),
            "xmin" to Schema.integer("Left X 0-1000"),
            "ymax" to Schema.integer("Bottom Y 0-1000"),
            "xmax" to Schema.integer("Right X 0-1000"),
            "label" to Schema.string("Optional short label"),
        ),
        optionalParameters = listOf("label"),
    )

    private val drawArrow = FunctionDeclaration(
        name = "draw_arrow",
        description = "Draw an arrow from one point to another. Coordinates 0-1000.",
        parameters = mapOf(
            "from_x" to Schema.integer("Start X 0-1000"),
            "from_y" to Schema.integer("Start Y 0-1000"),
            "to_x" to Schema.integer("End X 0-1000"),
            "to_y" to Schema.integer("End Y 0-1000"),
            "label" to Schema.string("Optional short label"),
        ),
        optionalParameters = listOf("label"),
    )

    private val clearDrawings = FunctionDeclaration(
        name = "clear_drawings",
        description = "Clear all drawings on the screen.",
        parameters = emptyMap(),
        optionalParameters = emptyList(),
    )

    val tool: Tool = Tool.functionDeclarations(
        listOf(pointAt, highlightBox, drawArrow, clearDrawings),
    )
}
