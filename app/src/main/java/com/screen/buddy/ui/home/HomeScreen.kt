package com.screen.buddy.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screen.buddy.BuildConfig
import com.screen.buddy.R
import com.screen.buddy.overlay.OverlayService
import com.screen.buddy.ui.theme.Ink700
import com.screen.buddy.ui.theme.Ink800
import com.screen.buddy.ui.theme.Ink900
import com.screen.buddy.ui.theme.Ink950
import com.screen.buddy.ui.theme.Mist
import com.screen.buddy.ui.theme.MistDim
import com.screen.buddy.ui.theme.Seafoam
import com.screen.buddy.ui.theme.SeafoamDim

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDebug: () -> Unit,
    onReOnboard: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val screenAccessOff by viewModel.screenAccessOff.collectAsStateWithLifecycle()
    val showBatteryHint by viewModel.showBatteryHint.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var batteryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(showBatteryHint) {
        if (showBatteryHint) batteryDialog = true
    }

    val pulse = rememberInfiniteTransition(label = "hero")
    val glow by pulse.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            tween(2_200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Ink950, Ink900, Ink800),
                ),
            ),
    ) {
        // Soft atmosphere, not flat.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Seafoam.copy(alpha = 0.14f), Ink950.copy(alpha = 0f)),
                        radius = 900f,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(Modifier.height(28.dp))
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .scale(glow)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Seafoam, SeafoamDim)),
                        )
                        .border(2.dp, Mist.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "B",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Ink950,
                    )
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    "Buddy",
                    style = MaterialTheme.typography.displayLarge,
                    color = Mist,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your live tutor on any screen. Tap the bubble, ask out loud, watch it draw as it explains.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MistDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (screenAccessOff) {
                    Button(
                        onClick = onReOnboard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = Mist,
                        ),
                    ) {
                        Text("Turn on screen access", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Button(
                    onClick = { OverlayService.start(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Seafoam,
                        contentColor = Ink950,
                    ),
                ) {
                    Text(
                        if (paused) "Show bubble (paused)" else "Show bubble",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                RowActions(
                    paused = paused,
                    onPauseToggle = {
                        val next = !paused
                        viewModel.setPaused(next)
                        val action =
                            if (next) OverlayService.ACTION_PAUSE else OverlayService.ACTION_RESUME
                        context.startService(
                            Intent(context, OverlayService::class.java).setAction(action),
                        )
                        if (!next) OverlayService.start(context)
                    },
                    onOpenSettings = onOpenSettings,
                    onOpenHistory = onOpenHistory,
                    onReOnboard = onReOnboard,
                    onOpenDebug = onOpenDebug,
                )
            }
        }
    }

    if (batteryDialog) {
        AlertDialog(
            onDismissRequest = {
                batteryDialog = false
                viewModel.markBatteryHintShown()
            },
            title = { Text(stringResource(R.string.battery_hint_title)) },
            text = { Text(stringResource(R.string.battery_hint_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        batteryDialog = false
                        viewModel.markBatteryHintShown()
                        openBatterySettings(context)
                    },
                ) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        batteryDialog = false
                        viewModel.markBatteryHintShown()
                    },
                ) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun RowActions(
    paused: Boolean,
    onPauseToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onReOnboard: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val chip = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .clip(shape)
        .background(Ink700.copy(alpha = 0.7f))
        .border(1.dp, Mist.copy(alpha = 0.08f), shape)

    @Composable
    fun Chip(label: String, onClick: () -> Unit) {
        TextButton(
            onClick = onClick,
            modifier = chip,
            shape = shape,
        ) {
            Text(label, color = Mist, style = MaterialTheme.typography.labelLarge)
        }
    }

    Chip(if (paused) "Resume" else "Pause") { onPauseToggle() }
    Chip("Settings", onOpenSettings)
    Chip("History", onOpenHistory)
    Chip("Permissions", onReOnboard)
    if (BuildConfig.DEBUG) {
        Chip("Debug", onOpenDebug)
    }
}

private fun openBatterySettings(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}
