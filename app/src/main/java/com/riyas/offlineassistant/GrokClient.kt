package com.riyas.SpideyAssistant

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class DeviceCommandIntent(
    val action: String,
    val argument: String,
    val confidence: Double,
) {
    fun toCommandText(): String = when (action) {
        "TIME" -> "what time is it"
        "BATTERY" -> "battery level"
        "FLASHLIGHT_ON" -> "turn on flashlight"
        "FLASHLIGHT_OFF" -> "turn off flashlight"
        "WEATHER" -> if (argument.isBlank()) "weather" else "weather in $argument"
        "LOCATION" -> "where am i"
        "OPEN_APP" -> "open $argument"
        "SEARCH" -> "search for $argument"
        else -> ""
    }
}

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
    private val provider: AiProvider = AiProvider.GROK,
    private val apiKey: String  = BuildConfig.GROK_API_KEY,
    private val model: String   = BuildConfig.GROK_MODEL,
    private val baseUrl: String = BuildConfig.GROK_BASE_URL,
) {

    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

    // Command detection must not hold up the chat on a slow network
    private val classifyHttp = http.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun classifyDeviceCommand(userMessage: String): DeviceCommandIntent? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null

        val instruction = """
            Classify whether the user's message directly requests one supported assistant action.
            Understand natural phrasing, transcription mistakes, and misspellings. Do not execute questions asking how to do an action.
            Supported actions: TIME, BATTERY, FLASHLIGHT_ON, FLASHLIGHT_OFF, WEATHER, LOCATION, OPEN_APP, CALL_CONTACT, SEARCH, NONE.
            For WEATHER, include an explicitly named city as argument or use an empty argument.
            For OPEN_APP, include only the app name as argument.
            For CALL_CONTACT, include only the contact's spoken name, with no call/speaker instructions.
            Use CALL_CONTACT only when a direct call is requested and a specific person's name is present. "Call my friends" is ambiguous and must be NONE.
            For SEARCH, include only the requested search query.
            Other arguments must be empty.
            Return only JSON: {"action":"...","argument":"...","confidence":0.0}.
            Return NONE for ordinary conversation, ambiguous requests, unsupported actions, or informational questions that are not explicit searches. Never invent a contact name or action.
        """.trimIndent()
        val isGemini = provider == AiProvider.GEMINI
        val requestBody = if (isGemini) {
            JsonObject().apply {
                add("systemInstruction", JsonObject().apply {
                    add("parts", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", instruction) })
                    })
                })
                add("contents", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        add("parts", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply { addProperty("text", userMessage) })
                        })
                    })
                })
                add("generationConfig", JsonObject().apply {
                    addProperty("temperature", 0.0)
                    addProperty("maxOutputTokens", 128)
                    addProperty("responseMimeType", "application/json")
                })
            }
        } else {
            JsonObject().apply {
                addProperty("model", model)
                addProperty("stream", false)
                addProperty("temperature", 0.0)
                addProperty("max_tokens", 128)
                add("messages", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", instruction)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userMessage)
                    })
                })
            }
        }
        val url = if (isGemini) {
            val encodedModel = java.net.URLEncoder.encode(model, Charsets.UTF_8.name())
            val encodedKey = java.net.URLEncoder.encode(apiKey, Charsets.UTF_8.name())
            "${baseUrl.trimEnd('/')}/v1beta/models/$encodedModel:generateContent?key=$encodedKey"
        } else "${baseUrl.trimEnd('/')}/chat/completions"

        val response = http.newCall(
            Request.Builder()
                .url(url)
                .apply { if (!isGemini) addHeader("Authorization", "Bearer $apiKey") }
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        if (!response.isSuccessful) throw GrokApiException("Command detection API error ${response.code}")
        val body = response.body?.string() ?: return@withContext null
        val root = gson.fromJson(body, JsonObject::class.java)
        val content = if (isGemini) {
            root.getAsJsonArray("candidates")?.get(0)?.asJsonObject
                ?.getAsJsonObject("content")?.getAsJsonArray("parts")
                ?.get(0)?.asJsonObject?.get("text")?.asString
        } else {
            root.getAsJsonArray("choices")?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")?.get("content")?.asString
        } ?: return@withContext null

        val jsonText = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val result = runCatching { gson.fromJson(jsonText, JsonObject::class.java) }.getOrNull()
            ?: return@withContext null
        val action = result.get("action")?.asString?.uppercase() ?: return@withContext null
        val allowed = setOf(
            "TIME", "BATTERY", "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "WEATHER", "LOCATION",
            "OPEN_APP", "CALL_CONTACT", "SEARCH"
        )
        if (action !in allowed) return@withContext null
        val argument = result.get("argument")?.asString?.trim().orEmpty().take(80)
        val confidence = result.get("confidence")?.asDouble ?: 0.0
        val minimumConfidence = if (action == "CALL_CONTACT") 0.93 else 0.85
        if (confidence < minimumConfidence ||
            (action in setOf("OPEN_APP", "CALL_CONTACT", "SEARCH") && argument.isBlank())
        ) return@withContext null
        DeviceCommandIntent(action, argument, confidence)
    }

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

        val isGemini = provider == AiProvider.GEMINI
        val bodyJson = if (isGemini) {
            buildGeminiBody(messagesArray)
        } else {
            JsonObject().apply {
                addProperty("model", model)
                add("messages", messagesArray)
                addProperty("stream", true)
                addProperty("temperature", 0.7)
                addProperty("max_tokens", 1024)
            }
        }
        val requestUrl = if (isGemini) {
            val encodedModel = java.net.URLEncoder.encode(model, Charsets.UTF_8.name())
            val encodedKey = java.net.URLEncoder.encode(apiKey, Charsets.UTF_8.name())
            "${baseUrl.trimEnd('/')}/v1beta/models/$encodedModel:streamGenerateContent?alt=sse&key=$encodedKey"
        } else {
            "${baseUrl.trimEnd('/')}/chat/completions"
        }

        val request = Request.Builder()
            .url(requestUrl)
            .apply {
                if (!isGemini) addHeader("Authorization", "Bearer $apiKey")
            }
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute()

        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: "Unknown error"
            throw GrokApiException("API error ${response.code}: $errBody")
        }

        val source = response.body?.source()
            ?: throw GrokApiException("Empty response body from API")

        // Parse SSE stream: each line is "data: {...}" or "data: [DONE]"
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                try {
                    val json = gson.fromJson(data, JsonObject::class.java)
                    val delta = if (isGemini) {
                        json.getAsJsonArray("candidates")
                            ?.get(0)?.asJsonObject
                            ?.getAsJsonObject("content")
                            ?.getAsJsonArray("parts")
                            ?.get(0)?.asJsonObject
                            ?.get("text")?.asString
                    } else {
                        json.getAsJsonArray("choices")
                            ?.get(0)?.asJsonObject
                            ?.getAsJsonObject("delta")
                            ?.get("content")?.asString
                    }
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

                Answer only in English or Tamil. Match the user's language: use natural spoken Tamil in Tamil script when they speak Tamil, and clear simple English when they speak English. Understand Tanglish naturally; reply in Tanglish only when the user uses or asks for it. Never switch to another language. Keep voice replies concise and easy to pronounce.

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

    private fun buildGeminiBody(messages: com.google.gson.JsonArray): JsonObject {
        val contents = com.google.gson.JsonArray()
        var systemInstruction: JsonObject? = null
        messages.forEach { item ->
            val message = item.asJsonObject
            val role = message.get("role").asString
            val content = message.get("content").asString
            if (role == "system") {
                systemInstruction = JsonObject().apply {
                    add("parts", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", content) })
                    })
                }
            } else {
                contents.add(JsonObject().apply {
                    addProperty("role", if (role == "assistant") "model" else "user")
                    add("parts", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", content) })
                    })
                })
            }
        }
        return JsonObject().apply {
            systemInstruction?.let { add("systemInstruction", it) }
            add("contents", contents)
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.7)
                addProperty("maxOutputTokens", 1024)
            })
        }
    }

    /** Returns true if an API key has been configured (non-empty, non-placeholder). */
    fun isConfigured(): Boolean =
        apiKey.isNotBlank() && apiKey != "xai-your-key-here" && apiKey.length > 10
}

class GrokApiException(message: String) : Exception(message)
