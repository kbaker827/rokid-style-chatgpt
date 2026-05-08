package com.rokid.style.chatgpt

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences – mirrors iOS UserDefaults usage.
 */
object Prefs {

    private const val PREF_FILE = "rokid_chatgpt_prefs"

    private const val KEY_API_KEY        = "api_key"
    private const val KEY_MODEL_ID       = "model_id"
    private const val KEY_SYSTEM_PROMPT  = "system_prompt"
    private const val KEY_MAX_TOKENS     = "max_tokens"
    private const val KEY_MAX_HISTORY    = "max_history"
    private const val KEY_VOICE_ENABLED  = "voice_enabled"
    private const val KEY_AUTO_SEND      = "auto_send_voice"

    val DEFAULT_SYSTEM_PROMPT =
        "You are a helpful AI assistant on Rokid AR glasses. " +
        "Keep answers concise — 1-3 sentences. " +
        "Avoid markdown formatting. Speak naturally."

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // ── API key ──────────────────────────────────────────────────────────────

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(context: Context, value: String) =
        prefs(context).edit().putString(KEY_API_KEY, value).apply()

    // ── Model ────────────────────────────────────────────────────────────────

    fun getModelId(context: Context): String =
        prefs(context).getString(KEY_MODEL_ID, "gpt-4o-mini") ?: "gpt-4o-mini"

    fun setModelId(context: Context, value: String) =
        prefs(context).edit().putString(KEY_MODEL_ID, value).apply()

    // ── System prompt ────────────────────────────────────────────────────────

    fun getSystemPrompt(context: Context): String =
        prefs(context).getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT

    fun setSystemPrompt(context: Context, value: String) =
        prefs(context).edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    // ── Max tokens ───────────────────────────────────────────────────────────

    fun getMaxTokens(context: Context): Int =
        prefs(context).getInt(KEY_MAX_TOKENS, 512)

    fun setMaxTokens(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_MAX_TOKENS, value).apply()

    // ── Max history ──────────────────────────────────────────────────────────

    fun getMaxHistory(context: Context): Int =
        prefs(context).getInt(KEY_MAX_HISTORY, 6)

    fun setMaxHistory(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_MAX_HISTORY, value).apply()

    // ── Voice enabled ────────────────────────────────────────────────────────

    fun getVoiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOICE_ENABLED, true)

    fun setVoiceEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_VOICE_ENABLED, value).apply()

    // ── Auto-send on silence ─────────────────────────────────────────────────

    fun getAutoSendVoice(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SEND, true)

    fun setAutoSendVoice(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_SEND, value).apply()
}
