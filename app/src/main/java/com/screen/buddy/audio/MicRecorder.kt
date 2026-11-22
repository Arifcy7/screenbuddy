package com.screen.buddy.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** AudioRecord VOICE_COMMUNICATION, 16 kHz PCM16 mono. Implemented in M5. */
@Singleton
class MicRecorder @Inject constructor() {
    fun start(): Flow<ByteArray> {
        // M5
        return emptyFlow()
    }

    fun stop() {
        // M5
    }
}
