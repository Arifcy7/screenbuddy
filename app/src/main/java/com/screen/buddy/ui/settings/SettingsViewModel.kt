package com.screen.buddy.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screen.buddy.data.InputMode
import com.screen.buddy.data.SettingsRepository
import com.screen.buddy.data.db.MessageDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val messageDao: MessageDao,
) : ViewModel() {

    val inputMode = settingsRepository.inputMode.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), InputMode.HOLD,
    )
    val voice = settingsRepository.voice.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), "Fenrir",
    )
    val languageHint = settingsRepository.languageHint.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), "",
    )
    val drawingDurationSec = settingsRepository.drawingDurationSec.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), 8,
    )
    val paused = settingsRepository.paused.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false,
    )

    fun setInputMode(mode: InputMode) = viewModelScope.launch {
        settingsRepository.setInputMode(mode)
    }

    fun setVoice(voice: String) = viewModelScope.launch {
        settingsRepository.setVoice(voice)
    }

    fun setLanguageHint(hint: String) = viewModelScope.launch {
        settingsRepository.setLanguageHint(hint)
    }

    fun setDrawingDuration(sec: Int) = viewModelScope.launch {
        settingsRepository.setDrawingDurationSec(sec)
    }

    fun setPaused(paused: Boolean) = viewModelScope.launch {
        settingsRepository.setPaused(paused)
    }

    fun clearHistory() = viewModelScope.launch {
        messageDao.clearAll()
    }
}
