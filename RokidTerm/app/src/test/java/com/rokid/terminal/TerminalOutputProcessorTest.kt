package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputProcessorTest {
    @Test
    fun convertsRemoteOutputIntoRenderFrame() {
        val processor = TerminalOutputProcessor(columns = 8, rows = 2)

        val frame = processor.consume("hello")

        assertEquals("hello   ", rowText(frame, 0))
    }

    @Test
    fun publishedFramesRemainImmutableAfterLaterOutput() {
        val processor = TerminalOutputProcessor(columns = 4, rows = 2)
        val first = processor.consume("ab")

        val second = processor.consume("cd")

        assertEquals("ab  ", rowText(first, 0))
        assertEquals("abcd", rowText(second, 0))
    }

    @Test
    fun resetPublishesBlankFrame() {
        val processor = TerminalOutputProcessor(columns = 4, rows = 2)
        processor.consume("data")

        val reset = processor.reset()

        assertEquals("    ", rowText(reset, 0))
        assertEquals("    ", rowText(reset, 1))
    }

    @Test
    fun resizeChangesGridWithoutReflowingPublishedFrame() {
        val processor = TerminalOutputProcessor(columns = 4, rows = 2)
        val beforeResize = processor.consume("abcd")

        val resized = processor.resize(columns = 6, rows = 3)

        assertEquals(4, beforeResize.columns)
        assertEquals(2, beforeResize.rows)
        assertEquals("abcd", rowText(beforeResize, 0))
        assertEquals(6, resized.columns)
        assertEquals(3, resized.rows)
        assertEquals("abcd  ", rowText(resized, 0))
        assertTrue(resized.revision > beforeResize.revision)
    }

    @Test
    fun sameSizeResizeDoesNotAdvanceRevision() {
        val processor = TerminalOutputProcessor(columns = 4, rows = 2)
        val before = processor.consume("x")

        val sameSize = processor.resize(columns = 4, rows = 2)

        assertEquals(before.revision, sameSize.revision)
    }

    @Test
    fun olderAndNewerNavigationClampAndRestoreLiveCursor() {
        val processor = processorWithOneHistoryRow()

        val oldest = processor.scrollOlder(rows = 99)
        assertEquals(1, oldest.scrollOffsetRows)
        assertEquals(1, oldest.scrollbackRows)
        assertFalse(oldest.cursor.visible)
        // Browsing = scrollback + live screen appended below (the trimmed
        // import never duplicates the screen's turns — 2026-08-08).
        assertEquals("1111", rowText(oldest, 0))
        assertEquals("2222", rowText(oldest, 1))
        assertEquals("3333", rowText(oldest, 2))

        val stillOldest = processor.scrollOlder(rows = 99)
        assertEquals(oldest.revision, stillOldest.revision)

        val live = processor.scrollNewer(rows = 99)
        assertEquals(0, live.scrollOffsetRows)
        assertTrue(live.cursor.visible)
        assertEquals("2222", rowText(live, 0))
        assertEquals("3333", rowText(live, 1))
        assertEquals("    ", rowText(live, 2))
    }

    @Test
    fun remoteOutputWhileHistoricalDoesNotSnapToLive() {
        val processor = processorWithOneHistoryRow()
        processor.scrollOlder(rows = 1)

        val typedAtLiveBottom = processor.consume("4444")

        assertEquals(1, typedAtLiveBottom.scrollOffsetRows)
        assertTrue(typedAtLiveBottom.hasNewOutput)
        assertEquals("1111", rowText(typedAtLiveBottom, 0))
        assertEquals("2222", rowText(typedAtLiveBottom, 1))
        assertEquals("3333", rowText(typedAtLiveBottom, 2))

        val emptyChunk = processor.consume("")
        assertTrue(emptyChunk.hasNewOutput)
        assertEquals(1, emptyChunk.scrollOffsetRows)
    }

    @Test
    fun remoteOutputWhileHistoricalKeepsViewportAnchored() {
        // Real scrolls no longer create history (login noise / redraw
        // artifacts); the history row stays pinned at the top of the view.
        val processor = processorWithOneHistoryRow()
        processor.scrollOlder(rows = 1)

        val afterRemoteScroll = processor.consume("4444\r\n")

        assertEquals(1, afterRemoteScroll.scrollOffsetRows)
        assertEquals(1, afterRemoteScroll.scrollbackRows)
        assertEquals("1111", rowText(afterRemoteScroll, 0))
    }

    @Test
    fun boundedHistoryStillPreservesViewportWhenOldRowsAreEvicted() {
        // Import with a max bound evicts the oldest rows; the viewport is
        // clamped to the surviving history.
        val processor = TerminalOutputProcessor(columns = 4, rows = 3, maxScrollbackRows = 2)
        processor.importScrollbackText(listOf("1111", "2222", "3333"))
        val before = processor.scrollOlder(rows = 99)
        assertEquals(2, before.scrollbackRows)
        assertEquals(2, before.scrollOffsetRows)
        assertEquals("2222", rowText(before, 0))
        assertEquals("3333", rowText(before, 1))

        val after = processor.consume("5555\r\n")

        assertEquals(2, after.scrollOffsetRows)
        assertEquals(2, after.scrollbackRows)
        assertEquals("2222", rowText(after, 0))
        assertEquals("3333", rowText(after, 1))
    }

    @Test
    fun returnToLiveResetAndResizeClearHistoricalState() {
        val processor = processorWithOneHistoryRow()
        processor.scrollOlder(rows = 1)
        processor.consume("4444")

        val live = processor.returnToLive()
        assertEquals(0, live.scrollOffsetRows)
        assertFalse(live.hasNewOutput)

        processor.scrollOlder(rows = 1)
        val reset = processor.reset()
        assertEquals(0, reset.scrollOffsetRows)
        assertEquals(0, reset.scrollbackRows)

        processor.importScrollbackText(listOf("1111"))
        processor.scrollOlder(rows = 1)
        val resized = processor.resize(columns = 5, rows = 4)
        assertEquals(0, resized.scrollOffsetRows)
        assertEquals(0, resized.scrollbackRows)
    }

    private fun processorWithOneHistoryRow(): TerminalOutputProcessor =
        TerminalOutputProcessor(columns = 4, rows = 3).also {
            fillRows(it, "1111", "2222", "3333")
            // The live screen keeps the post-scroll state ("2222" on top);
            // history is supplied by the import, not the scroll.
            it.consume("\u001b[3;1H\r\n")
            it.importScrollbackText(listOf("1111"))
            assertEquals(1, it.scrollbackRows)
        }

    private fun fillRows(processor: TerminalOutputProcessor, vararg values: String) {
        values.forEachIndexed { index, value ->
            processor.consume("\u001b[${index + 1};1H$value")
        }
    }

    private fun rowText(frame: TerminalFrame, row: Int): String = frame.cells[row]
        .joinToString("") { if (it.continuation) "·" else it.text }

    @Test
    fun clearScrollbackEmptiesHistoryAndReturnsToLive() {
        // Conversation switches must not leak the previous conversation's
        // rows: clearScrollback empties the in-memory history unconditionally
        // (importScrollbackText alone is a no-op for empty rows or while the
        // alternate screen is active — regression 2026-08-08).
        val processor = TerminalOutputProcessor(columns = 4, rows = 3)
        processor.importScrollbackText(listOf("1111", "2222", "3333"))
        processor.scrollOlder(rows = 99)
        assertEquals(3, processor.scrollbackRows)

        processor.clearScrollback()

        assertEquals(0, processor.scrollbackRows)
        assertEquals(0, processor.scrollOffset)
    }
}
