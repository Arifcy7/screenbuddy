package com.screen.buddy.ui.debug

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.screen.buddy.capture.ScreenCaptureManager
import com.screen.buddy.overlay.DrawOp
import com.screen.buddy.live.ToolRouter
import com.screen.buddy.overlay.DrawingState
import com.screen.buddy.overlay.NBox
import com.screen.buddy.overlay.OverlayService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Debug", style = MaterialTheme.typography.headlineMedium)

        Button(
            onClick = {
                scope.launch {
                    val result = viewModel.capture()
                    bitmap = result.bitmap ?: viewModel.lastBitmap()
                    status = when {
                        result.isSecureBlack -> "FLAG_SECURE / black frame detected"
                        result.jpeg != null -> "Captured ${result.jpeg.size} bytes JPEG"
                        else -> "Capture failed — check screen permission"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Capture one frame") }

        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Last frame",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
            )
        }
        if (status.isNotBlank()) Text(status)

        Button(
            onClick = {
                OverlayService.start(context)
                viewModel.testCenterRing(configuration.orientation)
                context.startService(
                    Intent(context, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_DEBUG_DRAW),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Test ring at (500,500)") }

        Button(
            onClick = {
                viewModel.testBox(configuration.orientation)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Test highlight box") }

        Text("Teaching board demos (drawn over this screen)", style = MaterialTheme.typography.titleSmall)
        Button(
            onClick = { OverlayService.start(context); viewModel.demoMaths() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Demo: maths (plots, 3D surface)") }
        Button(
            onClick = { OverlayService.start(context); viewModel.demoMachineLearning() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Demo: machine learning") }
        Button(
            onClick = { OverlayService.start(context); viewModel.demoBiology() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Demo: biology diagrams") }

        Button(
            onClick = { viewModel.clearDrawings() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Clear drawings") }
    }
}

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val screenCaptureManager: ScreenCaptureManager,
    private val drawingState: DrawingState,
    private val toolRouter: ToolRouter,
) : ViewModel() {
    suspend fun capture() = screenCaptureManager.captureOnce()
    fun lastBitmap() = screenCaptureManager.lastDebugBitmap

    @Suppress("UNUSED_PARAMETER")
    fun testCenterRing(orientation: Int) {
        drawingState.clear()
        drawingState.add(DrawOp.Ring(500, 500, "center"))
    }

    @Suppress("UNUSED_PARAMETER")
    fun testBox(orientation: Int) {
        drawingState.clear()
        drawingState.add(DrawOp.HighlightBox(NBox(200, 200, 800, 800), "box"))
    }

    /** Runs a tool exactly as the model would, so the board can be checked without a call. */
    private fun tool(name: String, vararg args: Pair<String, Any>) {
        toolRouter.route(
            name,
            args.associate { (k, v) ->
                k to when (v) {
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            },
        )
    }

    fun demoMaths() {
        drawingState.clear()
        tool("plot_function", "expression" to "sin(x)", "id" to "p", "title" to "Waves",
            "xmin" to 40, "ymin" to 60, "xmax" to 960, "ymax" to 430)
        tool("plot_function", "expression" to "cos(x)", "id" to "p")
        tool("plot_function", "expression" to "x^2/8 - 1", "id" to "p")
        tool("draw_3d_surface", "expression" to "sin(x)*cos(y)",
            "xmin" to 40, "ymin" to 450, "xmax" to 960, "ymax" to 840)
        tool("draw_text", "x" to 60, "y" to 860, "text" to "d/dx sin(x) = cos(x)", "size" to 20)
        drawingState.clearAfter(DEMO_MS)
    }

    fun demoMachineLearning() {
        drawingState.clear()
        tool("draw_neural_network", "layers" to "3,5,5,2", "title" to "Feed-forward network",
            "xmin" to 40, "ymin" to 60, "xmax" to 960, "ymax" to 400)
        tool("plot_function", "expression" to "sigmoid(x)", "id" to "act", "title" to "Activations",
            "xmin" to 40, "ymin" to 420, "xmax" to 500, "ymax" to 700)
        tool("plot_function", "expression" to "relu(x)", "id" to "act")
        tool("draw_3d_surface", "expression" to "x^2 + y^2", "title" to "Loss surface",
            "xmin" to 520, "ymin" to 420, "xmax" to 960, "ymax" to 700)
        tool("plot_points", "points" to "1,2.3;2,1.6;3,1.1;4,0.8;5,0.62;6,0.5;7,0.44", "connect" to true,
            "title" to "Training loss", "xmin" to 40, "ymin" to 720, "xmax" to 960, "ymax" to 930)
        drawingState.clearAfter(DEMO_MS)
    }

    fun demoBiology() {
        drawingState.clear()
        tool("draw_diagram",
            "nodes" to "sun:Sunlight;leaf:Chlorophyll;water:Water;co2:CO₂;glu:Glucose;o2:Oxygen",
            "edges" to "sun>leaf;water>leaf;co2>leaf;leaf>glu:makes;leaf>o2:releases",
            "title" to "Photosynthesis", "xmin" to 40, "ymin" to 60, "xmax" to 960, "ymax" to 470)
        tool("draw_diagram", "nodes" to "g1:G1;s:S phase;g2:G2;m:Mitosis",
            "edges" to "g1>s;s>g2;g2>m;m>g1", "layout" to "cycle", "title" to "Cell cycle",
            "xmin" to 40, "ymin" to 490, "xmax" to 500, "ymax" to 860)
        tool("draw_3d_shape", "kind" to "sphere", "label" to "Cell (sphere)",
            "xmin" to 520, "ymin" to 490, "xmax" to 960, "ymax" to 860)
        tool("point_at", "x" to 500, "y" to 900, "label" to "Pointer")
        drawingState.clearAfter(DEMO_MS)
    }

    private companion object {
        /** Demos cover the buttons underneath, so they tidy themselves away. */
        const val DEMO_MS = 25_000L
    }

    fun clearDrawings() = drawingState.clear()
}
