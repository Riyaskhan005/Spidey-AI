package com.riyas.SpideyAssistant

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ElevenLabsClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun synthesize(text: String, apiKey: String, voiceId: String): ByteArray =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Add an ElevenLabs API key in AI settings." }
            require(voiceId.isNotBlank()) { "Add an ElevenLabs voice ID in AI settings." }

            val requestBody = JsonObject().apply {
                addProperty("text", text)
                addProperty("model_id", "eleven_multilingual_v2")
                add("voice_settings", JsonObject().apply {
                    addProperty("stability", 0.55)
                    addProperty("similarity_boost", 0.8)
                    addProperty("style", 0.05)
                    addProperty("use_speaker_boost", true)
                    addProperty("speed", 0.95)
                })
            }
            val encodedVoiceId = URLEncoder.encode(voiceId, Charsets.UTF_8.name())
            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$encodedVoiceId?output_format=mp3_44100_128")
                .header("xi-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body ?: throw IllegalStateException("ElevenLabs returned an empty response.")
                if (!response.isSuccessful) {
                    val details = body.string().take(400)
                    throw IllegalStateException("ElevenLabs speech failed (${response.code}): $details")
                }
                body.bytes()
            }
        }
}