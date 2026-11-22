package com.screen.buddy.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screen.buddy.capture.ProjectionHolder
import com.screen.buddy.data.SettingsRepository
import com.screen.buddy.overlay.OverlayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectionHolder: ProjectionHolder,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _refresh = MutableStateFlow(0)

    val status: StateFlow<PermissionStatus> = combine(
        _refresh,
        projectionHolder.isActive,
        projectionHolder.needsReconsent,
    ) { _, active, needsReconsent ->
        PermissionStatus(
            overlayGranted = Settings.canDrawOverlays(context),
            micGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
            notificationsGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                true
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            },
            projectionGranted = active || (projectionHolder.hasConsent() && !needsReconsent),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PermissionStatus())

    fun refresh() {
        _refresh.value = _refresh.value + 1
    }

    fun overlaySettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri(),
        )
    }

    fun onProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            projectionHolder.storeConsent(resultCode, data)
            OverlayService.start(context)
            // Projection is created inside the service after startForeground.
        } else {
            projectionHolder.revokeConsent()
        }
        refresh()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingDone(true)
            OverlayService.start(context)
        }
    }
}
