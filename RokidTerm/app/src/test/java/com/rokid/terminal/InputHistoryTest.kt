package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
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

    @Test
    fun migrateMovesPlaceholderKeyHistoryToRealKey() {
        val dir = tempDir()
        InputHistory(dir, "-srv/placeholder-id").add("draft under placeholder")

        InputHistory.migrate(dir, "-srv/placeholder-id", "-srv/real-id")

        // The real key now owns the draft; the placeholder file is gone.
        assertEquals("draft under placeholder", InputHistory(dir, "-srv/real-id").peek(-1))
        assertEquals(null, InputHistory(dir, "-srv/placeholder-id").peek(-1))
        assertNull(File(dir, "input_history_-srv_placeholder-id.txt").let { if (it.exists()) it.length() else null })
    }

    @Test
    fun migrateNeverClobbersExistingTarget() {
        val dir = tempDir()
        InputHistory(dir, "-srv/from").add("old")
        val target = InputHistory(dir, "-srv/to")
        target.add("existing")
        target.add("keep me")

        InputHistory.migrate(dir, "-srv/from", "-srv/to")

        val after = InputHistory(dir, "-srv/to")
        assertEquals("keep me", after.peek(-1))
        assertEquals("existing", after.peek(-1))
        // The source file survives for manual recovery.
        assertEquals("old", InputHistory(dir, "-srv/from").peek(-1))
    }

    @Test
    fun migrateIsNoOpForSameKeyOrMissingSource() {
        val dir = tempDir()
        InputHistory(dir, "-srv/k").add("draft")

        InputHistory.migrate(dir, "-srv/k", "-srv/k")
        InputHistory.migrate(dir, "-srv/missing", "-srv/k")

        assertEquals("draft", InputHistory(dir, "-srv/k").peek(-1))
    }

    @Test
    fun deleteFileRemovesOnlyTheTargetKey() {
        val dir = tempDir()
        InputHistory(dir, "-srv/doomed").add("delete me")
        InputHistory(dir, "-srv/keep").add("keep me")

        InputHistory.deleteFile(dir, "-srv/doomed")

        assertNull(InputHistory(dir, "-srv/doomed").peek(-1))
        assertEquals("keep me", InputHistory(dir, "-srv/keep").peek(-1))
    }
}
