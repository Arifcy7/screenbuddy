package com.screen.buddy.live

import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool

/**
 * Everything Buddy can draw. All positions are integers 0-1000 over the latest screenshot
 * (x right, y down). Every drawing tool also accepts:
 *  - cue:   a few words from the sentence being spoken, so the drawing appears at that moment
 *  - color: a name (red, orange, yellow, green, teal, blue, purple, pink, white, grey) or #RRGGBB
 *  - id:    a name for the drawing, to replace it later or remove it with erase
 */
object BuddyTools {
    private fun coord(description: String) =
        Schema.integer(description, minimum = 0.0, maximum = 1000.0)

    private val common = mapOf(
        "color" to Schema.string("red, orange, yellow, green, teal, blue, purple, pink, white, grey or #RRGGBB"),
        "id" to Schema.string("Name for this drawing so you can replace or erase it later"),
    )

    /** Optional panel rectangle for charts, 3D, diagrams and images. */
    private val panel = mapOf(
        "xmin" to coord("Panel left"),
        "ymin" to coord("Panel top"),
        "xmax" to coord("Panel right"),
        "ymax" to coord("Panel bottom"),
    )

    private fun declare(
        name: String,
        description: String,
        required: Map<String, Schema>,
        optional: Map<String, Schema> = emptyMap(),
    ): FunctionDeclaration {
        val allOptional = optional + common
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = required + allOptional,
            optionalParameters = allOptional.keys.toList(),
        )
    }

    private val pointAt = declare(
        "point_at",
        "REQUIRED when you mention a visible UI element: draws a ring on the user's screen at " +
            "that spot. Fades by itself after a few seconds.",
        required = mapOf("x" to coord("X"), "y" to coord("Y")),
        optional = mapOf("label" to Schema.string("Short label")),
    )

    private val highlightBox = declare(
        "highlight_box",
        "Outline a region of the user's screen (a button, a paragraph, a row). Fades by itself.",
        required = mapOf(
            "xmin" to coord("Left"), "ymin" to coord("Top"),
            "xmax" to coord("Right"), "ymax" to coord("Bottom"),
        ),
        optional = mapOf("label" to Schema.string("Short label")),
    )

    private val drawArrow = declare(
        "draw_arrow",
        "Draw an arrow from one point to another, optionally labelled.",
        required = mapOf(
            "from_x" to coord("Start X"), "from_y" to coord("Start Y"),
            "to_x" to coord("End X"), "to_y" to coord("End Y"),
        ),
        optional = mapOf("label" to Schema.string("Short label")),
    )

    private val drawText = declare(
        "draw_text",
        "Write text on the board: an equation, a definition, one step of a derivation. Use one " +
            "call per line or step so they appear one by one. Use \\n for a line break.",
        required = mapOf(
            "x" to coord("Left edge of the text"),
            "y" to coord("Top edge of the text"),
            "text" to Schema.string("Text to show, e.g. 'y = mx + c'"),
        ),
        optional = mapOf("size" to Schema.integer("Font size 12-48, default 18", minimum = 12.0, maximum = 48.0)),
    )

    private val drawPath = declare(
        "draw_path",
        "Draw a freehand line or polygon through points, for sketches and geometry.",
        required = mapOf("points" to Schema.string("x,y;x,y;... in 0-1000")),
        optional = mapOf(
            "closed" to Schema.boolean("Join the last point to the first"),
            "fill" to Schema.boolean("Fill the closed shape with a tint"),
        ),
    )

    private val drawShape = declare(
        "draw_shape",
        "Draw a basic shape inside a box.",
        required = mapOf(
            "kind" to Schema.enumeration(
                listOf("rect", "rounded", "circle", "ellipse", "line", "triangle", "diamond"),
                "Shape",
            ),
            "xmin" to coord("Left"), "ymin" to coord("Top"),
            "xmax" to coord("Right"), "ymax" to coord("Bottom"),
        ),
        optional = mapOf(
            "label" to Schema.string("Text centred in the shape"),
            "fill" to Schema.boolean("Tint the inside"),
        ),
    )

    private val plotFunction = declare(
        "plot_function",
        "Plot y = f(x) accurately on a chart with axes, grid and numbers. The phone computes " +
            "the curve, so give the formula, never points. To add another curve to the same " +
            "chart, call again with the same id. Examples: 'x^2', 'sin(x)', '1/(1+exp(-x))', " +
            "'relu(x)', 'tanh(x)', 'exp(-x^2/2)'.",
        required = mapOf("expression" to Schema.string("Formula in x")),
        optional = mapOf(
            "x_min" to Schema.double("Left end of x axis, default -5"),
            "x_max" to Schema.double("Right end of x axis, default 5"),
            "y_min" to Schema.double("Bottom of y axis (default: fitted to the curve)"),
            "y_max" to Schema.double("Top of y axis (default: fitted to the curve)"),
            "title" to Schema.string("Chart title"),
            "label" to Schema.string("Legend label for this curve"),
        ) + panel,
    )

    private val plotPoints = declare(
        "plot_points",
        "Plot data points on a chart with axes (scatter plot, training loss, measurements). " +
            "Points are real data values, not screen coordinates. Reuse an id to add to a chart.",
        required = mapOf("points" to Schema.string("Data as x,y;x,y;... e.g. '1,2.5;2,3.1'")),
        optional = mapOf(
            "connect" to Schema.boolean("Join the points with a line"),
            "title" to Schema.string("Chart title"),
            "label" to Schema.string("Legend label"),
        ) + panel,
    )

    private val draw3dSurface = declare(
        "draw_3d_surface",
        "Draw z = f(x, y) as a rotating 3D surface. Good for loss landscapes, saddles, bowls, " +
            "waves. Examples: 'x^2 + y^2', 'x^2 - y^2', 'sin(x)*cos(y)', 'exp(-(x^2+y^2))'.",
        required = mapOf("expression" to Schema.string("Formula in x and y")),
        optional = mapOf(
            "x_min" to Schema.double("default -3"), "x_max" to Schema.double("default 3"),
            "y_min" to Schema.double("default -3"), "y_max" to Schema.double("default 3"),
            "title" to Schema.string("Title"),
        ) + panel,
    )

    private val draw3dShape = declare(
        "draw_3d_shape",
        "Draw a rotating 3D solid as a wireframe.",
        required = mapOf(
            "kind" to Schema.enumeration(
                listOf("cube", "cuboid", "sphere", "cylinder", "cone", "pyramid", "tetrahedron", "torus", "axes"),
                "Solid",
            ),
        ),
        optional = mapOf("label" to Schema.string("Caption")) + panel,
    )

    private val drawDiagram = declare(
        "draw_diagram",
        "Draw a labelled boxes-and-arrows diagram, laid out automatically: flowcharts, " +
            "pipelines, biological cycles, hierarchies, model architectures.",
        required = mapOf(
            "nodes" to Schema.string("Boxes as key:Label;key:Label e.g. 'a:Sunlight;b:Chlorophyll;c:Glucose'"),
        ),
        optional = mapOf(
            "edges" to Schema.string("Arrows as from>to or from>to:label, separated by ; e.g. 'a>b;b>c:produces'"),
            "layout" to Schema.enumeration(listOf("down", "right", "cycle"), "Flow direction, default down"),
            "title" to Schema.string("Title"),
        ) + panel,
    )

    private val drawNeuralNetwork = declare(
        "draw_neural_network",
        "Draw a neural network: columns of neurons with connections between adjacent layers.",
        required = mapOf("layers" to Schema.string("Neurons per layer, e.g. '3,5,5,2'")),
        optional = mapOf(
            "labels" to Schema.string("Layer names, e.g. 'Input,Hidden,Hidden,Output'"),
            "title" to Schema.string("Title"),
        ) + panel,
    )

    private val showImage = declare(
        "show_image",
        "Generate and show an illustration when shapes and charts are not enough (a cell, the " +
            "heart, a plant, a CNN feature map). Takes several seconds to appear; keep " +
            "explaining meanwhile. Use sparingly.",
        required = mapOf("prompt" to Schema.string("What the picture should show, with the labels wanted")),
        optional = mapOf("caption" to Schema.string("Caption under the picture")) + panel,
    )

    private val drawWireframe = declare(
        "draw_wireframe",
        "Draw custom line art from several polylines you have already projected to 2D.",
        required = mapOf("polylines" to Schema.string("Paths separated by | , each x,y;x,y;...")),
        optional = mapOf("title" to Schema.string("Title")),
    )

    private val erase = FunctionDeclaration(
        name = "erase",
        description = "Remove specific drawings by id (ids are returned by every drawing call).",
        parameters = mapOf("ids" to Schema.string("Comma-separated ids, e.g. 'd3,d4'")),
    )

    private val clearDrawings = FunctionDeclaration(
        name = "clear_drawings",
        description = "Wipe the whole board. Do this before starting a new topic.",
        parameters = emptyMap(),
        optionalParameters = emptyList(),
    )

    val tool: Tool = Tool.functionDeclarations(
        listOf(
            pointAt,
            highlightBox,
            drawArrow,
            drawText,
            drawPath,
            drawShape,
            plotFunction,
            plotPoints,
            draw3dSurface,
            draw3dShape,
            drawDiagram,
            drawNeuralNetwork,
            showImage,
            drawWireframe,
            erase,
            clearDrawings,
        ),
    )
}
