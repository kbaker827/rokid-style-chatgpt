package com.rokid.style.chatgpt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Foreground service that ties together:
 *  [SpeechManager] → [OpenAiClient] streaming → [TtsManager]
 *
 * Lives as long as the app is active (or in background on Rokid YodaOS).
 * Bound by [MainActivity] via [LocalBinder].
 */
class VoiceAssistantService : Service() {

    companion object {
        private const val TAG              = "VoiceAssistantSvc"
        private const val NOTIF_CHANNEL_ID = "rokid_chatgpt_channel"
        private const val NOTIF_ID         = 1001
        const val ACTION_START             = "com.rokid.style.chatgpt.START"
        const val ACTION_STOP              = "com.rokid.style.chatgpt.STOP"
    }

    // ── Binder ───────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    // ── Components ───────────────────────────────────────────────────────────

    private lateinit var speechManager : SpeechManager
    private lateinit var ttsManager    : TtsManager
    private lateinit var openAiClient  : OpenAiClient
    private lateinit var history       : ConversationHistory

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var streamJob: Job? = null

    // ── State callbacks (observed by MainActivity / UI) ───────────────────

    var onStateChanged   : ((AssistantState) -> Unit)? = null
    var onPartialSpeech  : ((String) -> Unit)? = null
    var onResponseChunk  : ((String) -> Unit)? = null
    var onError          : ((String) -> Unit)? = null

    enum class AssistantState {
        IDLE, LISTENING, THINKING, SPEAKING
    }

    private var state = AssistantState.IDLE
        set(value) {
            field = value
            onStateChanged?.invoke(value)
            updateNotification(value)
        }

    // Accumulates the full streaming response for history
    private val responseBuffer = StringBuilder()

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        speechManager = SpeechManager(this)
        ttsManager    = TtsManager(this)
        openAiClient  = OpenAiClient()
        history       = ConversationHistory(Prefs.getMaxHistory(this))

        wireSpeechCallbacks()
        wireTtsCallbacks()

        ttsManager.init()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP  -> stopListening()
            else         -> startForeground(NOTIF_ID, buildNotification(AssistantState.IDLE))
        }
        startForeground(NOTIF_ID, buildNotification(state))
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        speechManager.stop()
        ttsManager.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        if (!Prefs.getVoiceEnabled(this)) return
        history.updateMaxPairs(Prefs.getMaxHistory(this))
        speechManager.start()
        state = AssistantState.LISTENING
    }

    fun stopListening() {
        speechManager.stop()
        streamJob?.cancel()
        ttsManager.stop()
        state = AssistantState.IDLE
    }

    fun clearHistory() = history.clear()

    fun getCurrentState() = state

    // ── Speech callbacks ──────────────────────────────────────────────────────

    private fun wireSpeechCallbacks() {
        speechManager.onPartialResult = { partial ->
            onPartialSpeech?.invoke(partial)
        }

        speechManager.onSilence = { transcript ->
            if (transcript.isNotBlank() && Prefs.getAutoSendVoice(this)) {
                sendToGpt(transcript)
            }
        }

        speechManager.onListeningStateChanged = { listening ->
            if (listening && state != AssistantState.THINKING && state != AssistantState.SPEAKING) {
                state = AssistantState.LISTENING
            }
        }

        speechManager.onError = { msg ->
            Log.e(TAG, "Speech error: $msg")
            // Non-fatal — the SpeechManager auto-restarts
        }
    }

    // ── TTS callbacks ─────────────────────────────────────────────────────────

    private fun wireTtsCallbacks() {
        ttsManager.onDone = {
            Log.d(TAG, "TTS done, resuming mic")
            // Add accumulated response to history
            val fullResponse = responseBuffer.toString().trim()
            if (fullResponse.isNotEmpty()) {
                history.addAssistant(fullResponse)
            }
            responseBuffer.clear()

            // Re-arm the microphone
            speechManager.resume()
            state = AssistantState.LISTENING
        }
    }

    // ── GPT streaming ─────────────────────────────────────────────────────────

    private fun sendToGpt(userText: String) {
        val apiKey = Prefs.getApiKey(this)
        if (apiKey.isBlank()) {
            onError?.invoke("API key not set. Open Settings to configure.")
            return
        }

        Log.d(TAG, "Sending to GPT: $userText")

        history.addUser(userText)

        val messages   = history.buildApiMessages(Prefs.getSystemPrompt(this))
        val model      = Prefs.getModelId(this)
        val maxTokens  = Prefs.getMaxTokens(this)

        // Pause mic while we're thinking / speaking
        speechManager.pause()
        state = AssistantState.THINKING
        responseBuffer.clear()

        streamJob = serviceScope.launch {
            openAiClient
                .streamCompletion(apiKey, model, messages, maxTokens)
                .catch { e ->
                    Log.e(TAG, "GPT error", e)
                    val msg = when (e) {
                        is OpenAiException -> "OpenAI error ${e.code}: ${e.message}"
                        else               -> "Error: ${e.message}"
                    }
                    onError?.invoke(msg)
                    // Speak the error so the user knows something went wrong
                    ttsManager.speak("Sorry, I encountered an error. $msg")
                    state = AssistantState.SPEAKING
                }
                .onCompletion { cause ->
                    if (cause == null) {
                        Log.d(TAG, "Stream complete")
                        // TTS will call onDone when it finishes speaking
                    }
                }
                .collect { chunk ->
                    responseBuffer.append(chunk)
                    onResponseChunk?.invoke(chunk)

                    // Switch state to SPEAKING on first chunk
                    if (state == AssistantState.THINKING) {
                        state = AssistantState.SPEAKING
                    }

                    // Feed each sentence-ending chunk immediately to TTS for
                    // low-latency playback. We speak full sentences where possible.
                    speakChunkIfSentenceComplete(chunk)
                }
        }
    }

    /**
     * Buffer and speak sentence-complete fragments for smooth TTS.
     * Accumulates text until a sentence-ending punctuation mark is found,
     * then hands that sentence to TTS.
     */
    private val sentenceBuffer = StringBuilder()

    private fun speakChunkIfSentenceComplete(chunk: String) {
        sentenceBuffer.append(chunk)
        val buf = sentenceBuffer.toString()

        // Find the last sentence-ending position
        val sentenceEnders = charArrayOf('.', '?', '!', '\n')
        var lastEnd = -1
        for (i in buf.indices) {
            if (buf[i] in sentenceEnders) lastEnd = i
        }

        if (lastEnd >= 0) {
            val toSpeak  = buf.substring(0, lastEnd + 1).trim()
            val leftover = buf.substring(lastEnd + 1)
            sentenceBuffer.clear()
            sentenceBuffer.append(leftover)
            if (toSpeak.isNotEmpty()) {
                ttsManager.speak(toSpeak)
            }
        }
    }

    // Called from onDone to flush any remaining buffered text that had no
    // sentence-ending punctuation (shouldn't happen often, but be safe).
    private fun flushSentenceBuffer() {
        val remaining = sentenceBuffer.toString().trim()
        sentenceBuffer.clear()
        if (remaining.isNotEmpty()) {
            ttsManager.speak(remaining)
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "ChatGPT Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for voice assistant"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(currentState: AssistantState) =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Rokid ChatGPT")
            .setContentText(stateLabel(currentState))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(newState: AssistantState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(newState))
    }

    private fun stateLabel(s: AssistantState) = when (s) {
        AssistantState.IDLE      -> "Tap to start listening"
        AssistantState.LISTENING -> "ChatGPT listening…"
        AssistantState.THINKING  -> "Thinking…"
        AssistantState.SPEAKING  -> "Speaking…"
    }
}
