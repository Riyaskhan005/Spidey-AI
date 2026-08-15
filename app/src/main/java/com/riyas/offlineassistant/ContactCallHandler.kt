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

    /**
     * Looks up [name] in the device Contacts. Prefers an exact (case-insensitive)
     * display-name match; falls back to the first partial match.
     */
    fun findContact(context: Context, name: String): ContactMatch? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        var exactMatch: ContactMatch? = null
        var partialMatch: ContactMatch? = null

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val contactName = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numberIdx) ?: continue
                if (contactName.equals(name, ignoreCase = true)) {
                    exactMatch = ContactMatch(contactName, number)
                    return@use
                } else if (partialMatch == null) {
                    partialMatch = ContactMatch(contactName, number)
                }
            }
        }

        return exactMatch ?: partialMatch
    }

    /** Places a direct phone call to [number] (requires CALL_PHONE permission granted). */
    fun placeCall(context: Context, number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        context.startActivity(intent)
    }
}