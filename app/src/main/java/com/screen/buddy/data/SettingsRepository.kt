package com.screen.buddy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "buddy_settings")

enum class InputMode { HOLD, TAP }

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val inputMode = stringPreferencesKey("input_mode")
        val voice = stringPreferencesKey("voice")
        val languageHint = stringPreferencesKey("language_hint")
        val drawingDurationSec = intPreferencesKey("drawing_duration_sec")
        val paused = booleanPreferencesKey("paused")
        val batteryHintShown = booleanPreferencesKey("battery_hint_shown")
    }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.onboardingDone] ?: false }
    val inputMode: Flow<InputMode> = context.dataStore.data.map {
        runCatching { InputMode.valueOf(it[Keys.inputMode] ?: InputMode.HOLD.name) }
            .getOrDefault(InputMode.HOLD)
    }
    val voice: Flow<String> = context.dataStore.data.map { it[Keys.voice] ?: "Fenrir" }
    val languageHint: Flow<String> = context.dataStore.data.map { it[Keys.languageHint] ?: "" }
    val drawingDurationSec: Flow<Int> = context.dataStore.data.map { it[Keys.drawingDurationSec] ?: 8 }
    val paused: Flow<Boolean> = context.dataStore.data.map { it[Keys.paused] ?: false }
    val batteryHintShown: Flow<Boolean> = context.dataStore.data.map { it[Keys.batteryHintShown] ?: false }

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[Keys.onboardingDone] = done }
    }

    suspend fun setInputMode(mode: InputMode) {
        context.dataStore.edit { it[Keys.inputMode] = mode.name }
    }

    suspend fun setVoice(voice: String) {
        context.dataStore.edit { it[Keys.voice] = voice }
    }

    suspend fun setLanguageHint(hint: String) {
        context.dataStore.edit { it[Keys.languageHint] = hint }
    }

    suspend fun setDrawingDurationSec(seconds: Int) {
        context.dataStore.edit { it[Keys.drawingDurationSec] = seconds.coerceIn(3, 30) }
    }

    suspend fun setPaused(paused: Boolean) {
        context.dataStore.edit { it[Keys.paused] = paused }
    }

    suspend fun setBatteryHintShown(shown: Boolean) {
        context.dataStore.edit { it[Keys.batteryHintShown] = shown }
    }
}
