package com.screen.buddy.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refresh() }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onProjectionResult(result.resultCode, result.data)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Welcome to Buddy", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Grant these so Buddy can float over apps, hear you, and see one screenshot when you summon it.",
            style = MaterialTheme.typography.bodyMedium,
        )

        PermissionStep(
            title = "Display over other apps",
            granted = status.overlayGranted,
            onAction = { overlayLauncher.launch(viewModel.overlaySettingsIntent()) },
        )
        PermissionStep(
            title = "Microphone",
            granted = status.micGranted,
            onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionStep(
                title = "Notifications",
                granted = status.notificationsGranted,
                onAction = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        } else {
            PermissionStep(
                title = "Notifications",
                granted = true,
                onAction = {},
                actionLabel = "Not required",
            )
        }
        PermissionStep(
            title = "Screen capture",
            granted = status.projectionGranted,
            onAction = {
                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            },
        )

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                viewModel.completeOnboarding()
                onFinished()
            },
            enabled = status.allGranted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
        if (!status.allGranted) {
            Text(
                "All four steps must be granted before continuing.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PermissionStep(
    title: String,
    granted: Boolean,
    onAction: () -> Unit,
    actionLabel: String = if (granted) "Granted" else "Grant",
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (granted) "Ready" else "Required",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (granted) {
                OutlinedButton(onClick = onAction, enabled = actionLabel != "Not required") {
                    Text(actionLabel)
                }
            } else {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
