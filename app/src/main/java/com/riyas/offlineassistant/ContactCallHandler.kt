package com.riyas.SpideyAssistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract

/**
 * Local (non-AI) command handler for "call <name>" style requests.
 * If the user's prompt matches one of the recognized call-intent phrasings,
 * we skip Grok entirely, search the device Contacts, and place the call directly.
 */
object ContactCallHandler {

    data class ContactMatch(val name: String, val number: String)

    // Trailing filler words we strip off the captured name, e.g. "call John please" -> "John"
    private val FILLER_WORDS = Regex(
        """(?i)\b(please|now|right\s+now|for\s+me|quickly|asap)\b"""
    )

    // All recognized "call" prefix/phrasing patterns. Add more here as needed —
    // this is the single place that defines every trigger keyword.
    private val CALL_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)^(?:can|could|would)\s+you\s+(?:please\s+)?call\s+(.+)$"""),
        Regex("""(?i)^(?:please\s+)?call\s+up\s+(.+)$"""),
        Regex("""(?i)^(?:please\s+)?call\s+(.+)$"""),
        Regex("""(?i)^(?:please\s+)?dial\s+(.+)$"""),
        Regex("""(?i)^(?:please\s+)?ring\s+(.+)$"""),
        Regex("""(?i)^(?:please\s+)?phone\s+(?:call\s+to\s+)?(.+)$"""),
        Regex("""(?i)^(?:make|place|give)\s+a\s+call\s+to\s+(.+)$"""),
        Regex("""(?i)^(?:make|place|give)\s+(.+?)\s+a\s+call$"""),
        Regex("""(?i)^connect\s+me\s+(?:to|with)\s+(.+)$"""),
        Regex("""(?i)^i\s+(?:want|need)\s+to\s+call\s+(.+)$"""),
        Regex("""(?i)^get\s+(.+?)\s+on\s+(?:the\s+)?(?:phone|line)$"""),
    )

    /**
     * Returns the extracted contact name if [prompt] matches a call-intent
     * pattern, or null if this isn't a call command (falls through to Grok).
     */
    fun extractCallTarget(prompt: String): String? {
        val text = prompt.trim()
        for (pattern in CALL_PATTERNS) {
            val match = pattern.find(text) ?: continue
            var name = match.groupValues[1].trim()
            name = name.replace(FILLER_WORDS, "").trim()
            name = name.trim('.', '!', '?', ',', ' ')
            if (name.isNotBlank()) return name
        }
        return null
    }

    fun findContact(context: Context, name: String): ContactMatch? {
        val resolver = context.contentResolver

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val candidates = mutableListOf<ContactMatch>()

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->

            val nameIdx =
                cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

            val numberIdx =
                cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val contactName = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numberIdx) ?: continue

                candidates.add(
                    ContactMatch(
                        contactName,
                        number
                    )
                )
            }
        }

        if (candidates.isEmpty()) return null

        // Normalize user's requested name
        val target = normalizeName(name)

        if (target.isBlank()) return null

        // ---------------------------------------------------------
        // 1. Exact normalized full-name match
        // ---------------------------------------------------------
        candidates.firstOrNull {
            normalizeName(it.name) == target
        }?.let {
            return it
        }

        // ---------------------------------------------------------
        // 2. Exact normalized single-word match
        // ---------------------------------------------------------
        val exactWordMatches = candidates.filter { candidate ->

            val words = normalizeName(candidate.name)
                .split(Regex("\\s+"))

            words.any { word ->
                word == target
            }
        }

        if (exactWordMatches.size == 1) {
            return exactWordMatches.first()
        }

        // Multiple contacts have the same word.
        // Don't randomly call one.
        if (exactWordMatches.size > 1) {
            return null
        }

        // ---------------------------------------------------------
        // 3. Contact name STARTS WITH target
        // Example:
        // target = "amma"
        // contact = "amma mom"
        // ---------------------------------------------------------
        candidates.firstOrNull {

            val normalizedContact = normalizeName(it.name)

            normalizedContact == target ||
                    normalizedContact.startsWith("$target ")
        }?.let {
            return it
        }

        // ---------------------------------------------------------
        // 4. Contact name CONTAINS target
        // Example:
        // target = "amma"
        // contact = "my amma"
        // ---------------------------------------------------------
        candidates.firstOrNull {

            val normalizedContact = normalizeName(it.name)

            normalizedContact.contains(target)
        }?.let {
            return it
        }

        // ---------------------------------------------------------
        // 5. Reverse contains
        // Example:
        // target = "amma mom"
        // contact = "amma"
        // ---------------------------------------------------------
        candidates.firstOrNull {

            val normalizedContact = normalizeName(it.name)

            target.contains(normalizedContact)
        }?.let {
            return it
        }

        // ---------------------------------------------------------
        // 6. Fuzzy matching
        // ---------------------------------------------------------
        var best: ContactMatch? = null
        var bestScore = 0.0

        for (candidate in candidates) {

            val normalizedContact = normalizeName(candidate.name)

            if (normalizedContact.isBlank()) continue

            val words = normalizedContact.split(Regex("\\s+"))

            val score = (words + normalizedContact).maxOf {
                similarity(target, it)
            }

            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }

        return if (bestScore >= 0.72) {
            best
        } else {
            null
        }
    }

    /** Similarity in [0.0, 1.0] — 1.0 means identical, based on edit distance. */
    private fun similarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[a.length][b.length]
    }

    private fun normalizeName(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Places a direct phone call to [number] (requires CALL_PHONE permission granted). */
    fun placeCall(context: Context, number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        context.startActivity(intent)
    }
}