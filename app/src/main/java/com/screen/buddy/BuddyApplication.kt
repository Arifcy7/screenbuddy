package com.screen.buddy

import android.app.Application
import com.google.firebase.FirebaseApp
import com.screen.buddy.security.AppCheckInstaller
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class BuddyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        FirebaseApp.initializeApp(this)
        AppCheckInstaller.install()
    }
}
