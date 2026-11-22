package com.screen.buddy.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.screen.buddy.MainActivity
import com.screen.buddy.R
import com.screen.buddy.capture.ScreenAccess
import com.screen.buddy.capture.ScreenCaptureManager
import com.screen.buddy.data.SettingsRepository
import com.screen.buddy.live.LiveSessionManager
import com.screen.buddy.live.SessionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var screenAccess: ScreenAccess
    @Inject lateinit var screenCaptureManager: ScreenCaptureManager
    @Inject lateinit var liveSessionManager: LiveSessionManager
    @Inject lateinit var drawingState: DrawingState
    @Inject lateinit var settingsRepository: SettingsRepository

    private val job = SupervisorJob()
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate + job)
    private val ioScope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var lifecycleOwner: OverlayLifecycleOwner
    private var bubbleWindow: BubbleWindow? = null
    private var drawingWindow: DrawingWindow? = null
    private var paused = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
        lifecycleOwner = OverlayLifecycleOwner().also { it.onCreate() }
        mainScope.launch {
            // "Drawing duration" in Settings is how long pointer marks stay.
            settingsRepository.drawingDurationSec.collect { drawingState.pointerTtlMs = it * 1000L }
        }
        startAsForeground()
        screenCaptureManager.refreshGeometry()
        showWindows()
        observeGestures()
        observePause()
        observeDrawings()
        Timber.d("OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> mainScope.launch {
                paused = true
                settingsRepository.setPaused(true)
                liveSessionManager.closeWarmSession(ioScope)
                Toast.makeText(this@OverlayService, R.string.paused, Toast.LENGTH_SHORT).show()
            }
            ACTION_RESUME -> mainScope.launch {
                paused = false
                settingsRepository.setPaused(false)
            }
            ACTION_QUIT -> {
                liveSessionManager.closeWarmSession(ioScope)
                stopSelf()
            }
            ACTION_DEBUG_DRAW -> {
                drawingState.clear()
                drawingState.add(DrawOp.Ring(500, 500, "center"))
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        // minSdk 30: the microphone type is always available.
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun showWindows() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // Board first, bubble second: the bubble must stay visible on top of opaque panels.
        drawingWindow = DrawingWindow(
            applicationContext,
            wm,
            lifecycleOwner,
            drawingState,
            screenCaptureManager.geometry,
        ).also { it.show() }
        bubbleWindow = BubbleWindow(
            applicationContext,
            wm,
            lifecycleOwner,
            liveSessionManager.state,
        ).also { it.show() }
    }

    private fun observeGestures() {
        val bubble = bubbleWindow ?: return
        mainScope.launch {
            bubble.gestures.collect { gesture ->
                if (paused) {
                    Toast.makeText(this@OverlayService, R.string.paused, Toast.LENGTH_SHORT).show()
                    return@collect
                }
                when (gesture) {
                    // Continuous conversation: Buddy listens after every answer. Hold and tap
                    // both just start / interrupt / stop — never force push-to-talk.
                    BubbleGesture.Tap, BubbleGesture.HoldStart -> onBubbleTap()
                    BubbleGesture.HoldEnd -> Unit
                }
            }
        }
    }

    /** One tap does the obvious thing for the current state, in either input mode. */
    private fun onBubbleTap() {
        when (liveSessionManager.state.value) {
            // Start a conversation.
            is SessionState.Idle, is SessionState.Error -> summon(pushToTalk = false)
            // Buddy is talking (or about to): cut in and listen again. The board stays.
            is SessionState.Speaking, is SessionState.Thinking ->
                liveSessionManager.interrupt(ioScope)
            // It is listening or connecting: end the conversation and wipe the board.
            else -> {
                liveSessionManager.stopTurn(ioScope)
                drawingState.clear()
            }
        }
    }

    private fun observePause() {
        mainScope.launch {
            settingsRepository.paused.collect { paused = it }
        }
    }

    private fun observeDrawings() {
        mainScope.launch {
            drawingState.ops.collect { ops ->
                if (ops.isNotEmpty()) {
                    Timber.d("Drawing overlay ops=%d — bringing window to front", ops.size)
                    drawingWindow?.bringToFront()
                }
            }
        }
    }

    private fun summon(pushToTalk: Boolean) {
        ioScope.launch {
            try {
                if (!screenAccess.isActive.value) {
                    showToast(getString(R.string.screen_access_off))
                    return@launch
                }
                val voice = settingsRepository.voice.first()
                val lang = settingsRepository.languageHint.first()
                val capture = screenCaptureManager.captureOnce()
                capture.bitmap?.recycle()
                if (capture.jpeg == null && !capture.isSecureBlack) {
                    showToast(getString(R.string.screen_capture_failed))
                    return@launch
                }
                liveSessionManager.startTurn(
                    scope = ioScope,
                    jpegFrame = capture.jpeg,
                    screenElements = capture.elements,
                    secureBlack = capture.isSecureBlack,
                    voiceName = voice,
                    languageHint = lang,
                    captureOrientation = capture.orientation,
                    pushToTalk = pushToTalk,
                )
            } catch (e: Exception) {
                Timber.e(e, "Summon failed")
                showToast(e.message ?: "Summon failed")
                liveSessionManager.stopTurn(ioScope)
            }
        }
    }

    private fun showToast(msg: String) {
        mainScope.launch {
            Toast.makeText(this@OverlayService, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val quit = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_pause), pause)
            .addAction(0, getString(R.string.action_quit), quit)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Buddy", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel("buddy_status", "Buddy status", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    override fun onDestroy() {
        liveSessionManager.closeWarmSession(ioScope)
        bubbleWindow?.hide()
        drawingWindow?.hide()
        lifecycleOwner.onDestroy()
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "buddy_overlay"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "com.screen.buddy.PAUSE"
        const val ACTION_RESUME = "com.screen.buddy.RESUME"
        const val ACTION_QUIT = "com.screen.buddy.QUIT"
        const val ACTION_DEBUG_DRAW = "com.screen.buddy.DEBUG_DRAW"

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
