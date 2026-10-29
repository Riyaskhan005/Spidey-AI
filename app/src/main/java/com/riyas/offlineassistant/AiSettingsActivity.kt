package com.riyas.SpideyAssistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class AiSettingsActivity : AppCompatActivity() {

    private lateinit var settingsStore: AiSettingsStore
    private lateinit var providerGroup: RadioGroup
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var tavilyApiKeyInput: EditText
    private lateinit var remoteModelControls: View
    private lateinit var offlineModelControls: View
    private lateinit var offlineModelLabel: TextView
    private lateinit var offlineBackendGroup: RadioGroup
    private lateinit var temperatureInput: EditText
    private lateinit var topKInput: EditText
    private lateinit var topPInput: EditText
    private lateinit var contextTokensInput: EditText
    private lateinit var maxOutputTokensInput: EditText
    private lateinit var visualTokenBudgetSpinner: Spinner
    private lateinit var kokoroModelLabel: TextView
    private lateinit var kokoroVoiceSpinner: Spinner
    private val drafts = mutableMapOf<AiProvider, AiSettings>()
    private var selectedProvider = AiProvider.GROK
    private var offlineModelUri: Uri? = null
    private var kokoroModelUri: Uri? = null
    private val visualTokenBudgets = listOf<Int?>(null, 70, 140, 280, 560, 1120)

    private val providerRadioIds = mapOf(
        AiProvider.GEMINI to R.id.providerGemini,
        AiProvider.GROK to R.id.providerGrok,
        AiProvider.GROQ to R.id.providerGroq,
        AiProvider.OPENAI to R.id.providerOpenAi,
        AiProvider.OFFLINE to R.id.providerOffline,
    )

    private val offlineModelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> acceptModelFile(uri, isKokoro = false) }

    private val kokoroModelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> acceptModelFile(uri, isKokoro = true) }

    private val kokoroVoices = listOf(
        "Puck (American English)" to "am_puck",
        "Adam (American English)" to "am_adam",
        "Echo (American English)" to "am_echo",
        "Michael (American English)" to "am_michael",
        "Heart (American English)" to "af_heart",
        "Bella (American English)" to "af_bella",
        "Emma (British English)" to "bf_emma",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_settings)

        settingsStore = AiSettingsStore(this)
        providerGroup = findViewById(R.id.providerGroup)
        modelInput = findViewById(R.id.modelInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        tavilyApiKeyInput = findViewById(R.id.tavilyApiKeyInput)
        remoteModelControls = findViewById(R.id.remoteModelControls)
        offlineModelControls = findViewById(R.id.offlineModelControls)
        offlineModelLabel = findViewById(R.id.offlineModelLabel)
        offlineBackendGroup = findViewById(R.id.offlineBackendGroup)
        temperatureInput = findViewById(R.id.temperatureInput)
        topKInput = findViewById(R.id.topKInput)
        topPInput = findViewById(R.id.topPInput)
        contextTokensInput = findViewById(R.id.contextTokensInput)
        maxOutputTokensInput = findViewById(R.id.maxOutputTokensInput)
        visualTokenBudgetSpinner = findViewById(R.id.visualTokenBudgetSpinner)
        kokoroModelLabel = findViewById(R.id.kokoroModelLabel)
        kokoroVoiceSpinner = findViewById(R.id.kokoroVoiceSpinner)
        offlineModelUri = settingsStore.load(AiProvider.OFFLINE).modelUri
            .takeIf(String::isNotBlank)?.let(Uri::parse)
        kokoroModelUri = settingsStore.loadKokoroModel()
        selectedProvider = settingsStore.load().provider
        tavilyApiKeyInput.setText(settingsStore.loadTavilyApiKey())
        kokoroVoiceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            kokoroVoices.map { it.first },
        )
        visualTokenBudgetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Model default", "70", "140", "280", "560", "1120"),
        )
        val savedVoice = settingsStore.loadKokoroVoice()
        kokoroVoiceSpinner.setSelection(kokoroVoices.indexOfFirst { it.second == savedVoice }.coerceAtLeast(0))
        kokoroVoiceSpinner.visibility = if (kokoroModelUri == null) View.GONE else View.VISIBLE
        updateModelLabels()

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        providerGroup.check(providerRadioIds.getValue(selectedProvider))
        showProfile(settingsStore.load(selectedProvider))
        findViewById<MaterialButton>(R.id.browseOfflineModelButton).setOnClickListener {
            offlineModelPicker.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.browseKokoroModelButton).setOnClickListener {
            kokoroModelPicker.launch(arrayOf("*/*"))
        }
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
        remoteModelControls.visibility = if (settings.provider == AiProvider.OFFLINE) View.GONE else View.VISIBLE
        offlineModelControls.visibility = if (settings.provider == AiProvider.OFFLINE) View.VISIBLE else View.GONE
        modelInput.setText(settings.model)
        apiKeyInput.setText(settings.apiKey)
        offlineBackendGroup.check(
            if (settings.offlineBackend == OfflineBackend.GPU) R.id.backendGpu else R.id.backendCpu,
        )
        temperatureInput.setText(settings.temperature.toString())
        topKInput.setText(settings.topK.toString())
        topPInput.setText(settings.topP.toString())
        contextTokensInput.setText(settings.contextTokenBudget.toString())
        maxOutputTokensInput.setText(settings.maxOutputTokens.toString())
        visualTokenBudgetSpinner.setSelection(
            visualTokenBudgets.indexOf(settings.visualTokenBudget).coerceAtLeast(0),
        )
    }

    private fun currentProfile(): AiSettings {
        val saved = drafts[selectedProvider] ?: settingsStore.load(selectedProvider)
        return saved.copy(
            provider = selectedProvider,
            apiKey = if (selectedProvider == AiProvider.OFFLINE) "" else apiKeyInput.text.toString(),
            model = modelInput.text.toString(),
            baseUrl = settingsStore.load(selectedProvider).baseUrl,
            modelUri = offlineModelUri?.toString().orEmpty(),
            offlineBackend = if (offlineBackendGroup.checkedRadioButtonId == R.id.backendGpu) {
                OfflineBackend.GPU
            } else {
                OfflineBackend.CPU
            },
            temperature = temperatureInput.text.toString().toDoubleOrNull() ?: saved.temperature,
            topK = topKInput.text.toString().toIntOrNull() ?: saved.topK,
            topP = topPInput.text.toString().toDoubleOrNull() ?: saved.topP,
            contextTokenBudget = contextTokensInput.text.toString().toIntOrNull() ?: saved.contextTokenBudget,
            maxOutputTokens = maxOutputTokensInput.text.toString().toIntOrNull() ?: saved.maxOutputTokens,
            visualTokenBudget = visualTokenBudgets.getOrNull(visualTokenBudgetSpinner.selectedItemPosition),
        )
    }

    private fun saveSettings() {
        if (selectedProvider == AiProvider.OFFLINE && offlineModelUri == null) {
            Toast.makeText(this, "Browse and select an offline .litertlm model.", Toast.LENGTH_LONG).show()
            return
        }
        if (selectedProvider == AiProvider.OFFLINE && !validateOfflineSettings()) return
        val active = currentProfile()
        if (active.provider != AiProvider.OFFLINE && active.model.isBlank()) {
            modelInput.error = "Enter a model name"
            return
        }
        settingsStore.saveTavilyApiKey(tavilyApiKeyInput.text.toString())
        kokoroModelUri?.let(settingsStore::saveKokoroModel)
        settingsStore.saveKokoroVoice(kokoroVoices[kokoroVoiceSpinner.selectedItemPosition].second)
        drafts[selectedProvider] = active
        drafts.forEach { (provider, settings) ->
            settingsStore.save(settings, selectProvider = provider == selectedProvider)
        }
        setResult(RESULT_OK, Intent())
        Toast.makeText(this, "${selectedProvider.label} settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun validateOfflineSettings(): Boolean {
        listOf(
            temperatureInput,
            topKInput,
            topPInput,
            contextTokensInput,
            maxOutputTokensInput,
        ).forEach { it.error = null }

        val temperature = temperatureInput.text.toString().toDoubleOrNull()
        if (temperature == null || !temperature.isFinite() || temperature !in 0.0..2.0) {
            temperatureInput.error = "Enter a value from 0 to 2"
            return false
        }
        val topK = topKInput.text.toString().toIntOrNull()
        if (topK == null || topK !in 1..256) {
            topKInput.error = "Enter a value from 1 to 256"
            return false
        }
        val topP = topPInput.text.toString().toDoubleOrNull()
        if (topP == null || !topP.isFinite() || topP !in 0.0..1.0) {
            topPInput.error = "Enter a value from 0 to 1"
            return false
        }
        val contextTokens = contextTokensInput.text.toString().toIntOrNull()
        if (contextTokens == null || contextTokens !in 512..4096) {
            contextTokensInput.error = "Enter a value from 512 to 4096"
            return false
        }
        val maxOutputTokens = maxOutputTokensInput.text.toString().toIntOrNull()
        if (maxOutputTokens == null || maxOutputTokens !in 1..1024 || maxOutputTokens > contextTokens) {
            maxOutputTokensInput.error = "Enter a value up to 1024 and no greater than the context budget"
            return false
        }
        return true
    }

    private fun acceptModelFile(uri: Uri?, isKokoro: Boolean) {
        if (uri == null) return
        val fileName = displayName(uri)
        if (!fileName.endsWith(".litertlm", ignoreCase = true)) {
            Toast.makeText(this, "Select a .litertlm model file.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (error: SecurityException) {
            Toast.makeText(this, "Cannot keep access to this model file: ${error.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (isKokoro) {
            kokoroModelUri = uri
        } else {
            offlineModelUri = uri
            drafts[AiProvider.OFFLINE] = settingsStore.load(AiProvider.OFFLINE).copy(modelUri = uri.toString())
        }
        updateModelLabels()
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                uri.lastPathSegment.orEmpty()
            }
        } ?: uri.lastPathSegment.orEmpty()

    private fun updateModelLabels() {
        offlineModelLabel.text = offlineModelUri?.let(::displayName) ?: "No offline model selected"
        kokoroModelLabel.text = kokoroModelUri?.let { "Kokoro model: ${displayName(it)}" }
            ?: "Kokoro model: not selected"
        kokoroVoiceSpinner.visibility = if (kokoroModelUri == null) View.GONE else View.VISIBLE
    }
}