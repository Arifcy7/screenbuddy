package com.screen.buddy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.screen.buddy.data.SettingsRepository
import com.screen.buddy.ui.debug.DebugScreen
import com.screen.buddy.ui.history.HistoryScreen
import com.screen.buddy.ui.home.HomeScreen
import com.screen.buddy.ui.onboarding.OnboardingScreen
import com.screen.buddy.ui.settings.SettingsScreen
import com.screen.buddy.ui.theme.ScreenBuddyTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScreenBuddyTheme {
                val navController = rememberNavController()
                val rootVm: RootViewModel = hiltViewModel()
                val onboardingDone by rootVm.onboardingDone.collectAsStateWithLifecycle()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = if (onboardingDone) "home" else "onboarding",
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable("onboarding") {
                            OnboardingScreen(
                                onFinished = {
                                    navController.navigate("home") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable("home") {
                            HomeScreen(
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenHistory = { navController.navigate("history") },
                                onOpenDebug = { navController.navigate("debug") },
                                onReOnboard = { navController.navigate("onboarding") },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("history") {
                            HistoryScreen(onBack = { navController.popBackStack() })
                        }
                        composable("debug") {
                            DebugScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val onboardingDone = settingsRepository.onboardingDone.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )
}
