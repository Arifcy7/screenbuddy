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
import com.screen.buddy.overlay.DrawingState
import com.screen.buddy.overlay.OverlayService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
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
) : ViewModel() {
    suspend fun capture() = screenCaptureManager.captureOnce()
    fun lastBitmap() = screenCaptureManager.lastDebugBitmap

    fun testCenterRing(orientation: Int) {
        drawingState.clear()
        drawingState.add(DrawOp.Ring(500, 500, "center", orientation))
    }

    fun testBox(orientation: Int) {
        drawingState.clear()
        drawingState.add(
            DrawOp.HighlightBox(200, 200, 800, 800, "box", orientation),
        )
    }

    fun clearDrawings() = drawingState.clear()
}
