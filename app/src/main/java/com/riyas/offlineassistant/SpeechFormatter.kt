package com.riyas.SpideyAssistant

import java.util.regex.Pattern

/**
 * SpeechFormatter — Formats assistant text responses into concise, natural voice output for TTS.
 *
 * Requirements:
 * - If the content is short and simple: speak it fully and cleanly (strip markdown syntax).
 * - If the content is long or complex: avoid speaking the entire verbose text; instead speak
 *   a concise 1-2 sentence spoken summary / lead gist, omitting code blocks, markdown tables,
 *   and lengthy lists, while the full comprehensive response remains displayed in the chat UI.
 */
object SpeechFormatter {

    private const val MAX_SHORT_CHARS = 200
    private const val MAX_SUMMARY_CHARS = 180

    private val CODE_BLOCK_REGEX = Regex("```(?:[a-zA-Z0-9_-]+)?\n?([\\s\\S]*?)```", RegexOption.MULTILINE)
    private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")
    private val IMAGE_REGEX = Regex("!\\[[^\\]]*\\]\\([^)]+\\)")
    private val URL_REGEX = Regex("https?://\\S+")
    private val TABLE_ROW_REGEX = Regex("^\\s*\\|.*\\|\\s*$", RegexOption.MULTILINE)
    private val HEADER_REGEX = Regex("^#{1,6}\\s+", RegexOption.MULTILINE)
    private val BULLET_REGEX = Regex("^\\s*[-*•+]\\s+", RegexOption.MULTILINE)
    private val NUMBERED_LIST_REGEX = Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE)
    private val BOLD_ITALIC_REGEX = Regex("[*_~`#>]")

    /**
     * Converts a raw AI or assistant response into a concise, natural spoken utterance
     * suitable for Text-To-Speech (TTS).
     *
     * - Cleans all Markdown syntax, symbols, and URLs.
     * - Omits code blocks from speech and notes that code is displayed on screen.
     * - If response is short (<= MAX_SHORT_CHARS and <= 2 sentences), speaks in full.
     * - If response is long, extracts the key 1-2 lead sentences and appends a short
     *   on-screen reference ("I've displayed the full details on your screen.").
     */
    fun formatForSpeech(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""

        val hasCodeBlock = CODE_BLOCK_REGEX.containsMatchIn(trimmed)
        val textWithoutCode = trimmed.replace(CODE_BLOCK_REGEX, " ").trim()

        val cleaned = cleanMarkdown(textWithoutCode)

        // If the original response had code blocks
        if (hasCodeBlock) {
            if (cleaned.isBlank() || cleaned.length < 15) {
                return "Here is the code. I've displayed it on your screen."
            } else {
                val leadSummary = extractLeadSentences(cleaned, maxChars = 140)
                return "$leadSummary I've displayed the full code on your screen."
            }
        }

        if (cleaned.isBlank()) return ""

        // If response is already short and simple
        if (cleaned.length <= MAX_SHORT_CHARS) {
            val sentences = splitSentences(cleaned)
            if (sentences.size <= 2) {
                return cleaned
            }
        }

        // Long response -> extract concise 1-2 sentence lead summary
        val summary = extractLeadSentences(cleaned, maxChars = MAX_SUMMARY_CHARS)

        // If the summary already covers practically the whole cleaned text, don't append extra filler
        if (summary.length >= (cleaned.length * 0.85).toInt()) {
            return cleaned
        }

        return "$summary I've displayed the full details on your screen."
    }

    /**
     * Strips Markdown formatting, tables, URLs, emojis/special chars for clear TTS reading.
     */
    fun cleanMarkdown(text: String): String {
        var s = text
        // Remove markdown tables
        s = TABLE_ROW_REGEX.replace(s, " ")
        // Remove markdown images
        s = IMAGE_REGEX.replace(s, "")
        // Replace markdown links [text](url) with just text
        s = MARKDOWN_LINK_REGEX.replace(s, "$1")
        // Remove raw URLs
        s = URL_REGEX.replace(s, "")
        // Remove headers
        s = HEADER_REGEX.replace(s, "")
        // Clean bullet list markers
        s = BULLET_REGEX.replace(s, "")
        // Clean numbered list markers e.g. "1. " -> ""
        s = NUMBERED_LIST_REGEX.replace(s, "")
        // Remove formatting symbols like *, _, `, #, >, ~
        s = BOLD_ITALIC_REGEX.replace(s, "")
        // Normalize whitespace and line breaks
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    /**
     * Splits text into natural sentences.
     */
    private fun splitSentences(text: String): List<String> {
        val list = mutableListOf<String>()
        val pattern = Pattern.compile("([^.!?]+[.!?]+)|([^.!?]+$)")
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val sentence = matcher.group().trim()
            if (sentence.isNotEmpty()) {
                list.add(sentence)
            }
        }
        return list
    }

    /**
     * Extracts up to 2 initial sentences within [maxChars].
     */
    private fun extractLeadSentences(text: String, maxChars: Int): String {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return text

        val result = StringBuilder()
        var count = 0

        for (sentence in sentences) {
            if (count >= 2) break
            if (result.isNotEmpty() && (result.length + sentence.length + 1) > maxChars) {
                break
            }
            if (result.isNotEmpty()) result.append(" ")
            result.append(sentence)
            count++
        }

        val combined = result.toString().trim()
        if (combined.isNotEmpty()) {
            return combined
        }

        // If the first sentence alone is longer than maxChars, trim cleanly at word boundary
        val firstSentence = sentences.first()
        if (firstSentence.length > maxChars) {
            val cutIndex = firstSentence.lastIndexOf(' ', maxChars)
            return if (cutIndex > 0) {
                firstSentence.substring(0, cutIndex).trimEnd(',', ';', ':', ' ') + "."
            } else {
                firstSentence.take(maxChars).trimEnd(',', ';', ':', ' ') + "."
            }
        }

        return firstSentence
    }
}
