package com.riyas.SpideyAssistant

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class KokoroTtsRunner(private val context: Context) {
    fun isAvailable(): Boolean =
        File(context.applicationInfo.nativeLibraryDir, RUNNER_NAME).isFile

    suspend fun synthesize(
        text: String,
        generation: Int,
        segmentIndex: Int,
        modelUri: Uri?,
        voice: String,
    ): File =
        withContext(Dispatchers.IO) {
            val selectedModelUri = modelUri
                ?: throw IllegalStateException("Select a Kokoro model in AI settings.")
            val runner = File(context.applicationInfo.nativeLibraryDir, RUNNER_NAME)
            check(runner.isFile) {
                "Kokoro speech is unavailable because its native runtime is not included in this APK."
            }

            val model = ensureModel(selectedModelUri)
            val cacheDir = File(context.cacheDir, "kokoro-cache").apply { mkdirs() }
            val output = File(context.cacheDir, "kokoro_${generation}_$segmentIndex.wav")
            val log = File(context.cacheDir, "kokoro_${generation}_$segmentIndex.log")
            output.delete()
            log.delete()

            val processBuilder = ProcessBuilder(
                runner.absolutePath,
                "--model_name=kokoro",
                "--model_path=${model.absolutePath}",
                "--backend=gpu",
                "--cache_dir=${cacheDir.absolutePath}",
                "--language=${if (voice.startsWith("b")) "en-GB" else "en-US"}",
                "--voice=$voice",
                "--text=$text",
                "--output_path=${output.absolutePath}",
            )
            val inheritedLibraryPath = System.getenv("LD_LIBRARY_PATH").orEmpty()
            processBuilder.environment()["LD_LIBRARY_PATH"] = listOf(
                runner.parentFile?.absolutePath.orEmpty(),
                inheritedLibraryPath,
            ).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
            val process = processBuilder
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()

            var keepOutput = false
            try {
                if (!runInterruptible { process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS) }) {
                    process.destroyForcibly()
                    throw IllegalStateException("Kokoro speech generation timed out.")
                }
                if (process.exitValue() != 0) {
                    val details = log.takeIf(File::isFile)?.readText()?.takeLast(MAX_ERROR_LENGTH).orEmpty()
                    throw IllegalStateException(
                        details.ifBlank { "Kokoro runner exited with code ${process.exitValue()}." }
                    )
                }
                check(output.isFile && output.length() > WAV_HEADER_SIZE) {
                    "Kokoro did not produce an audio file."
                }
                keepOutput = true
                output
            } catch (error: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                throw error
            } finally {
                if (process.isAlive) process.destroyForcibly()
                log.delete()
                if (!keepOutput) output.delete()
            }
        }

    private fun ensureModel(uri: Uri): File {
        val modelName = modelFileName("kokoro", uri)
        val model = File(context.noBackupFilesDir, modelName)
        if (model.isFile && model.length() > 0) return model

        val partial = File(context.noBackupFilesDir, "$modelName.part")
        partial.delete()
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            partial.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Unable to read the selected Kokoro model.")
        check(partial.isFile && partial.length() > 0) { "The selected Kokoro model is empty." }
        if (model.exists() && !model.delete()) {
            throw IllegalStateException("Unable to replace the previous Kokoro model.")
        }
        check(partial.renameTo(model)) { "Unable to install the Kokoro model." }
        return model
    }

    private companion object {
        const val RUNNER_NAME = "libkokoro_runner.so"
        const val PROCESS_TIMEOUT_SECONDS = 120L
        const val MAX_ERROR_LENGTH = 2_000
        const val WAV_HEADER_SIZE = 44L
    }
}
