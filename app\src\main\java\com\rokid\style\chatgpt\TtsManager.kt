package com.rokid.style.chatgpt

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps Android TextToSpeech.
 *
 * - Initialises lazily on first [speak] call.
 * - Queues utterances so streaming chunks can be fed one sentence at a time.
 * - [onDone] callback fires when the TTS queue drains (used to re-arm the mic).
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private val pendingQueue = ArrayDeque<String>()

    var onDone: (() -> Unit)? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(utteranceListener)
                ready.set(true)
                // flush anything that arrived before init finished
                flushPending()
            } else {
                Log.e(TAG, "TTS init failed with status $status")
            }
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
        pendingQueue.clear()
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Speak [text] – safe to call before init completes. */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (ready.get()) {
            enqueueTts(text)
        } else {
            pendingQueue.addLast(text)
            init()
        }
    }

    /** Stop current speech immediately and clear queue. */
    fun stop() {
        tts?.stop()
    }

    val isSpeaking: Boolean
        get() = tts?.isSpeaking == true

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun flushPending() {
        while (pendingQueue.isNotEmpty()) {
            enqueueTts(pendingQueue.removeFirst())
        }
    }

    private fun enqueueTts(text: String) {
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            // Fire callback only when the queue is truly empty
            if (tts?.isSpeaking == false) {
                onDone?.invoke()
            }
        }

        @Deprecated("Deprecated in API level 21")
        override fun onError(utteranceId: String?) {
            Log.e(TAG, "TTS error for utterance $utteranceId")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.e(TAG, "TTS error $errorCode for utterance $utteranceId")
        }
    }
}
