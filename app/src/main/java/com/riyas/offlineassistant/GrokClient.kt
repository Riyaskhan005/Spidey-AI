package com.riyas.SpideyAssistant

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * GrokClient — thin wrapper around xAI's OpenAI-compatible chat completions API.
 *
 * Usage:
 *   GrokClient().streamChat("Hello!").collect { token -> append(token) }
 *
 * The client streams tokens via Server-Sent Events (SSE) and emits each chunk
 * as a Flow<String> — the same interface used by the on-device Gemma model.
 */
class GrokClient(
    private val apiKey: String  = BuildConfig.GROK_API_KEY,
    private val model: String   = BuildConfig.GROK_MODEL,
    private val baseUrl: String = BuildConfig.GROK_BASE_URL,
) {

    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Streams a chat completion for [userMessage].
     * [history] is an optional list of prior turns: Pair(role, content).
     * Emits token chunks as they arrive via SSE.
     */
    fun streamChat(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
    ): Flow<String> = flow {
        val messagesArray = buildMessagesJson(history, userMessage)

        val bodyJson = JsonObject().apply {
            addProperty("model", model)
            add("messages", messagesArray)
            addProperty("stream", true)
            addProperty("temperature", 0.7)
            addProperty("max_tokens", 1024)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute()

        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: "Unknown error"
            throw GrokApiException("Grok API error ${response.code}: $errBody")
        }

        val source = response.body?.source()
            ?: throw GrokApiException("Empty response body from Grok API")

        // Parse SSE stream: each line is "data: {...}" or "data: [DONE]"
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                try {
                    val json = gson.fromJson(data, JsonObject::class.java)
                    val delta = json
                        .getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("delta")
                        ?.get("content")
                        ?.asString
                    if (!delta.isNullOrEmpty()) emit(delta)
                } catch (_: Exception) {
                    // Skip malformed SSE frames silently
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildMessagesJson(
        history: List<Pair<String, String>>,
        userMessage: String,
    ): com.google.gson.JsonArray {
        val arr = com.google.gson.JsonArray()
        // Optional system prompt
        arr.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty(
                "content",
                """
                You are Spidey, a smart and friendly personal AI assistant created exclusively for Riyas.
                Your name is Spidey and you always refer to yourself as Spidey.
                You know your owner is Riyas and you address him warmly and personally.
                You are helpful, witty, concise, and always ready to assist Riyas with anything he needs.
                When appropriate, you may use light humour or friendly banter — just like a personal companion would.
                Always prioritise being accurate, clear, and useful above all else.
                """.trimIndent()
            )
        })

        // Conversation history
        for ((role, content) in history) {
            arr.add(JsonObject().apply {
                addProperty("role", role)
                addProperty("content", content)
            })
        }
        // Current user turn
        arr.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", userMessage)
        })
        return arr
    }

    /** Returns true if an API key has been configured (non-empty, non-placeholder). */
    fun isConfigured(): Boolean =
        apiKey.isNotBlank() && apiKey != "xai-your-key-here" && apiKey.length > 10
}

class GrokApiException(message: String) : Exception(message)
