package com.screen.buddy.overlay

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint

/** Foreground service owning bubble/drawing windows. Implemented in M2. */
@AndroidEntryPoint
class OverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
