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

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micButton.isEnabled = granted
            if (!granted) {
                Toast.makeText(this, "Microphone permission is needed for voice mode.", Toast.LENGTH_LONG).show()
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
                runOnUiThread { runGrokInference(spokenText) }
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
                runGrokInference(prompt)
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

    // ── Grok inference ───────────────────────────────────────────────────────

    private fun runGrokInference(prompt: String) {
        sendButton.isEnabled = false
        micButton.isEnabled = false
        statusText.text = "Thinking…"

        chatAdapter.addMessage(ChatMessage(prompt, isUser = true))
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
                        chatAdapter.updateMessage(aiIndex, sb.toString())
                        chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    }
            } catch (e: Exception) {
                hadError = true
                val msg = e.message ?: "Unknown error"
                statusText.text = "❌ Error"
                chatAdapter.updateMessage(aiIndex, "Something went wrong:\n$msg")
                grokHistory.removeLastOrNull()
            }

            if (!hadError) {
                if (sb.isNotEmpty()) {
                    grokHistory.add(Pair("assistant", sb.toString()))
                    statusText.text = "Ready."
                    if (isVoiceMode) {
                        voiceManager.speak(sb.toString())
                    }
                } else {
                    grokHistory.removeLastOrNull()
                    statusText.text = "⚠️ No response"
                    chatAdapter.updateMessage(
                        aiIndex,
                        "No response received from Grok API.\n\nPossible reasons:\n• API key missing or invalid\n• Network issue\n• Model quota exceeded"
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
