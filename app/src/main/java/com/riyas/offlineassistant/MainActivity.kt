package com.riyas.SpideyAssistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // Grok client — lazily created, reused across calls
    private val grokClient: GrokClient by lazy { GrokClient() }

    // Conversation history for multi-turn Grok sessions: list of (role, content) pairs
    private val grokHistory = mutableListOf<Pair<String, String>>()

    private lateinit var statusText: TextView
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var waveformView: WaveformView
    private lateinit var voiceCaptionText: TextView

    private lateinit var modeToggle: MaterialButtonToggleGroup
    private lateinit var textModeButton: Button
    private lateinit var voiceModeButton: Button

    private lateinit var textInputBar: LinearLayout
    private lateinit var inputBox: EditText
    private lateinit var sendButton: ImageButton

    private lateinit var voiceInputBar: LinearLayout
    private lateinit var micButton: ImageButton
    private lateinit var voiceHintText: TextView

    private val chatAdapter = ChatAdapter()
    private var isVoiceMode = true
    private var isListening = false

    private lateinit var voiceManager: VoiceManager

    // Pending call-command prompt, held while we ask for Contacts/Call permission
    private var pendingCallPrompt: String? = null
    private var pendingLocationPrompt: String? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micButton.isEnabled = granted
            if (!granted) {
                Toast.makeText(this, "Microphone permission is needed for voice mode.", Toast.LENGTH_LONG).show()
            }
        }

    // Requests READ_CONTACTS + CALL_PHONE together, only when a call command is actually issued
    private val callPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.all { it }
            val prompt = pendingCallPrompt
            pendingCallPrompt = null
            if (granted && prompt != null) {
                handleCallCommand(prompt)
            } else if (!granted) {
                Toast.makeText(
                    this,
                    "Contacts and Call permissions are needed to place calls.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val locationCommandPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->

            val granted =
                results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            val prompt = pendingLocationPrompt
            pendingLocationPrompt = null

            if (granted && prompt != null) {
                processUserInput(prompt)
            } else {
                Toast.makeText(
                    this,
                    "Location permission is needed.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupChatList()
        setupModeToggle()
        setupTextInput()
        setupVoiceInput()

        voiceManager = VoiceManager(
            context = this,
            onSpeakStart = { runOnUiThread { waveformView.start() } },
            onSpeakDone = { runOnUiThread { waveformView.stop() } },
            onListenStart = {
                runOnUiThread {
                    isListening = true
                    micButton.setBackgroundResource(R.drawable.circle_mic_button_active)
                    voiceHintText.text = "Listening…"
                    waveformView.start()
                }
            },
            onListenResult = { spokenText ->
                runOnUiThread { processUserInput(spokenText) }
            },
            onListenError = { message ->
                runOnUiThread { voiceHintText.text = message }
            },
            onListenEnd = {
                runOnUiThread {
                    isListening = false
                    micButton.setBackgroundResource(R.drawable.circle_mic_button)
                    waveformView.stop()
                    if (voiceHintText.text == "Listening…") voiceHintText.text = "Tap to speak"
                }
            },
        )
        voiceManager.init()

        if (!grokClient.isConfigured()) {
            statusText.text = "⚠️ Grok API key not set. Edit local.properties → GROK_API_KEY"
            sendButton.isEnabled = false
            micButton.isEnabled = false
        } else {
            statusText.text = "Ready."
            sendButton.isEnabled = true
            ensureMicPermission()
        }
    }

    // ── View setup ───────────────────────────────────────────────────────────

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        waveformView = findViewById(R.id.waveformView)
        voiceCaptionText = findViewById(R.id.voiceCaptionText)

        modeToggle = findViewById(R.id.modeToggle)
        textModeButton = findViewById(R.id.textModeButton)
        voiceModeButton = findViewById(R.id.voiceModeButton)

        textInputBar = findViewById(R.id.textInputBar)
        inputBox = findViewById(R.id.inputBox)
        sendButton = findViewById(R.id.sendButton)

        voiceInputBar = findViewById(R.id.voiceInputBar)
        micButton = findViewById(R.id.micButton)
        voiceHintText = findViewById(R.id.voiceHintText)
    }

    private fun setupChatList() {
        chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecyclerView.adapter = chatAdapter
    }

    private fun setupModeToggle() {
        modeToggle.check(R.id.voiceModeButton)
        // Apply the initial visibility state directly, since the checked listener
        // only fires on an actual state change — and voiceModeButton is already
        // checked by default in the XML, so check() above won't trigger it.
        applyModeVisibility(isVoiceMode = true)

        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            applyModeVisibility(isVoiceMode = checkedId == R.id.voiceModeButton)
        }
    }

    private fun applyModeVisibility(isVoiceMode: Boolean) {
        this.isVoiceMode = isVoiceMode
        textInputBar.visibility = if (isVoiceMode) View.GONE else View.VISIBLE
        voiceInputBar.visibility = if (isVoiceMode) View.VISIBLE else View.GONE
        if (!isVoiceMode) {
            voiceManager.stopListening()
            voiceManager.stopSpeaking()
            waveformView.stop()
        }
    }

    private fun setupTextInput() {
        inputBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                sendButton.isEnabled = grokClient.isConfigured() && !s.isNullOrBlank()
            }
        })
        sendButton.setOnClickListener {
            val prompt = inputBox.text.toString().trim()
            if (prompt.isNotEmpty()) {
                inputBox.text.clear()
                processUserInput(prompt)
            }
        }
    }

    private fun setupVoiceInput() {
        micButton.setOnClickListener {
            if (isListening) {
                voiceManager.stopListening()
            } else {
                voiceManager.stopSpeaking()
                waveformView.stop()
                voiceManager.startListening()
            }
        }
    }

    private fun ensureMicPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        micButton.isEnabled = granted
        if (!granted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── Command routing ─────────────────────────────────────────────────────

    /**
     * Every user prompt (typed or spoken) comes through here first.
     * If it matches a local "call <name>" command, we handle it directly
     * against Contacts and never touch Grok. Otherwise it falls through
     * to normal AI inference.
     */
    private fun processUserInput(prompt: String) {
        val callTarget = ContactCallHandler.extractCallTarget(prompt)
        if (callTarget != null) {
            if (hasCallPermissions()) {
                handleCallCommand(prompt)
            } else {
                pendingCallPrompt = prompt
                callPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.CALL_PHONE
                    )
                )
            }
            return
        }
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (prompt.trim().equals("where am i", ignoreCase = true) ||
            prompt.trim().equals("what's my location", ignoreCase = true) ||
            prompt.trim().equals("what is my location", ignoreCase = true) ||
            prompt.trim().equals("current location", ignoreCase = true) ||
            prompt.trim().equals("my location", ignoreCase = true)
        ) {
            if (!hasLocationPermission) {
                pendingLocationPrompt = prompt
                locationCommandPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                return
            }
        }

        val command = CommandHandler.handle(
            this,
            prompt
        ) { locationResult ->
            runOnUiThread {
                respondLocally(locationResult)
            }
        }
        if (command != null) {
            handleLocalCommand(prompt, command)
            return
        }

        chatAdapter.addMessage(ChatMessage(prompt, isUser = true))
        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        runGrokInference(prompt)
    }

    private fun handleLocalCommand(prompt: String, command: CommandResult) {
        chatAdapter.addMessage(ChatMessage(prompt, isUser = true))
        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)

        when (command) {

            // Battery
            is CommandResult.Battery -> {
                respondLocally(command.phrase)
            }

            // Time
            is CommandResult.Time -> {
                respondLocally(command.phrase)
            }

            // Flashlight
            is CommandResult.Flashlight -> {
                respondLocally(command.phrase)
            }

            // Open app
            is CommandResult.OpenApp -> {
                respondLocally(command.ackPhrase)
            }
            
             // Weather
            is CommandResult.Weather -> {
                respondLocally(command.ackPhrase)

                if (command.location != null) {
                    // A city was named in the prompt (e.g. "weather in Chennai") —
                    // geocode it directly, no GPS permission required.
                    lifecycleScope.launch {
                        val result = WeatherHelper.fetchWeatherPhrase(this@MainActivity, command.location)
                        respondLocally(result)
                    }
                    return
                }

                val hasLocationPermission =
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                if (!hasLocationPermission) {
                    locationPermissionLauncher.launch(
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    return
                }

                lifecycleScope.launch {
                    val result = WeatherHelper.fetchWeatherPhrase(this@MainActivity)
                    respondLocally(result)
                }
            }

            // Web search
            is CommandResult.WebSearch -> {
                respondLocally(command.ackPhrase)

                runGrokInference(
                    command.query,
                    resultPrefix = "Here's what I found: "
                )
            }

            // Location
            is CommandResult.Location -> {
                respondLocally(command.ackPhrase)
            }
        }
    }

    /** Adds a Spidey chat bubble for a local (non-Grok) response and speaks it in voice mode. */
    private fun respondLocally(text: String) {
        chatAdapter.addMessage(ChatMessage(text, isUser = false))
        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        if (isVoiceMode) voiceManager.speak(text)
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Location permission is needed for weather.",
                    Toast.LENGTH_SHORT
                ).show()
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                val result = WeatherHelper.fetchWeatherPhrase(this@MainActivity)
                respondLocally(result)
            }
        }

    private fun hasCallPermissions(): Boolean {
        val contactsGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val callGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        return contactsGranted && callGranted
    }

    /** Looks up the contact and places the call — no Grok API call involved. */
    private fun handleCallCommand(prompt: String) {
        val target = ContactCallHandler.extractCallTarget(prompt) ?: return

        chatAdapter.addMessage(ChatMessage(prompt, isUser = true))

        val match = ContactCallHandler.findContact(this, target)
        if (match == null) {
            chatAdapter.addMessage(
                ChatMessage("I couldn't find a contact named \"$target\".", isUser = false)
            )
            chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            return
        }

        chatAdapter.addMessage(ChatMessage("Calling ${match.name}…", isUser = false))
        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
        ContactCallHandler.placeCall(this, match.number)
    }

    // ── Grok inference ───────────────────────────────────────────────────────

    private fun runGrokInference(prompt: String, resultPrefix: String = "") {
        sendButton.isEnabled = false
        micButton.isEnabled = false
        statusText.text = "Thinking…"

        val aiIndex = chatAdapter.addMessage(ChatMessage("", isUser = false, isStreaming = true))
        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)

        lifecycleScope.launch {
            grokHistory.add(Pair("user", prompt))

            val sb = StringBuilder()
            var hadError = false

            try {
                grokClient.streamChat(prompt, history = grokHistory.dropLast(1))
                    .collect { token ->
                        sb.append(token)
                        chatAdapter.updateMessage(aiIndex, resultPrefix + sb.toString(), isStreaming = true)
                        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    }
            } catch (e: Exception) {
                hadError = true
                val msg = e.message ?: "Unknown error"
                statusText.text = "❌ Error"
                chatAdapter.updateMessage(aiIndex, "Something went wrong:\n$msg", isStreaming = false)
                grokHistory.removeLastOrNull()
            }

            if (!hadError) {
                if (sb.isNotEmpty()) {
                    grokHistory.add(Pair("assistant", sb.toString()))
                    chatAdapter.updateMessage(aiIndex, resultPrefix + sb.toString(), isStreaming = false)
                    statusText.text = "Ready."
                    if (isVoiceMode) {
                        voiceManager.speak(resultPrefix + sb.toString())
                    }
                } else {
                    grokHistory.removeLastOrNull()
                    statusText.text = "⚠️ No response"
                    chatAdapter.updateMessage(
                        aiIndex,
                        "No response received from Grok API.\n\nPossible reasons:\n• API key missing or invalid\n• Network issue\n• Model quota exceeded",
                        isStreaming = false
                    )
                }
            }

            sendButton.isEnabled = grokClient.isConfigured()
            micButton.isEnabled = grokClient.isConfigured() &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        voiceManager.destroy()
        super.onDestroy()
    }
}