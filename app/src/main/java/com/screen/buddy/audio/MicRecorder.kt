package com.screen.buddy.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MicRecorder @Inject constructor() {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val running = AtomicBoolean(false)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(scope: CoroutineScope): Flow<ByteArray> = callbackFlow {
        if (!running.compareAndSet(false, true)) {
            close()
            return@callbackFlow
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = (minBuf * 2).coerceAtLeast(SAMPLE_RATE / 5 * 2) // ~100ms
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize,
        )
        audioRecord = recorder

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true }
        }

        recorder.startRecording()
        Timber.d("MicRecorder started")

        recordJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(bufferSize)
            while (isActive && running.get()) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    trySend(buf.copyOf(read))
                }
            }
        }

        awaitClose { stopInternal() }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        if (!running.getAndSet(false)) return
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        aec?.release()
        aec = null
        ns?.release()
        ns = null
        Timber.d("MicRecorder stopped")
    }
}
