package com.screen.buddy.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PcmPlayer @Inject constructor() {
    companion object {
        const val SAMPLE_RATE = 24_000
    }

    private val playing = AtomicBoolean(false)
    private var track: AudioTrack? = null

    @Synchronized
    fun play(pcmChunk: ByteArray) {
        if (pcmChunk.isEmpty()) return
        ensureTrack()
        val t = track ?: return
        if (!playing.get()) {
            t.play()
            playing.set(true)
        }
        var offset = 0
        while (offset < pcmChunk.size && playing.get()) {
            val written = t.write(pcmChunk, offset, pcmChunk.size - offset)
            if (written < 0) break
            offset += written
        }
    }

    /** Barge-in: stop playback and clear queued audio immediately. */
    @Synchronized
    fun stopImmediate() {
        playing.set(false)
        try {
            track?.pause()
            track?.flush()
            track?.stop()
        } catch (e: Exception) {
            Timber.w(e, "PcmPlayer stop")
        }
        releaseTrack()
    }

    @Synchronized
    private fun ensureTrack() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun releaseTrack() {
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }
}
