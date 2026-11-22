package com.screen.buddy.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screen.buddy.BuildConfig
import com.screen.buddy.R
import com.screen.buddy.overlay.OverlayService

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDebug: () -> Unit,
    onReOnboard: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val needsReconsent by viewModel.needsReconsent.collectAsStateWithLifecycle()
    val showBatteryHint by viewModel.showBatteryHint.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var batteryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(showBatteryHint) {
        if (showBatteryHint) batteryDialog = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Buddy", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Hold the floating bubble over any app and ask what you see. " +
                "Buddy answers out loud and can point at the screen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { OverlayService.start(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Show bubble")
        }
        OutlinedButton(
            onClick = {
                val next = !paused
                viewModel.setPaused(next)
                val action = if (next) OverlayService.ACTION_PAUSE else OverlayService.ACTION_RESUME
                context.startService(Intent(context, OverlayService::class.java).setAction(action))
                if (!next) OverlayService.start(context)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (paused) "Resume" else "Pause")
        }
        if (needsReconsent) {
            Button(onClick = onReOnboard, modifier = Modifier.fillMaxWidth()) {
                Text("Re-enable screen access")
            }
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Settings")
        }
        OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
            Text("History")
        }
        if (BuildConfig.DEBUG) {
            OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                Text("Debug capture / drawings")
            }
        }
        OutlinedButton(onClick = onReOnboard, modifier = Modifier.fillMaxWidth()) {
            Text("Permissions")
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

private fun openBatterySettings(context: android.content.Context) {
    // Use the settings screen (not REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) to avoid Play policy flags.
    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}
