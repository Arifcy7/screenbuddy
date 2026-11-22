package com.screen.buddy.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screen.buddy.capture.ScreenAccess
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
    private val screenAccess: ScreenAccess,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _refresh = MutableStateFlow(0)

    val status: StateFlow<PermissionStatus> = combine(
        _refresh,
        screenAccess.isActive,
    ) { _, screenAccessOn ->
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
            screenAccessGranted = screenAccessOn,
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

    /** The system screen where the user switches Buddy's accessibility service on. */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingDone(true)
            OverlayService.start(context)
        }
    }
}
