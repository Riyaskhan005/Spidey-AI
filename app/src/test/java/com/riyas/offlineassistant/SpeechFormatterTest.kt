package com.riyas.SpideyAssistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechFormatterTest {

    @Test
    fun mixedTamilAndEnglishTextIsSplitWithoutLosingWordsOrPunctuation() {
        val segments = SpeechFormatter.splitByLanguage("Hello, வணக்கம் நண்பா! How are you?")

        assertEquals(
            listOf(
                SpeechFormatter.SpeechSegment("Hello,", false),
                SpeechFormatter.SpeechSegment(" வணக்கம் நண்பா!", true),
                SpeechFormatter.SpeechSegment(" How are you?", false),
            ),
            segments,
        )
    }

    @Test
    fun tamilOnlyTextStaysOneTamilSegment() {
        assertEquals(
            listOf(SpeechFormatter.SpeechSegment("வணக்கம் நண்பா!", true)),
            SpeechFormatter.splitByLanguage("வணக்கம் நண்பா!"),
        )
    }
}
