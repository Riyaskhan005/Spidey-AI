package com.riyas.SpideyAssistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                applyMaleVoice()
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeakStart()
            override fun onDone(utteranceId: String?) = onSpeakDone()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onSpeakDone()
        })
    }

    /**
     * Picks a male-sounding voice from the TTS engine if one is available.
     * Most engines (Google TTS included) encode gender in the voice name
     * (e.g. "en-us-x-sfg#male_1-local"), since the Voice API itself has no
     * dedicated gender field. Falls back to a lowered pitch on the default
     * voice if the engine doesn't expose an explicit male option.
     */
    private fun applyMaleVoice() {
        val engine = tts ?: return
        val availableVoices: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }

        val locale = Locale.getDefault()

        fun isMale(voice: Voice) =
            voice.name.contains("male", ignoreCase = true) &&
                !voice.name.contains("female", ignoreCase = true)

        val maleVoice = availableVoices.firstOrNull { it.locale.language == locale.language && isMale(it) }
            ?: availableVoices.firstOrNull { isMale(it) }

        if (maleVoice != null) {
            engine.voice = maleVoice
            engine.setPitch(1.0f)
        } else {
            // No explicit male voice found on this device/engine — approximate
            // a deeper voice by lowering pitch slightly on the default voice.
            engine.setPitch(0.82f)
        }
        engine.setSpeechRate(1.0f)
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