package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude Code re-renders its TUI with absolute cursor positioning (no scroll
 * escapes), so dialogue rows must be captured by frame-content comparison.
 *
 * The byte-stream structure below mirrors the real 54x36 tmux/Claude redraw
 * captured on device 2026-08-06: rows written with erase-before-write at
 * absolute positions, 2-space indent, `\r\n` row flow, and a pinned
 * input/status section at rows 30-35. Regression tests for the baseline
 * shift detector in TerminalOutputProcessor.
 */
class ScrollCaptureRegressionTest {

    private val altEnter = "[?1049h"
    private val divider = "─".repeat(54)

    /** Claude-Code-style full redraw of a 54x36 screen. [lines] fills rows
     *  0..lines.size-1 (max 30); the bottom section (blank/divider/`❯`/
     *  divider/banner/status) is pinned at rows 30-35. */
    private fun redraw(
        lines: List<String>,
        spinner: String? = null,
        clock: String = "12:05",
    ): String {
        require(lines.size <= 30)
        val sb = StringBuilder()
        sb.append("[1;1H[1;36r")
        lines.forEachIndexed { index, line ->
            val text = if (spinner != null && index == 14) spinner else line
            sb.append("[${index + 1};2H[1K[C$text[K\r\n")
        }
        // Pinned bottom section (rows 30-35).
        sb.append("[31;1H[K[38;5;244m\r\n")
        sb.append("[32;1H[K$divider[K\r\n")
        sb.append("[33;1H[K❯ [K\r\n")
        sb.append("[34;1H[K$divider[K\r\n")
        sb.append("[35;1H[1K[C⏵⏵ bypass permissions on[K\r\n")
        sb.append("[36;1H[30m[42m[cloud-claude] claude* $clock(B[m")
        return sb.toString()
    }

    private fun conversation(first: Int, count: Int) = (first until first + count).map { "line $it" }

    private var nowNanos = 0L

    private fun processor() = TerminalOutputProcessor(
        columns = 54,
        rows = 36,
        nanoTime = { nowNanos },
    )

    /** Advances past the quiet window so the shift baseline settles. */
    private fun settle() {
        nowNanos += 600_000_000L
    }

    private fun rowText(frame: TerminalFrame, rowIndex: Int): String =
        frame.cells[rowIndex].joinToString("") { it.text }

    @Test
    fun attachRedrawDoesNotFabricateHistory() {
        val processor = processor()
        processor.consume(altEnter + redraw(conversation(1, 30)))
        assertEquals(0, processor.scrollbackRows)
    }

    @Test
    fun conversationShiftIsCapturedWithScrolledOutRow() {
        val processor = processor()
        processor.consume(altEnter + redraw(conversation(1, 30)))
        settle()
        // One new line pushes the conversation up by one; the bottom section
        // stays pinned at rows 30-35.
        processor.consume(redraw(conversation(2, 30)))

        assertEquals(1, processor.scrollbackRows)
        assertEquals("line 1", rowText(processor.scrollOlder(1), 0).trim())
    }

    @Test
    fun chunkedShiftRenderIsCapturedOnlyWhenComplete() {
        val processor = processor()
        processor.consume(altEnter + redraw(conversation(1, 30)))
        settle()
        val shifted = redraw(conversation(2, 30))
        // Split the render mid-conversation, as TCP reads do.
        val cut = shifted.indexOf("[16;2H")
        assertTrue(cut > 0)

        processor.consume(shifted.substring(0, cut))
        assertEquals(0, processor.scrollbackRows)

        processor.consume(shifted.substring(cut))
        assertEquals(1, processor.scrollbackRows)
        assertEquals("line 1", rowText(processor.scrollOlder(1), 0).trim())
    }

    @Test
    fun changingSpinnerRowDoesNotBlockCapture() {
        val processor = processor()
        processor.consume(altEnter + redraw(conversation(1, 30), spinner = "Thought for 3s"))
        settle()
        processor.consume(redraw(conversation(2, 30), spinner = "Thought for 4s"))

        assertEquals(1, processor.scrollbackRows)
    }

    @Test
    fun clockOnlyUpdateDoesNotCapture() {
        val processor = processor()
        processor.consume(altEnter + redraw(conversation(1, 30), clock = "12:05"))
        processor.consume(redraw(conversation(1, 30), clock = "12:06"))

        assertEquals(0, processor.scrollbackRows)
    }

    @Test
    fun pinnedHeaderRowIsNotCapturedAsHistory() {
        val processor = processor()
        val base = listOf("HEADER") + conversation(1, 29)
        val shifted = listOf("HEADER") + conversation(2, 29)
        processor.consume(altEnter + redraw(base))
        settle()
        processor.consume(redraw(shifted))

        assertEquals(1, processor.scrollbackRows)
        assertEquals("line 1", rowText(processor.scrollOlder(1), 0).trim())
    }

    @Test
    fun shortConversationShiftIsCaptured() {
        val processor = processor()
        processor.consume(altEnter + redraw(conversation(1, 5)))
        settle()
        processor.consume(redraw(conversation(2, 5)))

        assertEquals(1, processor.scrollbackRows)
        assertEquals("line 1", rowText(processor.scrollOlder(1), 0).trim())
    }
}
