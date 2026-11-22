package com.screen.buddy.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase AI Logic Live API session.
 * Model: gemini-3.1-flash-live-preview (approved for M0 planning).
 * Implemented in M5.
 */
@Singleton
class LiveSessionManager @Inject constructor(
    private val toolRouter: ToolRouter,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    suspend fun startSession(jpegFrame: ByteArray?) {
        // M5
    }

    suspend fun stopSession() {
        // M5
        _state.value = SessionState.Idle
    }
}
