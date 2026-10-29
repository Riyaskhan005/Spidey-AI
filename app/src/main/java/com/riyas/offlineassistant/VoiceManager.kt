package com.riyas.SpideyAssistant

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Wraps voice input and offline voice output behind
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
    private val onSpeakError: (String) -> Unit,
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
    private val kokoroTtsRunner = KokoroTtsRunner(context)
    private var kokoroModelUri: Uri? = null
    private var kokoroVoice = "am_puck"
    private var speechJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFile: File? = null
    private var speechGeneration = 0
    private var kokoroFallbackNotified = false

    fun configureKokoro(modelUri: Uri?, voice: String) {
        kokoroModelUri = modelUri
        kokoroVoice = voice
    }

    fun init() {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeakStart()
            override fun onDone(utteranceId: String?) {
                if (speechSegments.isEmpty() || utteranceId?.startsWith(speechId) != true) return
                speechSegmentIndex++
                if (speechSegmentIndex < speechSegments.size) {
                    speakNextSegment()
                } else {
                    finishSpeech(speechGeneration)
                }
            }
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (speechSegments.isNotEmpty() && utteranceId?.startsWith(speechId) == true) {
                    finishSpeech(speechGeneration, "The installed Tamil voice could not speak.")
                }
            }
        })
    }

    private fun applyLocalTamilVoice(): Boolean {
        val engine = tts ?: return false
        val availableVoices: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }

        fun isMale(voice: Voice) =
            voice.name.contains("male", ignoreCase = true) &&
                !voice.name.contains("female", ignoreCase = true)

        val selectedVoice = availableVoices
            .filter { it.locale.language == "ta" && !it.isNetworkConnectionRequired }
            .sortedWith(
                compareByDescending<Voice> { isMale(it) }
                    .thenByDescending { it.quality }
            )
            .firstOrNull()

        selectedVoice ?: return false
        engine.voice = selectedVoice
        engine.setPitch(if (isMale(selectedVoice)) 1.0f else 0.88f)
        engine.setSpeechRate(0.92f)
        return true
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
        speechSegments = SpeechFormatter.splitByLanguage(spokenText)
        speechSegmentIndex = 0
        speechId = "spidey_${System.currentTimeMillis()}_"
        if (speechSegments.isEmpty()) {
            finishSpeech(speechGeneration)
            return
        }
        speakNextSegment()
    }

    private fun releaseAudioPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        audioFile?.delete()
        audioFile = null
    }

    private fun speakNextSegment() {
        val segment = speechSegments.getOrNull(speechSegmentIndex) ?: return
        val generation = speechGeneration
        if (segment.isTamil) {
            if (!ttsReady) {
                finishSpeech(generation, "An installed offline Tamil text-to-speech voice is required.")
                return
            }
            if (!applyLocalTamilVoice()) {
                finishSpeech(generation, "Install an offline Tamil text-to-speech voice to speak Tamil.")
                return
            }
            val result = tts?.speak(
                segment.text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "$speechId$speechSegmentIndex",
            ) ?: TextToSpeech.ERROR
            if (result == TextToSpeech.ERROR) {
                finishSpeech(generation, "The installed Tamil voice could not start speaking.")
            }
            return
        }

        if (!kokoroTtsRunner.isAvailable()) {
            notifyKokoroFallback()
            speakWithAndroidEnglish(segment.text, generation)
            return
        }

        speechJob = scope.launch {
            try {
                val file = kokoroTtsRunner.synthesize(
                    segment.text,
                    generation,
                    speechSegmentIndex,
                    kokoroModelUri,
                    kokoroVoice,
                )
                if (generation == speechGeneration) {
                    playAudioFile(file, generation)
                } else {
                    file.delete()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == speechGeneration) {
                    notifyKokoroFallback()
                    speakWithAndroidEnglish(segment.text, generation)
                }
            }
        }
    }

    private fun notifyKokoroFallback() {
        if (kokoroFallbackNotified) return
        kokoroFallbackNotified = true
        onSpeakError("Kokoro runtime is missing; using the Android offline English voice.")
    }

    private fun speakWithAndroidEnglish(text: String, generation: Int) {
        val engine = tts
        if (!ttsReady || engine == null) {
            finishSpeech(generation, "Android text-to-speech is not ready.")
            return
        }
        val offlineVoice = engine.voices
            ?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            ?.maxByOrNull { it.quality }
        if (offlineVoice == null) {
            finishSpeech(generation, "Install an offline English voice in Android text-to-speech settings.")
            return
        }
        engine.voice = offlineVoice
        engine.setSpeechRate(0.92f)
        val result = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "$speechId$speechSegmentIndex",
        )
        if (result == TextToSpeech.ERROR) {
            finishSpeech(generation, "The installed offline English voice could not start speaking.")
        }
    }

    private fun playAudioFile(file: File, generation: Int) {
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
                    speechSegmentIndex++
                    if (speechSegmentIndex < speechSegments.size) {
                        speakNextSegment()
                    } else {
                        finishSpeech(generation)
                    }
                }
            }
            player.setOnErrorListener { _, _, _ ->
                if (generation == speechGeneration) {
                    finishSpeech(generation, "Kokoro audio could not be played.")
                }
                true
            }
            player.prepareAsync()
        } catch (error: Exception) {
            finishSpeech(generation, "Kokoro audio could not be played: ${error.message ?: "unknown error"}")
        }
    }

    private fun finishSpeech(generation: Int, errorMessage: String? = null) {
        if (generation != speechGeneration) return
        releaseAudioPlayer()
        speechSegments = emptyList()
        speechSegmentIndex = 0
        speechJob = null
        if (errorMessage != null) onSpeakError(errorMessage)
        onSpeakDone()
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
        speechJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        recognizer?.destroy()
        releaseAudioPlayer()
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