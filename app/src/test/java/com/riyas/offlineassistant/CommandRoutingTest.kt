package com.riyas.SpideyAssistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRoutingTest {

    @Test
    fun currentLocationPhrasesStayLocalLocationRequests() {
        listOf(
            "Where am I?",
            "Where am I living now?",
            "Where am I right now",
            "What is my current location?",
            "What's my location"
        ).forEach { phrase ->
            assertTrue("Expected location intent for: $phrase", CommandHandler.isCurrentLocationRequest(phrase))
        }

        assertFalse(CommandHandler.isCurrentLocationRequest("Open Google Maps"))
        assertFalse(CommandHandler.isCurrentLocationRequest("Where should I go today?"))
    }

    @Test
    fun callNameExtractionRemovesSpeakerInstruction() {
        assertEquals(
            "Jasim",
            ContactCallHandler.extractCallTarget("Can you call Jasim, put it on the speaker?")
        )
        assertEquals("Asim", ContactCallHandler.extractCallTarget("Call Asim"))
    }

    @Test
    fun deviceIntentMapsSupportedActionsToExistingCommandPhrases() {
        assertEquals("where am i", DeviceCommandIntent("LOCATION", "", 0.99).toCommandText())
        assertEquals("open Camera", DeviceCommandIntent("OPEN_APP", "Camera", 0.99).toCommandText())
        assertEquals("search for local weather", DeviceCommandIntent("SEARCH", "local weather", 0.99).toCommandText())
    }
}
