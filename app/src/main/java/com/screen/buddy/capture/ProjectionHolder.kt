package com.screen.buddy.capture

import android.media.projection.MediaProjection
import javax.inject.Inject
import javax.inject.Singleton

/** Holds MediaProjection consent result and Callback.onStop. Implemented in M1. */
@Singleton
class ProjectionHolder @Inject constructor() {
    @Volatile
    var mediaProjection: MediaProjection? = null
        private set

    fun setProjection(projection: MediaProjection?) {
        mediaProjection = projection
    }

    fun clear() {
        mediaProjection = null
    }
}
