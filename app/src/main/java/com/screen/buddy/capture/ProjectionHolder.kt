package com.screen.buddy.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.screen.buddy.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores MediaProjection consent. On Android 14+, create the projection only after the
 * foreground service with type mediaProjection has started.
 */
@Singleton
class ProjectionHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    @Volatile
    private var resultCode: Int = Activity.RESULT_CANCELED

    @Volatile
    private var resultData: Intent? = null

    @Volatile
    var mediaProjection: MediaProjection? = null
        private set

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _needsReconsent = MutableStateFlow(false)
    val needsReconsent: StateFlow<Boolean> = _needsReconsent.asStateFlow()

    fun hasConsent(): Boolean = resultData != null && resultCode == Activity.RESULT_OK

    fun storeConsent(resultCode: Int, data: Intent?) {
        this.resultCode = resultCode
        this.resultData = data?.let { Intent(it) }
        _needsReconsent.value = false
        Timber.d("Projection consent stored: ok=%s", resultCode == Activity.RESULT_OK)
    }

    /**
     * Must be called after OverlayService has entered the foreground with
     * FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
     */
    fun ensureProjection(): MediaProjection? {
        mediaProjection?.let { return it }
        val data = resultData ?: return null
        if (resultCode != Activity.RESULT_OK) return null
        return try {
            val projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(callback, null)
            mediaProjection = projection
            _isActive.value = projection != null
            _needsReconsent.value = projection == null
            projection
        } catch (e: Exception) {
            Timber.e(e, "getMediaProjection failed")
            clear()
            _needsReconsent.value = true
            null
        }
    }

    fun clear() {
        try {
            mediaProjection?.unregisterCallback(callback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping MediaProjection")
        }
        mediaProjection = null
        _isActive.value = false
    }

    fun revokeConsent() {
        clear()
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        _needsReconsent.value = true
    }

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Timber.w("MediaProjection stopped by system/user")
            mediaProjection = null
            _isActive.value = false
            _needsReconsent.value = true
            notifyReEnable()
        }
    }

    private fun notifyReEnable() {
        val channelId = "buddy_status"
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.projection_stopped_title))
            .setContentText(context.getString(R.string.projection_stopped_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1002, notification)
        } catch (e: SecurityException) {
            Timber.w(e, "Cannot post projection-stopped notification")
        }
    }
}
