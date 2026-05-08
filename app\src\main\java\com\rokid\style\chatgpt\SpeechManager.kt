package com.rokid.style.chatgpt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Wraps Android [SpeechRecognizer] with:
 *  - Continuous (auto-restart) listening
 *  - 1.8 s silence timer — fires [onSilence] with the accumulated transcript
 *  - Mirrors iOS SFSpeechRecognizer + silence-timer pattern
 *
 * Call [start] to begin listening, [stop] to tear down.
 * The owner must hold RECORD_AUDIO permission before calling [start].
 */
class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG              = "SpeechManager"
        private const val SILENCE_DELAY_MS = 1800L   // 1.8 s – matches iOS
    }

    // ── Callbacks ────────────────────────────────────────────────────────────

    /** Called when the user has stopped speaking for [SILENCE_DELAY_MS]. */
    var onSilence: ((transcript: String) -> Unit)? = null

    /** Called with partial results while the user is speaking. */
    var onPartialResult: ((text: String) -> Unit)? = null

    /** Called when listening state changes. */
    var onListeningStateChanged: ((isListening: Boolean) -> Unit)? = null

    /** Called on unrecoverable error. */
    var onError: ((message: String) -> Unit)? = null

    // ── State ────────────────────────────────────────────────────────────────

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler   = Handler(Looper.getMainLooper())
    private var currentText   = StringBuilder()
    private var isListening   = false
    private var shouldRestart = false
    private var paused        = false   // suppressed while TTS is speaking

    // ── Silence timer ────────────────────────────────────────────────────────

    private val silenceRunnable = Runnable {
        val text = currentText.toString().trim()
        if (text.isNotEmpty()) {
            Log.d(TAG, "Silence detected, transcript: $text")
            onSilence?.invoke(text)
            currentText.clear()
        }
    }

    private fun resetSilenceTimer() {
        mainHandler.removeCallbacks(silenceRunnable)
        mainHandler.postDelayed(silenceRunnable, SILENCE_DELAY_MS)
    }

    private fun cancelSilenceTimer() {
        mainHandler.removeCallbacks(silenceRunnable)
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun start() {
        if (isListening) return
        paused        = false
        shouldRestart = true
        startRecognizer()
    }

    fun stop() {
        shouldRestart = false
        paused        = false
        cancelSilenceTimer()
        destroyRecognizer()
    }

    /**
     * Pause recognition while TTS is playing.
     * The recognizer is torn down and will be restarted via [resume].
     */
    fun pause() {
        if (paused) return
        paused = true
        cancelSilenceTimer()
        destroyRecognizer()
        onListeningStateChanged?.invoke(false)
    }

    /** Resume recognition after TTS has finished. */
    fun resume() {
        if (!paused) return
        paused = false
        if (shouldRestart) {
            startRecognizer()
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("Speech recognition not available on this device")
            return
        }

        destroyRecognizer()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Keep microphone open as long as possible
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        recognizer?.startListening(intent)
        isListening = true
        onListeningStateChanged?.invoke(true)
        Log.d(TAG, "Recognizer started")
    }

    private fun destroyRecognizer() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer  = null
        isListening = false
    }

    private fun restartIfNeeded() {
        if (shouldRestart && !paused) {
            // Brief delay to let the audio system release the mic
            mainHandler.postDelayed({ startRecognizer() }, 300)
        }
    }

    // ── RecognitionListener ──────────────────────────────────────────────────

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
            cancelSilenceTimer()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Could be used to drive a visual level meter if a display is available
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
            resetSilenceTimer()
        }

        override fun onError(error: Int) {
            val msg = errorMessage(error)
            Log.w(TAG, "Recognition error: $msg ($error)")

            // Transient errors — just restart
            val isTransient = error == SpeechRecognizer.ERROR_NO_MATCH ||
                              error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                              error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            if (isTransient) {
                isListening = false
                restartIfNeeded()
            } else {
                isListening = false
                onError?.invoke(msg)
                restartIfNeeded()
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text    = matches?.firstOrNull() ?: ""
            Log.d(TAG, "Final result: $text")

            if (text.isNotEmpty()) {
                currentText.clear()
                currentText.append(text)
                // Silence timer may already have fired; fire it now if not
                resetSilenceTimer()
            }

            isListening = false
            restartIfNeeded()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return

            if (partial.isNotEmpty()) {
                currentText.clear()
                currentText.append(partial)
                onPartialResult?.invoke(partial)
                resetSilenceTimer()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun errorMessage(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO                -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT               -> "Client-side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing RECORD_AUDIO permission"
        SpeechRecognizer.ERROR_NETWORK              -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT      -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH             -> "No speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY      -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER               -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT       -> "No speech input"
        else                                        -> "Unknown error ($code)"
    }
}
