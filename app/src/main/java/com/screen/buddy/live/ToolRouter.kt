package com.screen.buddy.live

import com.screen.buddy.overlay.DrawingState
import javax.inject.Inject
import javax.inject.Singleton

/** Maps Gemini function calls to DrawingState actions. Implemented in M5. */
@Singleton
class ToolRouter @Inject constructor(
    private val drawingState: DrawingState,
) {
    fun route(name: String, argsJson: String): String {
        // M5
        return """{"ok":true}"""
    }
}
