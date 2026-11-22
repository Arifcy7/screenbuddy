package com.screen.buddy.ui.home

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screen.buddy.capture.ProjectionHolder
import com.screen.buddy.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    projectionHolder: ProjectionHolder,
) : ViewModel() {

    val paused = settingsRepository.paused.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )

    val needsReconsent = projectionHolder.needsReconsent.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )

    val showBatteryHint = combine(
        settingsRepository.batteryHintShown,
        flowOf(isAggressiveOem()),
    ) { shown, aggressive -> aggressive && !shown }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )

    fun setPaused(value: Boolean) {
        viewModelScope.launch { settingsRepository.setPaused(value) }
    }

    fun markBatteryHintShown() {
        viewModelScope.launch { settingsRepository.setBatteryHintShown(true) }
    }

    private fun isAggressiveOem(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return listOf("xiaomi", "huawei", "oppo", "vivo", "realme", "oneplus", "samsung")
            .any { m.contains(it) }
    }
}
