package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class InputHistoryTest {
    private fun tempDir() = Files.createTempDirectory("input-history-test").toFile()

    @Test
    fun historiesAreIsolatedPerConversationKey() {
        val dir = tempDir()
        val first = InputHistory(dir, "-srv/aaaa")
        first.add("draft one")

        // A different conversation key starts empty.
        val other = InputHistory(dir, "-srv/bbbb")
        assertNull(other.peek(-1))

        // Reloading the same key restores its drafts.
        val reloaded = InputHistory(dir, "-srv/aaaa")
        assertEquals("draft one", reloaded.peek(-1))
    }

    @Test
    fun addAndBrowseSequence() {
        val history = InputHistory(tempDir(), "k")
        history.add("first")
        history.add("second")

        assertEquals("second", history.peek(-1)) // older-walk from empty → newest
        assertEquals("first", history.peek(-1))  // older again → oldest
        assertEquals("first", history.peek(-1))  // clamped at the oldest end
        assertEquals("second", history.peek(1))  // newer walks back
        assertNull(history.peek(1))              // back at the empty entry
    }

    @Test
    fun consecutiveDuplicatesAreDeduplicated() {
        val history = InputHistory(tempDir(), "k")
        history.add("same")
        history.add("same")

        assertEquals(1, history.size)
    }

    @Test
    fun suggestionSlotIsNotPersisted() {
        val dir = tempDir()
        val history = InputHistory(dir, "k")
        history.add("draft")
        history.setSuggestion("suggested")

        assertEquals("suggested", history.jumpToSuggestion().let { history.suggestion() })
        val reloaded = InputHistory(dir, "k")
        assertNull(reloaded.suggestion())
    }
}
