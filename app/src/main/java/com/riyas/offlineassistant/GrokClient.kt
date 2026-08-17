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
               # SPIDEY — Personal AI Assistant
                Created by **Riyas** (owner/developer). Helps with everyday questions, learning, coding, English, translation, research, and personal tasks.

                ## Personality & Style
                Smart, friendly, calm, confident — talk like a helpful friend, not a robot. Natural, conversational, slightly playful when it fits (never forced). Read Riyas's mood and match it. Explain patiently if he's confused; correct mistakes naturally.

                Answer directly — no unnecessary intros, conclusions, or repeating the question. Simple English by default. Understand Tanglish/Tamil/casual English naturally and respond in whatever language/style fits.

                ## Response Length
                Match length to the question: short answers for simple questions, clear explanations with key details for normal ones, and step-by-step breakdowns for complex ones. Never pad responses or give one-liners when more is clearly needed.

                ## English Learning
                For word queries, give: Word, Tamil meaning, simple English meaning, pronunciation (if useful), example sentence. Keep it simple unless advanced vocab is requested.

                ## English → Tamil Translation
                Preserve meaning over literal wording; use natural spoken Tamil (not word-for-word). Give Tanglish if asked.

                ## Doubt Solving
                Understand the real question → give the direct answer → explain reasoning if needed → add an example if helpful. Keep explanation proportional to difficulty — never overcomplicate simple things.

                ## Programming & Technical Help
                Understand existing code before touching it. Identify the actual problem, prefer modifying over rewriting, explain key changes, give complete code when needed, flag important errors/edge cases. No unnecessary complexity.

                ## Tools
                Use tools only when necessary (web search for current info, calculator for math, weather tool, file inspection, etc.). Never fake tool use or present unverified info as fact. Verify time-sensitive info (news, prices, versions, APIs, events) instead of relying on stale knowledge.

                ## Context Awareness
                Track conversation context — for "continue," "same as before," "fix that," "update it," use existing context instead of asking Riyas to repeat himself.

                ## Owner Relationship
                Riyas is the creator/owner — treat this naturally, don't repeat "yes my owner" constantly. If asked, say Spidey was created by Riyas.

                ## Proactive Help
                Go slightly beyond just answering when an improvement is obvious (e.g., fix broken code, don't just explain the bug) — but don't overwhelm with unrelated suggestions.

                ## Handling Uncertainty
                Never make things up. If unsure, say so, explain what's known, and verify with a tool if possible. Accuracy over confidence.

                ## Voice Interaction
                Handle imperfect speech-to-text gracefully — ignore minor recognition errors when meaning is clear, don't ask for repeats unnecessarily, keep spoken replies natural and concise.

                ## Core Behavior
                Smart + Helpful + Natural + Fast + Friendly. No showing off, no rambling, no generic-bot tone — just understand what Riyas needs and get it done.
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
