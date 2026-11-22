package com.screen.buddy.live

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerMessage
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import com.screen.buddy.audio.MicRecorder
import com.screen.buddy.audio.PcmPlayer
import com.screen.buddy.data.db.MessageDao
import com.screen.buddy.data.db.MessageEntity
import com.screen.buddy.overlay.DrawingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(PublicPreviewAPI::class)
@Singleton
class LiveSessionManager @Inject constructor(
    private val toolRouter: ToolRouter,
    private val micRecorder: MicRecorder,
    private val pcmPlayer: PcmPlayer,
    private val drawingState: DrawingState,
    private val messageDao: MessageDao,
) {
    companion object {
        const val MODEL_ID = "gemini-3.1-flash-live-preview"
        private const val FALLBACK_MODEL_ID = "gemini-2.5-flash-native-audio-preview-12-2025"
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var session: LiveSession? = null
    private var receiveJob: Job? = null
    private var micJob: Job? = null

    suspend fun startTurn(
        scope: CoroutineScope,
        jpegFrame: ByteArray?,
        secureBlack: Boolean,
        voiceName: String,
        languageHint: String,
        captureOrientation: Int,
        pushToTalk: Boolean,
    ) {
        toolRouter.captureOrientation = captureOrientation
        drawingState.clear()
        _state.value = SessionState.Connecting

        try {
            val live = ensureSession(voiceName, languageHint)
            _state.value = SessionState.Listening

            if (secureBlack) {
                live.send(
                    "The screenshot is black because this app blocks capture (FLAG_SECURE). " +
                        "Tell the user out loud that you cannot see this app.",
                    turnComplete = true,
                )
            } else if (jpegFrame != null) {
                live.sendVideoRealtime(InlineData(jpegFrame, "image/jpeg"))
            }

            receiveJob?.cancel()
            receiveJob = scope.launch {
                live.receive()
                    .catch { e ->
                        Timber.e(e, "Live receive failed")
                        _state.value = SessionState.Error(e.message ?: "Session error")
                        stopTurn(scope)
                    }
                    .collect { message -> handleMessage(scope, live, message) }
            }

            startMic(scope, live)
            if (pushToTalk) {
                live.sendStartActivityRealtime()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start Live session")
            _state.value = SessionState.Error(friendlyError(e))
            stopTurn(scope)
        }
    }

    suspend fun endUserTurn() {
        val live = session ?: return
        try {
            live.sendStopActivityRealtime()
        } catch (e: Exception) {
            Timber.w(e, "sendStopActivityRealtime")
        }
        micRecorder.stop()
        micJob?.cancel()
        micJob = null
        _state.value = SessionState.Thinking
    }

    fun bargeIn() {
        pcmPlayer.stopImmediate()
        if (_state.value is SessionState.Speaking) {
            _state.value = SessionState.Listening
        }
    }

    fun stopTurn(scope: CoroutineScope? = null) {
        micRecorder.stop()
        micJob?.cancel()
        micJob = null
        receiveJob?.cancel()
        receiveJob = null
        pcmPlayer.stopImmediate()
        try {
            session?.stopReceiving()
        } catch (_: Exception) {
        }
        val toClose = session
        session = null
        if (toClose != null && scope != null) {
            scope.launch {
                try {
                    toClose.close()
                } catch (_: Exception) {
                }
            }
        }
        if (_state.value !is SessionState.Error) {
            _state.value = SessionState.Idle
        }
    }

    fun closeWarmSession(scope: CoroutineScope? = null) {
        stopTurn(scope)
        _state.value = SessionState.Idle
    }

    private suspend fun ensureSession(voiceName: String, languageHint: String): LiveSession {
        session?.takeUnless { it.isClosed() }?.let { return it }
        val instruction = buildString {
            append(SystemPrompt.TEXT)
            if (languageHint.isNotBlank()) {
                append(" Prefer language hint: ").append(languageHint).append('.')
            }
        }
        val model = try {
            createModel(MODEL_ID, voiceName, instruction)
        } catch (e: Exception) {
            Timber.w(e, "Primary model failed, trying fallback")
            createModel(FALLBACK_MODEL_ID, voiceName, instruction)
        }
        val connected = model.connect()
        session = connected
        return connected
    }

    private fun createModel(modelId: String, voiceName: String, instruction: String) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = modelId,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(voice = Voice(voiceName))
            },
            tools = listOf(BuddyTools.tool),
            systemInstruction = content { text(instruction) },
        )

    private fun startMic(scope: CoroutineScope, live: LiveSession) {
        micJob?.cancel()
        micJob = scope.launch {
            try {
                micRecorder.start(this).collect { chunk ->
                    if (_state.value is SessionState.Speaking) {
                        bargeIn()
                    }
                    live.sendAudioRealtime(InlineData(chunk, "audio/pcm;rate=16000"))
                }
            } catch (e: SecurityException) {
                Timber.e(e, "Mic permission missing")
                _state.value = SessionState.Error("Microphone permission required")
            } catch (e: Exception) {
                Timber.e(e, "Mic stream failed")
            }
        }
    }

    private suspend fun handleMessage(
        scope: CoroutineScope,
        live: LiveSession,
        message: LiveServerMessage,
    ) {
        when (message) {
            is LiveServerContent -> {
                val parts = message.content?.parts.orEmpty()
                var playedAudio = false
                for (part in parts) {
                    when (part) {
                        is InlineDataPart -> {
                            if (part.mimeType.startsWith("audio/pcm")) {
                                if (!playedAudio) {
                                    _state.value = SessionState.Speaking
                                    playedAudio = true
                                }
                                pcmPlayer.play(part.inlineData)
                            }
                        }
                        is TextPart -> {
                            if (part.text.isNotBlank()) {
                                messageDao.insert(
                                    MessageEntity(
                                        role = "assistant",
                                        text = part.text,
                                        createdAtEpochMs = System.currentTimeMillis(),
                                    ),
                                )
                            }
                        }
                    }
                }
                if (message.interrupted) {
                    pcmPlayer.stopImmediate()
                }
                if (message.turnComplete) {
                    pcmPlayer.stopImmediate()
                    micRecorder.stop()
                    micJob?.cancel()
                    micJob = null
                    _state.value = SessionState.Idle
                    try {
                        live.stopReceiving()
                    } catch (_: Exception) {
                    }
                    try {
                        live.close()
                    } catch (_: Exception) {
                    }
                    session = null
                }
            }
            is LiveServerToolCall -> {
                val responses = message.functionCalls.map { call ->
                    val result = toolRouter.route(call.name, call.args)
                    FunctionResponsePart(call.name, result, call.id)
                }
                live.sendFunctionResponse(responses)
            }
            else -> Timber.d("Live message: %s", message::class.java.simpleName)
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
