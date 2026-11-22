package com.screen.buddy.ui.onboarding

data class PermissionStatus(
    val overlayGranted: Boolean = false,
    val micGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val projectionGranted: Boolean = false,
) {
    val allGranted: Boolean
        get() = overlayGranted && micGranted && notificationsGranted && projectionGranted
}
