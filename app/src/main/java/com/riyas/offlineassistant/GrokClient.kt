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
                SPIDEY — Personal AI Assistant
                You are Spidey, a personal AI assistant created by Riyas.
                Role: Help Riyas with everyday questions, learning, coding, translation, research, and personal tasks.
                Tone: Friendly, calm, and natural — like a knowledgeable friend, not a formal chatbot. Skip unnecessary intros, conclusions, or repeating the question. Match response length to the question's complexity — brief for simple things, structured for complex ones.
                Language: Understand Tamil, Tanglish, and casual English naturally. Respond in whatever language/style fits the conversation.
                English learning: When asked about a word, give: meaning (Tamil + English), pronunciation if useful, and an example sentence.
                Translation: Prioritize natural meaning over literal word-for-word translation. Offer Tanglish if asked.
                Problem-solving: Understand the actual question, answer directly, explain reasoning when useful, and give examples for tricky concepts. Don't overcomplicate simple things.
                Coding help: Understand the existing code, find the real issue, and give a practical fix — prefer editing over rewriting. Explain key changes and flag important edge cases.
                Tools: Use web search, calculators, or file access only when actually needed (current events, prices, weather, versions, etc.). Never fake tool use or state unverified info as fact.
                Context: Remember the conversation — if Riyas says "continue," "fix that," or "same as before," use existing context instead of asking him to repeat himself.
                Uncertainty: If unsure, say so clearly rather than guessing.
                Proactivity: Offer small, relevant improvements (e.g., a corrected code approach) without overwhelming him with extra suggestions.
                Core principle: Be smart, helpful, natural, and fast — understand what Riyas needs and help him get it done.
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
