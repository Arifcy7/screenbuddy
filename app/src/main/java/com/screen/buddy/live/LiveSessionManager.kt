package com.screen.buddy.live

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.ContextWindowCompressionConfig
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerMessage
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SlidingWindow
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.screen.buddy.audio.MicRecorder
import com.screen.buddy.audio.PcmPlayer
import com.screen.buddy.capture.ScreenCaptureManager
import com.screen.buddy.data.db.MessageDao
import com.screen.buddy.data.db.MessageEntity
import com.screen.buddy.overlay.DrawingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Gemini Live conversation.
 *
 * A conversation is a sequence of turns on ONE Live session, so the model remembers what was
 * said and drawn. After Buddy finishes speaking the mic reopens for a follow-up; when the user
 * goes quiet the conversation ends but the socket stays warm for a while, and after that a
 * resumption handle (or, failing that, the saved transcript) restores the context.
 */
@OptIn(PublicPreviewAPI::class)
@Singleton
class LiveSessionManager @Inject constructor(
    private val toolRouter: ToolRouter,
    private val micRecorder: MicRecorder,
    private val pcmPlayer: PcmPlayer,
    private val drawingState: DrawingState,
    private val messageDao: MessageDao,
    private val screenCaptureManager: ScreenCaptureManager,
) {
    companion object {
        const val MODEL_ID = "gemini-3.1-flash-live-preview"
        private const val FALLBACK_MODEL_ID = "gemini-2.5-flash-native-audio-preview-12-2025"

        /** Quiet time after which an open mic gives up and the conversation ends. */
        private const val LISTEN_TIMEOUT_MS = 45_000L
        /** How long to wait for a reply after push-to-talk release. */
        private const val REPLY_TIMEOUT_MS = 20_000L
        /** How long the socket is kept open after a conversation, for instant follow-ups. */
        private const val WARM_SESSION_MS = 90_000L
        /** While listening, refresh the model's view of the screen this often (if it changed). */
        private const val FRAME_INTERVAL_MS = 3_000L
        /** Server keeps resumable state for about two hours; stay well inside that. */
        private const val HANDLE_MAX_AGE_MS = 100 * 60_000L
        private const val HISTORY_SEED_MESSAGES = 12
        private const val HISTORY_SEED_MAX_AGE_MS = 24 * 60 * 60_000L
        private const val MIC_MIME = "audio/pcm;rate=16000"
        /** How long the board stays up after a conversation ends. */
        private const val BOARD_LINGER_MS = 90_000L
        /** Slight stagger when several drawings arrive in one tool burst. */
        private const val DRAW_STAGGER_MS = 90L
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Outlives the service scope so sockets get closed and transcripts saved on shutdown. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val turnMutex = Mutex()

    @Volatile private var session: LiveSession? = null
    @Volatile private var receivingFor: LiveSession? = null
    private var sessionKey: String? = null
    private var sessionModelId: String = MODEL_ID
    private var receiveJob: Job? = null
    private var micJob: Job? = null
    private var watchdogJob: Job? = null
    private var idleCloseJob: Job? = null

    @Volatile private var pushToTalk = false
    /** True from summon until the conversation ends (timeout, tap, error). */
    @Volatile private var conversationActive = false
    /** The model is mid-generation (content seen, no turnComplete yet). */
    @Volatile private var modelTurnActive = false
    /** The user said something the model has not answered yet. */
    @Volatile private var awaitingReply = false
    /** After an interrupt: drop the rest of the current model turn. */
    @Volatile private var suppressPlayback = false
    @Volatile private var goAwayPending = false
    @Volatile private var lastActivityAt = 0L
    @Volatile private var lastFrameHash = 0
    @Volatile private var lastElements: String? = null
    @Volatile private var lastSpeechAt = 0L

    @Volatile private var resumeHandle: String? = null
    private var resumeHandleAt = 0L
    private var resumeHandleModelId: String = MODEL_ID
    @Volatile private var connectionUsedHandle = false
    @Volatile private var connectionGotContent = false

    private val transcriptLock = Any()
    private val userText = StringBuilder()
    private val modelText = StringBuilder()
    private var userTextAt = 0L
    private var modelTextAt = 0L

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    /** Start (or restart) a conversation with a fresh view of the screen. */
    suspend fun startTurn(
        scope: CoroutineScope,
        jpegFrame: ByteArray?,
        screenElements: String?,
        secureBlack: Boolean,
        voiceName: String,
        languageHint: String,
        @Suppress("UNUSED_PARAMETER") captureOrientation: Int,
        pushToTalk: Boolean,
    ) = turnMutex.withLock {
        idleCloseJob?.cancel()
        drawingState.cancelPendingClear()
        // Fresh summon: wipe the board. Follow-ups reuse enterListening and keep it.
        drawingState.clear()
        pcmPlayer.stopImmediate()
        this.pushToTalk = pushToTalk
        suppressPlayback = false
        modelTurnActive = false
        awaitingReply = false
        conversationActive = true

        val key = "$voiceName|$languageHint"
        if (key != sessionKey) {
            // Voice / language are baked into the session; start over with the new settings.
            dropSession()
            resumeHandle = null
            sessionKey = key
        }
        if (session?.isClosed() != false) _state.value = SessionState.Connecting

        try {
            val live = openAndPrime(
                scope, voiceName, languageHint, jpegFrame, screenElements, secureBlack,
            )
            if (conversationActive) {
                enterListening(scope, live, followUp = false)
            } else {
                goIdle() // the user cancelled while we were connecting
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to start Live session")
            _state.value = SessionState.Error(friendlyError(e))
            goIdle(forceClose = true)
        }
    }

    /** Push-to-talk release: the user is done speaking, wait for the answer. */
    suspend fun endUserTurn() {
        val live = session ?: return
        if (_state.value !is SessionState.Listening) return
        _state.value = SessionState.Thinking // also mutes the uplink
        watchdogJob?.cancel()
        stopMic()
        awaitingReply = true
        try {
            // The server decides the turn is over when it hears silence, so give it some:
            // once the mic stops it would otherwise hear nothing at all and keep waiting.
            val silence = ByteArray(3_200) // 100 ms @ 16 kHz PCM16
            repeat(15) {
                live.sendAudioRealtime(InlineData(silence, MIC_MIME))
                delay(100)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Trailing silence failed")
        }
        watchdogJob = backgroundScope.launch {
            delay(REPLY_TIMEOUT_MS)
            if (_state.value is SessionState.Thinking && session === live) {
                Timber.w("No reply after push-to-talk; ending conversation")
                goIdle()
            }
        }
    }

    /** Tap while Buddy is talking: stop the voice and listen again so the user can redirect. */
    fun interrupt(scope: CoroutineScope) {
        val live = session
        if (live == null || !conversationActive) {
            goIdle()
            return
        }
        pushToTalk = false
        // If the model is still generating, discard what is left of that answer.
        suppressPlayback = modelTurnActive
        pcmPlayer.stopImmediate()
        saveTranscripts()
        enterListening(scope, live, followUp = true)
    }

    /** End the conversation. The session stays warm so a follow-up keeps its context. */
    fun stopTurn(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope? = null) {
        goIdle()
        if (_state.value is SessionState.Error) _state.value = SessionState.Idle
    }

    /** Pause / quit: close the socket now. Context can still be resumed later. */
    fun closeWarmSession(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope? = null) {
        goIdle(forceClose = true)
        _state.value = SessionState.Idle
    }

    // ---------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------

    private suspend fun openAndPrime(
        scope: CoroutineScope,
        voiceName: String,
        languageHint: String,
        jpegFrame: ByteArray?,
        screenElements: String?,
        secureBlack: Boolean,
    ): LiveSession {
        // 1. Warm socket from a moment ago: no reconnect, full context.
        session?.takeUnless { it.isClosed() }?.let { warm ->
            try {
                ensureReceiving(scope, warm)
                prime(warm, jpegFrame, screenElements, secureBlack)
                Timber.i("Reusing warm Live session")
                return warm
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Warm session unusable; reconnecting")
            }
        }
        dropSession()

        // 2. Resume server-side state, 3. fresh session seeded with the saved transcript,
        // 4. fallback model.
        val handle = resumeHandle?.takeIf {
            SystemClock.elapsedRealtime() - resumeHandleAt < HANDLE_MAX_AGE_MS
        }
        val attempts = buildList<Pair<String, String?>> {
            if (handle != null) add(resumeHandleModelId to handle)
            add(MODEL_ID to null)
            add(FALLBACK_MODEL_ID to null)
        }
        var lastError: Exception? = null
        for ((modelId, attemptHandle) in attempts) {
            try {
                val instruction = buildInstruction(languageHint, seedHistory = attemptHandle == null)
                val config = attemptHandle?.let { SessionResumptionConfig(it) }
                    ?: SessionResumptionConfig()
                val live = createModel(modelId, voiceName, instruction).connect(config)
                session = live
                sessionModelId = modelId
                connectionUsedHandle = attemptHandle != null
                connectionGotContent = false
                goAwayPending = false
                ensureReceiving(scope, live)
                prime(live, jpegFrame, screenElements, secureBlack)
                Timber.i("Live session connected model=%s resumed=%b", modelId, attemptHandle != null)
                return live
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Connect failed model=%s resumed=%b", modelId, attemptHandle != null)
                lastError = e
                dropSession()
                if (attemptHandle != null) resumeHandle = null
            }
        }
        throw lastError ?: IllegalStateException("Session failed")
    }

    /** Show the model the current screen at the start of a conversation. */
    private suspend fun prime(
        live: LiveSession,
        jpegFrame: ByteArray?,
        screenElements: String?,
        secureBlack: Boolean,
    ) {
        if (secureBlack) {
            awaitingReply = true
            live.send(
                "The screenshot is black because this app blocks capture (FLAG_SECURE). " +
                    "Tell the user out loud that you cannot see this app.",
                turnComplete = true,
            )
        } else if (jpegFrame != null) {
            live.sendVideoRealtime(InlineData(jpegFrame, "image/jpeg"))
            lastFrameHash = jpegFrame.contentHashCode()
            lastElements = screenElements
            // Nudge the Live model to actually invoke drawing tools (native audio often skips them).
            live.send(
                "This is the user's screen right now. When you refer to anything on it, call " +
                    "point_at or highlight_box with 0-1000 coordinates as you say it." +
                    elementsNote(screenElements),
                turnComplete = false,
            )
        }
    }

    private fun elementsNote(elements: String?): String =
        if (elements.isNullOrBlank()) {
            ""
        } else {
            "\nExact positions of labelled elements on this screen, as " +
                "\"label\" [xmin,ymin,xmax,ymax] in the same 0-1000 space. Prefer these boxes " +
                "over estimating from the image:\n$elements"
        }

    private suspend fun buildInstruction(languageHint: String, seedHistory: Boolean): String =
        buildString {
            append(SystemPrompt.TEXT)
            if (languageHint.isNotBlank()) {
                append(" Prefer language hint: ").append(languageHint).append('.')
            }
            if (seedHistory) append(historySeed())
        }

    /** Gist of the last conversation, for sessions that cannot be resumed server-side. */
    private suspend fun historySeed(): String {
        val cutoff = System.currentTimeMillis() - HISTORY_SEED_MAX_AGE_MS
        val recent = try {
            messageDao.recent(HISTORY_SEED_MESSAGES)
                .filter { it.createdAtEpochMs >= cutoff }
                .reversed()
        } catch (e: Exception) {
            Timber.w(e, "History seed failed")
            emptyList()
        }
        if (recent.isEmpty()) return ""
        return buildString {
            append(
                "\n\nEarlier conversation with this user (oldest first). Do not repeat it or " +
                    "mention it unprompted; use it to understand follow-up questions:\n",
            )
            for (m in recent) {
                append(if (m.role == "user") "User: " else "You: ")
                append(m.text.trim().take(400))
                append('\n')
            }
        }
    }

    private fun createModel(modelId: String, voiceName: String, instruction: String) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = modelId,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(voice = Voice(voiceName))
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
                // Without this an audio+image session hits its context limit within minutes.
                contextWindowCompression =
                    ContextWindowCompressionConfig(slidingWindow = SlidingWindow())
            },
            tools = listOf(BuddyTools.tool),
            systemInstruction = content { text(instruction) },
        )

    private fun ensureReceiving(scope: CoroutineScope, live: LiveSession) {
        if (receivingFor === live && receiveJob?.isActive == true) return
        receivingFor = live
        // Always off the main thread; the SDK's receive loop polls.
        receiveJob = scope.launch(Dispatchers.IO) {
            val failure = try {
                live.receive().collect { message -> handleMessage(scope, live, message) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            onReceiveEnded(live, failure)
        }
    }

    private fun stopReceiving(live: LiveSession?) {
        receivingFor = null
        try {
            // The SDK loop only exits through this flag, not through coroutine cancellation.
            live?.stopReceiving()
        } catch (_: Exception) {
        }
        receiveJob?.cancel()
        receiveJob = null
    }

    private fun onReceiveEnded(live: LiveSession, failure: Exception?) {
        if (receivingFor !== live) return // we stopped it on purpose
        Timber.w(failure, "Live session ended unexpectedly")
        if (connectionUsedHandle && !connectionGotContent) {
            // Most likely a stale handle; next attempt starts fresh from the transcript.
            resumeHandle = null
        }
        val wasActive = conversationActive
        if (wasActive) {
            _state.value = SessionState.Error(
                failure?.let { friendlyError(it) } ?: "Connection lost — tap to try again",
            )
        }
        goIdle(forceClose = true)
    }

    private fun dropSession() {
        val old = session
        stopReceiving(old)
        session = null
        if (old != null) {
            backgroundScope.launch {
                try {
                    old.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Server announced it will drop this connection: move to a new one, keeping the context. */
    private suspend fun refreshConnection(live: LiveSession) {
        goAwayPending = false
        val handle = resumeHandle ?: return
        try {
            live.resumeSession(SessionResumptionConfig(handle))
            Timber.i("Live connection refreshed after GoAway")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "resumeSession failed")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Turn state machine
    // ---------------------------------------------------------------------------------------

    private fun enterListening(scope: CoroutineScope, live: LiveSession, followUp: Boolean) {
        if (!conversationActive || session !== live) return
        drawingState.cancelPendingClear()
        lastActivityAt = SystemClock.elapsedRealtime()
        _state.value = SessionState.Listening
        startMic(scope, live)
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            // On a follow-up the screen may have changed (and now shows our drawings).
            var lastFrameAt = if (followUp) 0L else SystemClock.elapsedRealtime()
            while (isActive && _state.value is SessionState.Listening && session === live) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastActivityAt > LISTEN_TIMEOUT_MS) {
                    Timber.d("Nobody spoke; ending conversation")
                    goIdle()
                    break
                }
                if (now - lastFrameAt >= FRAME_INTERVAL_MS) {
                    lastFrameAt = now
                    sendFreshFrame(live)
                }
                delay(250)
            }
        }
    }

    private suspend fun sendFreshFrame(live: LiveSession) {
        try {
            val capture = screenCaptureManager.captureOnce()
            capture.bitmap?.recycle()
            val jpeg = capture.jpeg ?: return
            val hash = jpeg.contentHashCode()
            if (hash == lastFrameHash) return
            if (_state.value !is SessionState.Listening || session !== live) return
            live.sendVideoRealtime(InlineData(jpeg, "image/jpeg"))
            lastFrameHash = hash
            // Keep the exact element positions in step with the picture, but never inject text
            // while the user is mid-sentence.
            val elements = capture.elements
            val userQuiet = SystemClock.elapsedRealtime() - lastSpeechAt > 2_000
            if (!elements.isNullOrBlank() && elements != lastElements && userQuiet) {
                live.send("The screen changed." + elementsNote(elements), turnComplete = false)
                lastElements = elements
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Frame refresh failed")
        }
    }

    private fun startMic(scope: CoroutineScope, live: LiveSession) {
        if (micJob?.isActive == true) return
        micJob = scope.launch(Dispatchers.IO) {
            try {
                micRecorder.start(this).collect { chunk ->
                    // Uplink only while listening: speaker bleed would otherwise interrupt Buddy.
                    if (_state.value is SessionState.Listening && session === live) {
                        live.sendAudioRealtime(InlineData(chunk, MIC_MIME))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Timber.e(e, "Mic permission missing")
                _state.value = SessionState.Error("Microphone permission required")
                goIdle()
            } catch (e: Exception) {
                Timber.e(e, "Mic stream failed")
            }
        }
    }

    private fun stopMic() {
        micRecorder.stop()
        micJob?.cancel()
        micJob = null
    }

    /**
     * End the conversation. Keeps the socket open for a while unless something is still in
     * flight on it (a half-finished answer would otherwise leak into the next conversation).
     */
    private fun goIdle(forceClose: Boolean = false) {
        conversationActive = false
        watchdogJob?.cancel()
        watchdogJob = null
        idleCloseJob?.cancel()
        stopMic()
        pcmPlayer.stopImmediate()
        saveTranscripts()
        // Leave the board up long enough to read, then tidy it away.
        drawingState.clearAfter(BOARD_LINGER_MS)

        val live = session
        val mustClose = forceClose || modelTurnActive || awaitingReply || goAwayPending
        if (live != null) {
            if (mustClose) {
                dropSession()
            } else {
                stopReceiving(live)
                idleCloseJob = backgroundScope.launch {
                    delay(WARM_SESSION_MS)
                    turnMutex.withLock {
                        if (session === live && !conversationActive) {
                            Timber.d("Closing idle Live session")
                            dropSession()
                        }
                    }
                }
            }
        }
        modelTurnActive = false
        awaitingReply = false
        suppressPlayback = false
        if (_state.value !is SessionState.Error) {
            _state.value = SessionState.Idle
        }
    }

    /** Runs once Buddy's answer has finished sounding. */
    private fun afterModelTurn(scope: CoroutineScope, live: LiveSession) {
        val current = _state.value
        if (current !is SessionState.Speaking && current !is SessionState.Thinking) return
        if (session !== live) return
        if (conversationActive) {
            // The mic reopens by itself so the user can just answer or ask the next thing,
            // whichever way the conversation was started.
            pushToTalk = false
            enterListening(scope, live, followUp = true)
        } else {
            goIdle()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Server messages
    // ---------------------------------------------------------------------------------------

    private suspend fun handleMessage(
        scope: CoroutineScope,
        live: LiveSession,
        message: LiveServerMessage,
    ) {
        when (message) {
            is LiveServerContent -> handleContent(scope, live, message)
            is LiveServerToolCall -> handleToolCall(live, message)
            is LiveSessionResumptionUpdate -> {
                val handle = message.newHandle
                if (message.resumable == true && !handle.isNullOrBlank()) {
                    resumeHandle = handle
                    resumeHandleAt = SystemClock.elapsedRealtime()
                    resumeHandleModelId = sessionModelId
                }
            }
            is LiveServerGoAway -> {
                Timber.i("Live GoAway timeLeft=%s", message.timeLeft)
                goAwayPending = true
                if (!modelTurnActive) refreshConnection(live)
            }
            else -> Timber.d("Live message: %s", message::class.java.simpleName)
        }
    }

    private suspend fun handleContent(
        scope: CoroutineScope,
        live: LiveSession,
        message: LiveServerContent,
    ) {
        message.inputTranscription?.text?.takeIf { it.isNotEmpty() }?.let { heard ->
            lastActivityAt = SystemClock.elapsedRealtime()
            lastSpeechAt = lastActivityAt
            awaitingReply = true
            synchronized(transcriptLock) {
                if (userText.isEmpty()) userTextAt = System.currentTimeMillis()
                userText.append(heard)
            }
        }
        val parts = message.content?.parts.orEmpty()
        val said = message.outputTranscription?.text.orEmpty()
        // Only real content starts a model turn: transcript fragments can trail in after
        // turnComplete, and treating those as a new turn would leave it open forever.
        if (parts.isNotEmpty()) {
            modelTurnActive = true
            connectionGotContent = true
        }
        if (said.isNotEmpty() && !suppressPlayback) {
            synchronized(transcriptLock) {
                if (modelText.isEmpty()) modelTextAt = System.currentTimeMillis()
                modelText.append(said)
            }
        }
        for (part in parts) {
            // Text parts in audio mode are the model's reasoning, not speech; the spoken words
            // arrive through outputTranscription.
            if (part !is InlineDataPart || !part.mimeType.startsWith("audio/pcm")) continue
            if (suppressPlayback || !conversationActive) continue
            if (_state.value !is SessionState.Speaking) {
                watchdogJob?.cancel()
                _state.value = SessionState.Speaking
            }
            pcmPlayer.enqueue(part.inlineData)
        }

        if (message.interrupted) {
            // The user talked over Buddy: drop what was queued and keep listening.
            suppressPlayback = true
            pcmPlayer.stopImmediate()
            if (_state.value is SessionState.Speaking) {
                enterListening(scope, live, followUp = true)
            }
        }

        if (message.turnComplete) {
            modelTurnActive = false
            awaitingReply = false
            suppressPlayback = false
            saveTranscripts()
            if (goAwayPending) refreshConnection(live)
            when (_state.value) {
                is SessionState.Speaking ->
                    // Wait for the audio that is still queued before reopening the mic.
                    pcmPlayer.enqueueEndOfTurn {
                        backgroundScope.launch { afterModelTurn(scope, live) }
                    }
                is SessionState.Thinking -> afterModelTurn(scope, live)
                else -> Unit
            }
        }
    }

    private suspend fun handleToolCall(live: LiveSession, message: LiveServerToolCall) {
        modelTurnActive = true
        connectionGotContent = true
        val plans = message.functionCalls.map { call -> call to toolRouter.plan(call.name, call.args) }
        // Answer straight away so the model keeps talking without a gap.
        live.sendFunctionResponse(
            plans.map { (call, plan) -> FunctionResponsePart(call.name, plan.result, call.id) },
        )
        if (suppressPlayback || !conversationActive) return
        // Gemini 3.1 tool calls are synchronous: the model pauses to call them, then continues.
        // Drawing the moment they arrive means marks appear as that step is explained.
        var delayMs = 0L
        for ((_, plan) in plans) {
            val action = plan.apply ?: continue
            val wait = delayMs
            mainHandler.postDelayed(action, wait)
            delayMs += DRAW_STAGGER_MS
            Timber.d("Drawing scheduled in %dms", wait)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Transcript
    // ---------------------------------------------------------------------------------------

    private fun saveTranscripts() {
        val user: String
        val model: String
        val modelAt: Long
        val userAt: Long
        synchronized(transcriptLock) {
            user = userText.toString().trim()
            model = modelText.toString().trim()
            modelAt = modelTextAt
            // Transcripts arrive out of order relative to audio; keep question before answer.
            userAt = if (user.isNotEmpty() && model.isNotEmpty() && userTextAt >= modelAt) {
                modelAt - 1
            } else {
                userTextAt
            }
            userText.setLength(0)
            modelText.setLength(0)
        }
        if (user.isEmpty() && model.isEmpty()) return
        backgroundScope.launch {
            try {
                if (user.isNotEmpty()) {
                    messageDao.insert(MessageEntity(role = "user", text = user, createdAtEpochMs = userAt))
                }
                if (model.isNotEmpty()) {
                    messageDao.insert(
                        MessageEntity(role = "assistant", text = model, createdAtEpochMs = modelAt),
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Saving transcript failed")
            }
        }
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Unable to resolve host", true) ||
                msg.contains("network", true) -> "No network"
            msg.contains("quota", true) || msg.contains("429") -> "Quota hit — try later"
            msg.contains("PERMISSION", true) -> "Permission or App Check issue"
            else -> msg.ifBlank { "Session failed" }
        }
    }
}
