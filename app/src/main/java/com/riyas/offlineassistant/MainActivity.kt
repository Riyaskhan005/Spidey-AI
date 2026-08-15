package com.riyas.offlineassistant

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // Grok client — lazily created, reused across calls
    private val grokClient: GrokClient by lazy { GrokClient() }

    // Conversation history for multi-turn Grok sessions: list of (role, content) pairs
    private val grokHistory = mutableListOf<Pair<String, String>>()

    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var inputBox: EditText
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText   = findViewById(R.id.statusText)
        responseText = findViewById(R.id.responseText)
        inputBox     = findViewById(R.id.inputBox)
        sendButton   = findViewById(R.id.sendButton)

        if (!grokClient.isConfigured()) {
            statusText.text = "⚠️ Grok API key not set. Edit local.properties → GROK_API_KEY"
            sendButton.isEnabled = false
        } else {
            statusText.text = "🌐 Grok ready."
            sendButton.isEnabled = true
        }

        sendButton.setOnClickListener {
            val prompt = inputBox.text.toString().trim()
            if (prompt.isNotEmpty()) {
                runGrokInference(prompt)
            }
        }
    }

    // ── Grok inference ───────────────────────────────────────────────────────

    private fun runGrokInference(prompt: String) {
        sendButton.isEnabled = false
        responseText.text = ""
        statusText.text = "🌐 Generating..."

        lifecycleScope.launch {
            // Record user turn in history before the call
            grokHistory.add(Pair("user", prompt))

            val sb = StringBuilder()
            var hadError = false

            try {
                grokClient.streamChat(prompt, history = grokHistory.dropLast(1))
                    .collect { token ->
                        sb.append(token)
                        responseText.text = sb.toString()
                    }
            } catch (e: Exception) {
                hadError = true
                val msg = e.message ?: "Unknown error"
                statusText.text = "❌ Error: $msg"
                responseText.text = "Something went wrong:\n$msg"
                grokHistory.removeLastOrNull() // discard failed turn
                sendButton.isEnabled = true
                return@launch
            }

            if (sb.isNotEmpty()) {
                // Record assistant turn so next message has full context
                grokHistory.add(Pair("assistant", sb.toString()))
                statusText.text = "🌐 Grok ready."
            } else {
                // Flow completed but nothing was received — likely an API/key issue
                grokHistory.removeLastOrNull()
                statusText.text = "⚠️ No response. Check your GROK_API_KEY in local.properties."
                responseText.text = "No response received from Grok API.\n\nPossible reasons:\n• API key missing or invalid\n• Network issue\n• Model quota exceeded"
            }

            sendButton.isEnabled = true
        }
    }
}
