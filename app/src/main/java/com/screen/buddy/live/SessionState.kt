package com.screen.buddy.live

sealed class SessionState {
    data object Idle : SessionState()
    data object Connecting : SessionState()
    data object Listening : SessionState()
    data object Thinking : SessionState()
    data object Speaking : SessionState()
    data class Error(val message: String) : SessionState()
}
