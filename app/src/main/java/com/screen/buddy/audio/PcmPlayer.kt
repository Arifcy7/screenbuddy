package com.screen.buddy.audio

import javax.inject.Inject
import javax.inject.Singleton

/** AudioTrack streaming playback at 24 kHz, interruptible. Implemented in M5. */
@Singleton
class PcmPlayer @Inject constructor() {
    fun play(pcmChunk: ByteArray) {
        // M5
    }

    fun stopImmediate() {
        // M5
    }
}
