package com.riyas.SpideyAssistant

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AiProvider(
    val label: String,
    val defaultModel: String,
    val defaultBaseUrl: String,
) {
    GEMINI("Gemini", "gemini-2.5-flash", "https://generativelanguage.googleapis.com"),
    GROK("Grok", "grok-3-mini", "https://api.x.ai/v1"),
    GROQ("Groq", "openai/gpt-oss-120b", "https://api.groq.com/openai/v1"),
    OPENAI("OpenAI", "gpt-4o-mini", "https://api.openai.com/v1"),
    OFFLINE("Offline model", "", "");

    val storageKey: String get() = name.lowercase()
}

enum class OfflineBackend {
    CPU,
    GPU,
}

data class AiSettings(
    val provider: AiProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val modelUri: String = "",
    val offlineBackend: OfflineBackend = OfflineBackend.CPU,
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.95,
    val contextTokenBudget: Int = 2048,
    val maxOutputTokens: Int = 256,
    val visualTokenBudget: Int? = 70,
)

class AiSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("ai_provider_settings", Context.MODE_PRIVATE)
    private val keyAlias = "spidey.ai.provider.key"

    fun loadTavilyApiKey(): String =
        preferences.getString("tavily_api_key", null)?.let(::decrypt).orEmpty()

    fun saveTavilyApiKey(apiKey: String) {
        preferences.edit().putString("tavily_api_key", encrypt(apiKey.trim())).apply()
    }

    fun load(): AiSettings {
        val provider = runCatching {
            AiProvider.valueOf(preferences.getString("active_provider", AiProvider.GROK.name)!!)
        }.getOrDefault(AiProvider.GROK)
        return load(provider)
    }

    fun load(provider: AiProvider): AiSettings {
        val suffix = provider.storageKey
        val encryptedKey = preferences.getString("api_key_$suffix", null)
        val key = when {
            encryptedKey != null -> decrypt(encryptedKey)
            provider == AiProvider.GROQ -> BuildConfig.GROK_API_KEY
            else -> ""
        }
        val savedContextBudget = preferences.getInt("offline_context_tokens", 2048)
        val savedOutputBudget = preferences.getInt("offline_max_output_tokens", 256)
        val usedPreviousDefaultBudgets = savedContextBudget == 4096 && savedOutputBudget == 512
        return AiSettings(
            provider = provider,
            apiKey = key,
            model = preferences.getString("model_$suffix", provider.defaultModel) ?: provider.defaultModel,
            baseUrl = preferences.getString("base_url_$suffix", provider.defaultBaseUrl) ?: provider.defaultBaseUrl,
            modelUri = preferences.getString("offline_model_uri", "").orEmpty(),
            offlineBackend = runCatching {
                OfflineBackend.valueOf(preferences.getString("offline_backend", OfflineBackend.CPU.name)!!)
            }.getOrDefault(OfflineBackend.CPU),
            temperature = preferences.getFloat("offline_temperature", 0.7f).toDouble(),
            topK = preferences.getInt("offline_top_k", 40),
            topP = preferences.getFloat("offline_top_p", 0.95f).toDouble(),
            contextTokenBudget = if (usedPreviousDefaultBudgets) 2048 else savedContextBudget,
            maxOutputTokens = if (usedPreviousDefaultBudgets) 256 else savedOutputBudget,
            visualTokenBudget = preferences.getInt("offline_visual_token_budget", 70)
                .takeIf { it > 0 },
        )
    }

    fun saveKokoroModel(uri: Uri) {
        preferences.edit().putString("kokoro_model_uri", uri.toString()).apply()
    }

    fun loadKokoroModel(): Uri? =
        preferences.getString("kokoro_model_uri", null)?.let(Uri::parse)

    fun saveKokoroVoice(voice: String) {
        preferences.edit().putString("kokoro_voice", voice).apply()
    }

    fun loadKokoroVoice(): String =
        preferences.getString("kokoro_voice", "am_puck") ?: "am_puck"

    fun save(settings: AiSettings, selectProvider: Boolean = true) {
        val suffix = settings.provider.storageKey
        preferences.edit()
            .putString("api_key_$suffix", encrypt(settings.apiKey.trim()))
            .putString("model_$suffix", settings.model.trim())
            .putString("base_url_$suffix", settings.baseUrl.trim().trimEnd('/'))
            .putString("offline_model_uri", settings.modelUri)
            .apply {
                if (settings.provider == AiProvider.OFFLINE) {
                    putString("offline_backend", settings.offlineBackend.name)
                    putFloat("offline_temperature", settings.temperature.toFloat())
                    putInt("offline_top_k", settings.topK)
                    putFloat("offline_top_p", settings.topP.toFloat())
                    putInt("offline_context_tokens", settings.contextTokenBudget)
                    putInt("offline_max_output_tokens", settings.maxOutputTokens)
                    putInt("offline_visual_token_budget", settings.visualTokenBudget ?: -1)
                }
                if (selectProvider) putString("active_provider", settings.provider.name)
            }
            .apply()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            val data = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data, 0, 12))
            String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}