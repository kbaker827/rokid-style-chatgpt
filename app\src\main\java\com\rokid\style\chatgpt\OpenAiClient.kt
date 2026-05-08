package com.rokid.style.chatgpt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Handles streaming SSE requests to the OpenAI Chat Completions API.
 *
 * Mirrors ChatGPTAPIClient.swift (actor) behaviour:
 *  - Sends messages array with prepended system message
 *  - Parses `data:` SSE lines → ChatCompletionChunk → choices[0].delta.content
 *  - Emits each content fragment via a [Flow]
 */
class OpenAiClient {

    companion object {
        private const val TAG        = "OpenAiClient"
        private const val ENDPOINT   = "https://api.openai.com/v1/chat/completions"
        private const val DONE_TOKEN = "[DONE]"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Stream a ChatGPT response.
     *
     * @param apiKey      OpenAI API key
     * @param model       e.g. "gpt-4o-mini"
     * @param messages    Full messages list (system + history + latest user message)
     * @param maxTokens   Token budget for the response
     * @return [Flow] of delta content strings; throws on HTTP / parse error
     */
    fun streamCompletion(
        apiKey: String,
        model: String,
        messages: List<Map<String, String>>,
        maxTokens: Int
    ): Flow<String> = flow {
        val messagesArray = JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            obj.put("role", msg["role"] ?: "user")
            obj.put("content", msg["content"] ?: "")
            messagesArray.put(obj)
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("max_tokens", maxTokens)
            put("stream", true)
        }.toString()

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        Log.d(TAG, "Sending request to OpenAI: model=$model, messages=${messages.size}")

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "unknown error"
            Log.e(TAG, "HTTP ${response.code}: $errorBody")
            throw OpenAiException(response.code, errorBody)
        }

        val responseBody = response.body
            ?: throw OpenAiException(-1, "Empty response body")

        val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val raw = line!!.trim()
                if (raw.isEmpty()) continue

                // SSE lines are prefixed with "data: "
                if (!raw.startsWith("data:")) continue

                val data = raw.removePrefix("data:").trim()
                if (data == DONE_TOKEN) break

                val delta = parseDelta(data)
                if (delta != null && delta.isNotEmpty()) {
                    emit(delta)
                }
            }
        } finally {
            reader.close()
            responseBody.close()
        }
    }.flowOn(Dispatchers.IO)

    // ── Parsing ──────────────────────────────────────────────────────────────

    /**
     * Parse a single SSE data payload into the delta content string.
     *
     * Expected JSON shape:
     * {
     *   "choices": [ { "delta": { "content": "..." } } ]
     * }
     */
    private fun parseDelta(jsonStr: String): String? {
        return try {
            val root    = JSONObject(jsonStr)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val delta   = choices.getJSONObject(0).optJSONObject("delta") ?: return null
            delta.optString("content", null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse delta: $jsonStr", e)
            null
        }
    }
}

/** Thrown when the OpenAI API returns a non-2xx response. */
class OpenAiException(val code: Int, message: String) : Exception("HTTP $code: $message")
