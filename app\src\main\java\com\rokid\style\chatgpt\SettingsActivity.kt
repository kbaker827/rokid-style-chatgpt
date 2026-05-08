package com.rokid.style.chatgpt

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rokid.style.chatgpt.databinding.ActivitySettingsBinding

/**
 * Settings screen — mirrors iOS settings sheet.
 *
 * Fields:
 *  - OpenAI API key
 *  - Model selection (dropdown)
 *  - System prompt
 *  - Max tokens  (100 – 4096)
 *  - Max history (1 – 20 pairs)
 *  - Voice enabled toggle
 *  - Auto-send on silence toggle
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        val GPT_MODELS = listOf(
            "gpt-4o-mini",
            "gpt-4o",
            "gpt-4-turbo",
            "gpt-3.5-turbo"
        )
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_settings)

        setupModelSpinner()
        loadCurrentValues()

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnResetPrompt.setOnClickListener { resetSystemPrompt() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun setupModelSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, GPT_MODELS)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerModel.adapter = adapter
    }

    private fun loadCurrentValues() {
        binding.etApiKey.setText(Prefs.getApiKey(this))

        val modelIndex = GPT_MODELS.indexOf(Prefs.getModelId(this))
        binding.spinnerModel.setSelection(if (modelIndex >= 0) modelIndex else 0)

        binding.etSystemPrompt.setText(Prefs.getSystemPrompt(this))
        binding.etMaxTokens.setText(Prefs.getMaxTokens(this).toString())
        binding.etMaxHistory.setText(Prefs.getMaxHistory(this).toString())
        binding.switchVoiceEnabled.isChecked = Prefs.getVoiceEnabled(this)
        binding.switchAutoSend.isChecked = Prefs.getAutoSendVoice(this)
    }

    // ── Save / reset ──────────────────────────────────────────────────────────

    private fun saveSettings() {
        val apiKey = binding.etApiKey.text.toString().trim()
        if (apiKey.isEmpty()) {
            binding.etApiKey.error = "API key is required"
            return
        }

        val maxTokensStr = binding.etMaxTokens.text.toString().trim()
        val maxTokens = maxTokensStr.toIntOrNull()
        if (maxTokens == null || maxTokens !in 100..4096) {
            binding.etMaxTokens.error = "Enter a value between 100 and 4096"
            return
        }

        val maxHistoryStr = binding.etMaxHistory.text.toString().trim()
        val maxHistory = maxHistoryStr.toIntOrNull()
        if (maxHistory == null || maxHistory !in 1..20) {
            binding.etMaxHistory.error = "Enter a value between 1 and 20"
            return
        }

        val systemPrompt = binding.etSystemPrompt.text.toString().trim()
        if (systemPrompt.isEmpty()) {
            binding.etSystemPrompt.error = "System prompt cannot be empty"
            return
        }

        Prefs.setApiKey(this, apiKey)
        Prefs.setModelId(this, GPT_MODELS[binding.spinnerModel.selectedItemPosition])
        Prefs.setSystemPrompt(this, systemPrompt)
        Prefs.setMaxTokens(this, maxTokens)
        Prefs.setMaxHistory(this, maxHistory)
        Prefs.setVoiceEnabled(this, binding.switchVoiceEnabled.isChecked)
        Prefs.setAutoSendVoice(this, binding.switchAutoSend.isChecked)

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun resetSystemPrompt() {
        binding.etSystemPrompt.setText(Prefs.DEFAULT_SYSTEM_PROMPT)
    }
}
