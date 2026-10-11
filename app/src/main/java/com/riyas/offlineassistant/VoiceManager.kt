package com.riyas.SpideyAssistant

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private val scope: LifecycleCoroutineScope,
    private val onSpeakStart: () -> Unit,
    private val onSpeakDone: () -> Unit,
    private val onListenStart: () -> Unit,
    private val onListenLevel: (Float) -> Unit,
    private val onListenResult: (String) -> Unit,
    private val onListenError: (String) -> Unit,
    private val onListenEnd: () -> Unit,
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var speechSegments = emptyList<SpeechFormatter.SpeechSegment>()
    private var speechSegmentIndex = 0
    private var speechId = ""
    private val elevenLabsClient = ElevenLabsClient()
    private var elevenLabsApiKey = ""
    private var elevenLabsVoiceId = "JBFqnCBsd6RMkjVDRZzb"
    private var speechJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFile: File? = null
    private var speechGeneration = 0

    fun configureElevenLabs(apiKey: String, voiceId: String) {
        elevenLabsApiKey = apiKey.trim()
        elevenLabsVoiceId = voiceId.trim()
    }

    fun init() {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                applyMaleVoice(Locale.forLanguageTag("en-IN"))
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeakStart()
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith(speechId) != true) return
                speechSegmentIndex++
                if (speechSegmentIndex < speechSegments.size) {
                    speakNextSegment()
                } else {
                    speechSegments = emptyList()
                    onSpeakDone()
                }
            }
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId?.startsWith(speechId) == true) {
                    speechSegments = emptyList()
                    onSpeakDone()
                }
            }
        })
    }

    /**
     * Picks a male-sounding voice from the TTS engine if one is available.
     * Most engines (Google TTS included) encode gender in the voice name
     * (e.g. "en-us-x-sfg#male_1-local"), since the Voice API itself has no
     * dedicated gender field. Falls back to a lowered pitch on the default
     * voice if the engine doesn't expose an explicit male option.
     */
    private fun applyMaleVoice(locale: Locale) {
        val engine = tts ?: return
        val availableVoices: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }

        fun isMale(voice: Voice) =
            voice.name.contains("male", ignoreCase = true) &&
                !voice.name.contains("female", ignoreCase = true)

        val selectedVoice = availableVoices
            .filter { it.locale.language == locale.language }
            .sortedWith(
                compareByDescending<Voice> { isMale(it) }
                    .thenByDescending { it.locale.country == locale.country }
                    .thenByDescending { it.quality }
                    .thenBy { it.isNetworkConnectionRequired }
            )
            .firstOrNull()

        if (selectedVoice != null) {
            engine.voice = selectedVoice
            engine.setPitch(if (isMale(selectedVoice)) 1.0f else 0.88f)
        } else {
            engine.language = locale
            engine.setPitch(0.88f)
        }
        engine.setSpeechRate(0.92f)
    }

    fun speak(text: String, summarizeLong: Boolean = true) {
        if (text.isBlank()) return
        val spokenText = if (summarizeLong) {
            SpeechFormatter.formatForSpeech(text)
        } else {
            SpeechFormatter.cleanMarkdown(text)
        }
        if (spokenText.isBlank()) return
        stopSpeaking()
        val generation = speechGeneration
        if (elevenLabsApiKey.isNotBlank() && elevenLabsVoiceId.isNotBlank()) {
            speechJob = scope.launch {
                try {
                    val audio = elevenLabsClient.synthesize(
                        spokenText,
                        elevenLabsApiKey,
                        elevenLabsVoiceId,
                    )
                    if (generation == speechGeneration) playElevenLabsAudio(audio, generation, spokenText)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (generation == speechGeneration) speakWithDeviceTts(spokenText)
                }
            }
            return
        }
        speakWithDeviceTts(spokenText)
    }

    private fun speakWithDeviceTts(text: String) {
        if (!ttsReady) {
            onSpeakDone()
            return
        }
        speechSegments = SpeechFormatter.splitByLanguage(text)
        speechSegmentIndex = 0
        speechId = "spidey_${System.currentTimeMillis()}_"
        speakNextSegment()
    }

    private suspend fun playElevenLabsAudio(audio: ByteArray, generation: Int, fallbackText: String) {
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "elevenlabs_$generation.mp3").apply { writeBytes(audio) }
        }
        if (generation != speechGeneration) {
            file.delete()
            return
        }
        audioFile = file
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                if (generation == speechGeneration) {
                    onSpeakStart()
                    it.start()
                }
            }
            player.setOnCompletionListener {
                if (generation == speechGeneration) {
                    releaseAudioPlayer()
                    onSpeakDone()
                }
            }
            player.setOnErrorListener { _, _, _ ->
                if (generation == speechGeneration) {
                    releaseAudioPlayer()
                    speakWithDeviceTts(fallbackText)
                }
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            releaseAudioPlayer()
            speakWithDeviceTts(fallbackText)
        }
    }

    private fun releaseAudioPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        audioFile?.delete()
        audioFile = null
    }

    private fun speakNextSegment() {
        val segment = speechSegments.getOrNull(speechSegmentIndex) ?: return
        val locale = if (segment.isTamil) Locale.forLanguageTag("ta-IN") else Locale.forLanguageTag("en-IN")
        applyMaleVoice(locale)
        tts?.speak(
            segment.text,
            if (speechSegmentIndex == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "$speechId$speechSegmentIndex",
        )
    }

    fun stopSpeaking() {
        speechGeneration++
        speechJob?.cancel()
        speechJob = null
        mediaPlayer?.setOnPreparedListener(null)
        mediaPlayer?.setOnCompletionListener(null)
        mediaPlayer?.setOnErrorListener(null)
        releaseAudioPlayer()
        speechSegments = emptyList()
        speechSegmentIndex = 0
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
                override fun onRmsChanged(rmsdB: Float) = onListenLevel(rmsdB)
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
            val languageTag = if (Locale.getDefault().language == "ta") "ta-IN" else "en-IN"
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
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