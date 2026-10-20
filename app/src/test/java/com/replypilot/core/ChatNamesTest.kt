package com.replypilot.core

import com.replypilot.core.accessibility.ChatNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNamesTest {
    private val full = "Ezenwanyi & Her Billionaires 💸🤑💰"

    @Test
    fun `a header shortened with an ellipsis matches the full name`() {
        assertTrue(ChatNames.matches("Ezenwanyi & Her Billionaires…", full))
        assertTrue(ChatNames.matches(full, "Ezenwanyi & Her Billionaires..."))
        assertEquals("Ezenwanyi & Her Billionaires", ChatNames.searchable("Ezenwanyi & Her Billionaires…"))
    }

    @Test
    fun `different chats and tiny prefixes don't match`() {
        assertFalse(ChatNames.matches("Family", "Family Business"))
        assertFalse(ChatNames.matches("Fa…", "Family Business"))
        assertTrue(ChatNames.matches("eze eze", "Eze EZE"))
    }
}
