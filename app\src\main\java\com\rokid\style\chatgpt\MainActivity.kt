package com.rokid.style.chatgpt

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.style.chatgpt.databinding.ActivityMainBinding

/**
 * Single-screen UI.
 *
 * On display-free Rokid glasses this activity still runs in the background
 * (no screen output visible) but controls the [VoiceAssistantService].
 * When a companion display is attached the status label and transcript log
 * provide visual feedback.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 100
    }

    private lateinit var binding: ActivityMainBinding
    private var service: VoiceAssistantService? = null
    private var serviceBound = false

    // ── Service connection ────────────────────────────────────────────────────

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as VoiceAssistantService.LocalBinder).getService()
            serviceBound = true
            wireServiceCallbacks()
            updateUi(service!!.getCurrentState())
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            service = null
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.fabListen.setOnClickListener { onFabClicked() }
        binding.btnClearHistory.setOnClickListener { onClearHistory() }

        checkAndRequestAudioPermission()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, VoiceAssistantService::class.java).apply {
            action = VoiceAssistantService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(Intent(this, VoiceAssistantService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Permission handling ───────────────────────────────────────────────────

    private fun checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Service callbacks ─────────────────────────────────────────────────────

    private fun wireServiceCallbacks() {
        val svc = service ?: return

        svc.onStateChanged = { state ->
            runOnUiThread { updateUi(state) }
        }

        svc.onPartialSpeech = { partial ->
            runOnUiThread {
                binding.tvTranscript.text = partial
            }
        }

        svc.onResponseChunk = { chunk ->
            runOnUiThread {
                binding.tvResponse.append(chunk)
                // Auto-scroll
                binding.scrollResponse.post {
                    binding.scrollResponse.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }

        svc.onError = { msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                binding.tvStatus.text = "Error — tap to retry"
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun onFabClicked() {
        val svc = service ?: return
        when (svc.getCurrentState()) {
            VoiceAssistantService.AssistantState.IDLE -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    if (Prefs.getApiKey(this).isBlank()) {
                        Toast.makeText(this, "Please set your OpenAI API key in Settings", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, SettingsActivity::class.java))
                        return
                    }
                    svc.startListening()
                    binding.tvResponse.text = ""
                } else {
                    checkAndRequestAudioPermission()
                }
            }
            else -> {
                svc.stopListening()
            }
        }
    }

    private fun onClearHistory() {
        service?.clearHistory()
        binding.tvResponse.text = ""
        binding.tvTranscript.text = ""
        Toast.makeText(this, "Conversation history cleared", Toast.LENGTH_SHORT).show()
    }

    private fun updateUi(state: VoiceAssistantService.AssistantState) {
        binding.tvStatus.text = when (state) {
            VoiceAssistantService.AssistantState.IDLE      -> getString(R.string.status_idle)
            VoiceAssistantService.AssistantState.LISTENING -> getString(R.string.status_listening)
            VoiceAssistantService.AssistantState.THINKING  -> getString(R.string.status_thinking)
            VoiceAssistantService.AssistantState.SPEAKING  -> getString(R.string.status_speaking)
        }

        binding.fabListen.setImageResource(
            if (state == VoiceAssistantService.AssistantState.IDLE)
                android.R.drawable.ic_btn_speak_now
            else
                android.R.drawable.ic_media_pause
        )

        // Clear transcript line when a new cycle starts
        if (state == VoiceAssistantService.AssistantState.LISTENING) {
            binding.tvTranscript.text = ""
        }
    }
}
