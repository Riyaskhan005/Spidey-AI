package com.riyas.SpideyAssistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Wraps voice input (SpeechRecognizer) and voice output (TextToSpeech) behind
 * a small callback-based API so MainActivity doesn't need to touch the
 * platform APIs directly.
 *
 * Requires android.permission.RECORD_AUDIO to be granted before [startListening]
 * is called.
 */
class VoiceManager(
    private val context: Context,
    private val onSpeakStart: () -> Unit,
    private val onSpeakDone: () -> Unit,
    private val onListenStart: () -> Unit,
    private val onListenResult: (String) -> Unit,
    private val onListenError: (String) -> Unit,
    private val onListenEnd: () -> Unit,
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    fun init() {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.language = Locale.getDefault()
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeakStart()
            override fun onDone(utteranceId: String?) = onSpeakDone()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onSpeakDone()
        })
    }

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        val id = "spidey_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onListenError("Speech recognition not available on this device.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onListenStart()
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() = onListenEnd()
                override fun onError(error: Int) {
                    onListenError(describeError(error))
                    onListenEnd()
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) onListenResult(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        recognizer?.destroy()
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
        SpeechRecognizer.ERROR_NETWORK -> "Network error during recognition."
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        else -> "Voice recognition error ($error)."
    }
}
