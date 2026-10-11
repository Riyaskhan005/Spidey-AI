package com.riyas.SpideyAssistant

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var settingsStore: AiSettingsStore
    private lateinit var providerGroup: RadioGroup
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var tavilyApiKeyInput: EditText
    private lateinit var elevenLabsApiKeyInput: EditText
    private lateinit var elevenLabsVoiceIdInput: EditText
    private val drafts = mutableMapOf<AiProvider, AiSettings>()
    private var selectedProvider = AiProvider.GROK

    private val providerRadioIds = mapOf(
        AiProvider.GEMINI to R.id.providerGemini,
        AiProvider.GROK to R.id.providerGrok,
        AiProvider.GROQ to R.id.providerGroq,
        AiProvider.OPENAI to R.id.providerOpenAi,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_settings)

        settingsStore = AiSettingsStore(this)
        providerGroup = findViewById(R.id.providerGroup)
        modelInput = findViewById(R.id.modelInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        tavilyApiKeyInput = findViewById(R.id.tavilyApiKeyInput)
        elevenLabsApiKeyInput = findViewById(R.id.elevenLabsApiKeyInput)
        elevenLabsVoiceIdInput = findViewById(R.id.elevenLabsVoiceIdInput)
        selectedProvider = settingsStore.load().provider
        tavilyApiKeyInput.setText(settingsStore.loadTavilyApiKey())
        elevenLabsApiKeyInput.setText(settingsStore.loadElevenLabsApiKey())
        elevenLabsVoiceIdInput.setText(settingsStore.loadElevenLabsVoiceId())

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        providerGroup.check(providerRadioIds.getValue(selectedProvider))
        showProfile(settingsStore.load(selectedProvider))
        providerGroup.setOnCheckedChangeListener { _, checkedId ->
            val nextProvider = providerRadioIds.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            if (nextProvider == selectedProvider) return@setOnCheckedChangeListener
            drafts[selectedProvider] = currentProfile()
            selectedProvider = nextProvider
            showProfile(drafts[nextProvider] ?: settingsStore.load(nextProvider))
        }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { saveSettings() }
    }

    private fun showProfile(settings: AiSettings) {
        modelInput.setText(settings.model)
        apiKeyInput.setText(settings.apiKey)
    }

    private fun currentProfile() = AiSettings(
        provider = selectedProvider,
        apiKey = apiKeyInput.text.toString(),
        model = modelInput.text.toString(),
        baseUrl = settingsStore.load(selectedProvider).baseUrl,
    )

    private fun saveSettings() {
        settingsStore.saveTavilyApiKey(tavilyApiKeyInput.text.toString())
        settingsStore.saveElevenLabsApiKey(elevenLabsApiKeyInput.text.toString())
        settingsStore.saveElevenLabsVoiceId(elevenLabsVoiceIdInput.text.toString())
        val active = currentProfile()
        if (active.model.isBlank()) {
            modelInput.error = "Enter a model name"
            return
        }
        drafts[selectedProvider] = active
        drafts.forEach { (provider, settings) ->
            settingsStore.save(settings, selectProvider = provider == selectedProvider)
        }
        setResult(RESULT_OK, Intent())
        Toast.makeText(this, "${selectedProvider.label} settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}