package com.riyas.SpideyAssistant

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalApi::class)
class OfflineModelClient(private val context: Context) {
    private data class EngineSettingsKey(
        val modelUri: String,
        val backend: OfflineBackend,
        val contextTokenBudget: Int,
        val visualTokenBudget: Int?,
    )

    private var activeEngineSettings: EngineSettingsKey? = null
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    @Volatile private var inferenceActive = false
    @Volatile private var releaseAfterInference = false

    fun streamChat(
        modelUri: Uri?,
        userMessage: String,
        history: List<Pair<String, String>>,
        settings: AiSettings,
    ): Flow<String> = flow {
        inferenceActive = true
        try {
            val selectedUri = modelUri ?: throw IllegalStateException("Select an offline model in AI settings.")
            val activeConversation = getConversation(selectedUri, history, settings)
            activeConversation.sendMessageAsync(userMessage).collect { message ->
                emit(message.toString())
            }
        } catch (error: Exception) {
            close()
            throw error
        } finally {
            closeConversation()
            inferenceActive = false
            if (releaseAfterInference) {
                releaseAfterInference = false
                close()
            }
        }
    }.flowOn(Dispatchers.IO)

    fun releaseForMemoryPressure() {
        if (inferenceActive) {
            releaseAfterInference = true
        } else {
            close()
        }
    }

    fun close() {
        closeConversation()
        engine?.close()
        engine = null
        activeEngineSettings = null
        ExperimentalFlags.visualTokenBudget = null
    }

    private fun getConversation(
        modelUri: Uri,
        history: List<Pair<String, String>>,
        settings: AiSettings,
    ): Conversation {
        val settingsKey = EngineSettingsKey(
            modelUri = modelUri.toString(),
            backend = settings.offlineBackend,
            contextTokenBudget = settings.contextTokenBudget,
            visualTokenBudget = settings.visualTokenBudget,
        )
        if (activeEngineSettings != settingsKey) {
            close()
            val modelFile = copyModelToAppStorage(modelUri)
            val backend = when (settings.offlineBackend) {
                OfflineBackend.CPU -> Backend.CPU()
                OfflineBackend.GPU -> Backend.GPU()
            }
            ExperimentalFlags.visualTokenBudget = settings.visualTokenBudget
            val newEngine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    maxNumTokens = settings.contextTokenBudget,
                    cacheDir = File(context.cacheDir, "litertlm-cache").absolutePath,
                )
            )
            try {
                newEngine.initialize()
                engine = newEngine
                activeEngineSettings = settingsKey
            } catch (error: Exception) {
                newEngine.close()
                throw error
            }
        }

        closeConversation()
        val initialMessages = history
            .takeLast(MAX_HISTORY_MESSAGES)
            .map { (role, content) ->
                val boundedContent = content.takeLast(MAX_HISTORY_CHARS_PER_MESSAGE)
                if (role == "assistant") Message.model(boundedContent) else Message.user(boundedContent)
            }
        return checkNotNull(engine).createConversation(
            ConversationConfig(
                systemInstruction = com.google.ai.edge.litertlm.Contents.of(
                    "You are Spidey, a helpful personal assistant. Reply naturally and concisely in English or Tamil.",
                ),
                initialMessages = initialMessages,
                samplerConfig = SamplerConfig(
                    topK = settings.topK,
                    topP = settings.topP,
                    temperature = settings.temperature,
                ),
                maxOutputToken = settings.maxOutputTokens,
            )
        )
            .also { conversation = it }
    }

    private fun closeConversation() {
        conversation?.close()
        conversation = null
    }

    private fun copyModelToAppStorage(uri: Uri): File {
        val modelName = modelFileName("offline", uri)
        val model = File(context.noBackupFilesDir, modelName)
        if (model.isFile && model.length() > 0) return model

        val partial = File(context.noBackupFilesDir, "$modelName.part")
        partial.delete()
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            partial.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Unable to read the selected offline model.")
        check(partial.isFile && partial.length() > 0) { "The selected offline model is empty." }
        if (model.exists() && !model.delete()) {
            throw IllegalStateException("Unable to replace the previous offline model.")
        }
        check(partial.renameTo(model)) { "Unable to install the offline model." }
        return model
    }

    private companion object {
        const val MAX_HISTORY_MESSAGES = 4
        const val MAX_HISTORY_CHARS_PER_MESSAGE = 512
    }
}

internal fun modelFileName(prefix: String, uri: Uri): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
    val hex = "0123456789abcdef"
    return buildString(prefix.length + digest.size * 2 + ".litertlm".length) {
        append(prefix)
        digest.forEach { value ->
            val byte = value.toInt() and 0xff
            append(hex[byte ushr 4])
            append(hex[byte and 0x0f])
        }
        append(".litertlm")
    }
}
