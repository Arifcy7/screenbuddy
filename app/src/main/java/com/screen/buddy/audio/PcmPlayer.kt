package com.screen.buddy.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ordered playback timeline for one model turn.
 *
 * Audio chunks and "markers" (drawings, end of turn) are queued in the order the model produced
 * them and consumed by a single playback thread. That keeps the network collector free (it never
 * blocks on AudioTrack) and makes a drawing appear when its place in the speech is reached,
 * rather than whenever the packet happened to arrive.
 */
@Singleton
class PcmPlayer @Inject constructor() {
    companion object {
        const val SAMPLE_RATE = 24_000
        private const val BYTES_PER_FRAME = 2 // PCM16 mono
        private const val SLICE_BYTES = SAMPLE_RATE * BYTES_PER_FRAME / 20 // 50 ms
        private const val BUFFER_BYTES = SAMPLE_RATE * BYTES_PER_FRAME / 4 // 250 ms
    }

    private sealed class Item(val generation: Int) {
        class Audio(generation: Int, val pcm: ByteArray) : Item(generation)
        class Marker(generation: Int, val waitForDrain: Boolean, val action: () -> Unit) :
            Item(generation)
    }

    private val queue = LinkedBlockingQueue<Item>()
    private val trackLock = Any()
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    /** Bumped on every interrupt; anything queued under an older generation is dropped. */
    @Volatile private var generation = 0
    @Volatile private var started = false
    private val framesWritten = AtomicLong(0)

    private class Timed(val generation: Int, val frame: Long, val action: () -> Unit)

    private val timed = CopyOnWriteArrayList<Timed>()
    private val enqueuedFrames = AtomicLong(0)

    /**
     * Timeline position, in frames, of the end of everything queued so far. Positions restart
     * from zero after [stopImmediate].
     */
    val position: Long get() = enqueuedFrames.get()

    /** Queue model audio (24 kHz mono PCM16). Never blocks. */
    fun enqueue(pcmChunk: ByteArray) {
        if (pcmChunk.isEmpty()) return
        ensureWorker()
        queue.put(Item.Audio(generation, pcmChunk))
        enqueuedFrames.addAndGet((pcmChunk.size / BYTES_PER_FRAME).toLong())
    }

    /**
     * Runs [action] when the listener actually hears timeline position [frame] (or right away
     * if playback is already past it). This is what lets a drawing land on the word it belongs to.
     */
    fun runAtFrame(frame: Long, action: () -> Unit) {
        ensureWorker()
        timed.add(Timed(generation, frame, action))
        // Wake the playback thread so it starts watching the clock for this action.
        queue.put(Item.Marker(generation, waitForDrain = false, action = {}))
    }

    /** @param idle true when the playback thread has no audio in hand. */
    private fun fireDue(idle: Boolean) {
        if (timed.isEmpty()) return
        val head = synchronized(trackLock) {
            try {
                track?.takeIf { started }?.let { it.playbackHeadPosition.toLong() and 0xFFFFFFFFL } ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
        // Nothing left to play: whatever is still waiting will never be reached, so show it now.
        val drained = idle && queue.isEmpty() && head >= framesWritten.get() - SAMPLE_RATE / 10
        for (t in timed) {
            if (t.generation != generation) {
                timed.remove(t)
            } else if (drained || t.frame <= head) {
                timed.remove(t)
                try {
                    t.action()
                } catch (e: Exception) {
                    Timber.w(e, "Timed action failed")
                }
            }
        }
    }

    /** Runs [action] once everything queued before it has actually finished sounding. */
    fun enqueueEndOfTurn(action: () -> Unit) {
        ensureWorker()
        queue.put(Item.Marker(generation, waitForDrain = true, action = action))
    }

    /** Interrupt / cancel: drop queued audio and pending markers immediately. */
    fun stopImmediate() {
        generation++
        queue.clear()
        timed.clear()
        enqueuedFrames.set(0)
        synchronized(trackLock) {
            started = false
            framesWritten.set(0)
            try {
                track?.pause()
                track?.flush()
            } catch (e: Exception) {
                Timber.w(e, "PcmPlayer stopImmediate")
                releaseTrackLocked()
            }
        }
    }

    fun release() {
        generation++
        queue.clear()
        timed.clear()
        enqueuedFrames.set(0)
        synchronized(trackLock) {
            started = false
            framesWritten.set(0)
            releaseTrackLocked()
        }
    }

    @Synchronized
    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        worker = Thread({ runLoop() }, "buddy-pcm-player").apply {
            isDaemon = true
            start()
        }
    }

    private fun runLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        while (true) {
            val item = try {
                // Short poll so timed actions stay accurate while audio is only being played out.
                queue.poll(if (timed.isEmpty()) 250L else 20L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            fireDue(idle = item == null)
            if (item == null || item.generation != generation) continue
            try {
                when (item) {
                    is Item.Audio -> write(item)
                    is Item.Marker -> {
                        if (item.waitForDrain) awaitDrain(item.generation)
                        if (item.generation == generation) item.action()
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "PcmPlayer item failed")
            }
        }
    }

    private fun write(item: Item.Audio) {
        val t = synchronized(trackLock) {
            if (item.generation != generation) return
            val current = track ?: createTrack().also { track = it }
            if (!started) {
                current.play()
                started = true
            }
            current
        }
        val pcm = item.pcm
        var offset = 0
        while (offset < pcm.size && item.generation == generation) {
            val len = minOf(SLICE_BYTES, pcm.size - offset)
            val written = t.write(pcm, offset, len, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Timber.w("AudioTrack.write error %d", written)
                synchronized(trackLock) {
                    if (track === t) {
                        started = false
                        releaseTrackLocked()
                    }
                }
                return
            }
            if (written == 0) {
                // Paused underneath us; the generation check ends the loop.
                Thread.sleep(5)
                continue
            }
            offset += written
            framesWritten.addAndGet((written / BYTES_PER_FRAME).toLong())
            fireDue(idle = false)
        }
    }

    /** Wait until the frames handed to AudioTrack have been played out (bounded). */
    private fun awaitDrain(itemGeneration: Int) {
        val deadline = SystemClock.elapsedRealtime() + 1_500
        var lastHead = -1L
        var stalledSince = SystemClock.elapsedRealtime()
        while (itemGeneration == generation && SystemClock.elapsedRealtime() < deadline) {
            val target = framesWritten.get()
            if (target == 0L) return
            val head = synchronized(trackLock) {
                val t = track ?: return
                try {
                    t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                } catch (_: Exception) {
                    return
                }
            }
            if (head >= target) return
            val now = SystemClock.elapsedRealtime()
            if (head != lastHead) {
                lastHead = head
                stalledSince = now
            } else if (now - stalledSince > 300) {
                return // not advancing (e.g. start threshold never reached)
            }
            Thread.sleep(20)
        }
    }

    private fun createTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Small device buffer: the queue above absorbs network jitter, and a short buffer keeps
        // drawings and the end-of-turn signal close to what is actually audible.
        val buf = maxOf(minBuf, BUFFER_BYTES)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
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
            .setBufferSizeInBytes(buf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Default threshold is the whole buffer, which swallows very short replies.
            try {
                t.setStartThresholdInFrames(SAMPLE_RATE / 25)
            } catch (e: Exception) {
                Timber.w(e, "setStartThresholdInFrames")
            }
        }
        return t
    }

    private fun releaseTrackLocked() {
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }
}
