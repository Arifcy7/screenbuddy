package com.screen.buddy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screen.buddy.data.InputMode

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val inputMode by viewModel.inputMode.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val language by viewModel.languageHint.collectAsStateWithLifecycle()
    val duration by viewModel.drawingDurationSec.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    var languageField by remember(language) { mutableStateOf(language) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("Input mode", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = inputMode == InputMode.HOLD,
                onClick = { viewModel.setInputMode(InputMode.HOLD) },
                label = { Text("Hold to talk") },
            )
            FilterChip(
                selected = inputMode == InputMode.TAP,
                onClick = { viewModel.setInputMode(InputMode.TAP) },
                label = { Text("Tap to toggle") },
            )
        }

        Text("Voice", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Fenrir", "Puck", "Charon", "Kore", "Aoede").forEach { name ->
                FilterChip(
                    selected = voice.equals(name, ignoreCase = true),
                    onClick = { viewModel.setVoice(name) },
                    label = { Text(name) },
                )
            }
        }

        OutlinedTextField(
            value = languageField,
            onValueChange = {
                languageField = it
                viewModel.setLanguageHint(it)
            },
            label = { Text("Language hint (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Drawing duration: ${duration}s", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = duration.toFloat(),
            onValueChange = { viewModel.setDrawingDuration(it.toInt()) },
            valueRange = 3f..30f,
            steps = 26,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Pause Buddy")
            Switch(checked = paused, onCheckedChange = { viewModel.setPaused(it) })
        }

        TextButton(onClick = { viewModel.clearHistory() }) {
            Text("Clear conversation history")
        }
    }
}
