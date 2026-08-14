package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulator-level replace-before-overwrite capture (design 2026-08-13):
 * Claude Code repaints by overwriting rows in place (cursor addressing +
 * erase), so text-shift matching misses every frame of fast/streaming
 * output. The emulator instead snapshots a SETTLED row's old content right
 * before the first overwrite of that row in a chunk; same-content rewrites
 * are dropped, the bottom input/status rows are never captured, and
 * mid-row edits don't trigger.
 */
class TerminalScreenReplaceCaptureTest {

    private val altEnter = "[?1049h"

    private fun rowText(rows: List<List<TerminalCell>>, index: Int): String =
        rows[index].joinToString("") { it.text }.trimEnd()

    private fun paint(screen: TerminalScreen, row: Int, text: String, col: Int = 1): String {
        val sb = StringBuilder()
        sb.append("[").append(row + 1).append(';').append(col).append('H')
        sb.append(text)
        return sb.toString()
    }

    @Test
    fun stableRowIsCapturedBeforeOverwrite() {
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "OLD"), nowNanos = 0)

        screen.consume(paint(screen, 0, "NEW"), nowNanos = 400_000_000L)

        val captured = screen.drainReplaceCaptures()
        assertEquals(1, captured.size)
        assertEquals("OLD", rowText(captured, 0))
    }

    @Test
    fun seqStyleFullRepaintCapturesThePreviousFrame() {
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)

        // Frame 1: rows 0-3 = 1..4
        screen.consume(
            paint(screen, 0, "1") + paint(screen, 1, "2") + paint(screen, 2, "3") + paint(screen, 3, "4"),
            nowNanos = 0,
        )

        // Frame 2 at +1s: rows 0-3 = 5..8 — the OLD numbers must be captured.
        screen.consume(
            paint(screen, 0, "5") + paint(screen, 1, "6") + paint(screen, 2, "7") + paint(screen, 3, "8"),
            nowNanos = 1_000_000_000L,
        )
        val frame1 = screen.drainReplaceCaptures()
        assertEquals(4, frame1.size)
        assertEquals("1", rowText(frame1, 0))
        assertEquals("2", rowText(frame1, 1))
        assertEquals("3", rowText(frame1, 2))
        assertEquals("4", rowText(frame1, 3))

        // Frame 3 at +2s.
        screen.consume(
            paint(screen, 0, "9") + paint(screen, 1, "10") + paint(screen, 2, "11") + paint(screen, 3, "12"),
            nowNanos = 2_000_000_000L,
        )
        val frame2 = screen.drainReplaceCaptures()
        assertEquals(4, frame2.size)
        assertEquals("5", rowText(frame2, 0))
        assertEquals("6", rowText(frame2, 1))
        assertEquals("7", rowText(frame2, 2))
        assertEquals("8", rowText(frame2, 3))
    }

    @Test
    fun unstableRowIsNotCaptured() {
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "A"), nowNanos = 0)

        // Rewritten only 30 ms later — the previous content may be a
        // half-painted row from a repaint split across reads; no capture.
        screen.consume(paint(screen, 0, "B"), nowNanos = 30_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
    }

    @Test
    fun sameContentRewriteIsDropped() {
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "A"), nowNanos = 0)

        // Same text repainted after settling — must not fabricate history.
        screen.consume(paint(screen, 0, "A"), nowNanos = 400_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
    }

    @Test
    fun bottomTwoRowsAreNeverCaptured() {
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 4, "INPUT1") + paint(screen, 5, "STATUS1"), nowNanos = 0)

        screen.consume(paint(screen, 4, "INPUT2") + paint(screen, 5, "STATUS2"), nowNanos = 400_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
    }

    @Test
    fun column5PaintIsCaptured() {
        // Trace evidence (2026-08-13): Claude's streaming repaint writes at
        // column 5 — a column threshold would silently block everything.
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "ABCDE"), nowNanos = 0)

        screen.consume(paint(screen, 0, "X", col = 5), nowNanos = 400_000_000L)

        val captured = screen.drainReplaceCaptures()
        assertEquals(1, captured.size)
        assertEquals("ABCDE", rowText(captured, 0))
    }

    @Test
    fun oneCapturePerRowPerChunk() {
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "A"), nowNanos = 0)

        // Two rewrites of the same row within one chunk — only the FIRST
        // captures the settled content; the intermediate is never history.
        screen.consume(
            paint(screen, 0, "B") + paint(screen, 0, "C"),
            nowNanos = 400_000_000L,
        )

        val captured = screen.drainReplaceCaptures()
        assertEquals(1, captured.size)
        assertEquals("A", rowText(captured, 0))
    }

    @Test
    fun seqStyleStreamingIsCapturedEndToEnd() {
        var now = 0L
        val processor = TerminalOutputProcessor(
            columns = 10,
            rows = 6,
            nanoTime = { now },
        )
        // Five consecutive full repaints with entirely new content (the
        // seq-output failure mode: text-shift matching finds nothing).
        // Captures are provisional for one chunk — frames 1-3 settle into
        // the scrollback by the fifth repaint. The 6-row screen excludes
        // its bottom 3 rows, so each frame paints rows 0-2 (3 rows).
        processor.consume(altEnter + frame(listOf("1", "2", "3")))
        now += 600_000_000L
        processor.consume(frame(listOf("4", "5", "6")))
        now += 600_000_000L
        processor.consume(frame(listOf("7", "8", "9")))
        now += 600_000_000L
        processor.consume(frame(listOf("10", "11", "12")))
        now += 600_000_000L
        processor.consume(frame(listOf("13", "14", "15")))

        // Frames 1-4 must all be in the scrollback, in order (captures are
        // provisional for one chunk — every quiet settle flushes the two
        // pending frames).
        assertEquals(12, processor.scrollbackRows)
        val text = processor.exportScrollbackText()
        assertEquals("1", text[0].trimEnd())
        assertEquals("3", text[2].trimEnd())
        assertEquals("4", text[3].trimEnd())
        assertEquals("12", text[11].trimEnd())
    }

    private fun frame(lines: List<String>): String =
        lines.mapIndexed { index, line -> paintFrameRow(index, line) }.joinToString("")

    private fun paintFrameRow(row: Int, text: String): String =
        "[${row + 1};1H$text"

    @Test
    fun browseSkipsScrollbackTailShownByScreen() {
        // Reconnect dedup bug (2026-08-13), fixed at the RENDER level: the
        // exported scrollback tail equals the live screen's leading rows,
        // and the browse view must show them once (the screen supplies
        // them). The screen paints its own indentation + CJK gaps, so the
        // match is whitespace-insensitive.
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        // Screen: C975..C1000 region as rows 0-2 (indented), "到 1000 了"
        // with an internal gap at row 3.
        screen.consume(
            paint(screen, 0, "  C975") + paint(screen, 1, "  C976") +
                paint(screen, 2, "  C977") + paint(screen, 3, "到 1000 了"),
            nowNanos = 0,
        )
        // Scrollback ends with the same rows, unindented (export text).
        screen.appendExternalRows(
            listOf("C970", "C975", "C976", "C977", "到1000了").map {
                Array(10) { col -> TerminalCell(text = if (col < it.length) it[col].toString() else " ") }
            },
        )

        // Browse one screenful up: the scrollback's C975-onward rows are
        // already on screen and must not repeat — the view is the scrollback
        // rows above the overlap (C970) followed by the screen's rows
        // (C975 onward, indented), with C975 appearing exactly once.
        val view = screen.snapshot(scrollOffsetRows = 5)
        val text = view.joinToString("\n") { it.joinToString("") { c -> c.text }.trimEnd() }.trim()
        assertEquals("C970\n  C975\n  C976\n  C977\n到 1000 了", text)
    }

    @Test
    fun browseUnchangedWhenNoOverlap() {
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(
            paint(screen, 0, "LIVE1") + paint(screen, 1, "LIVE2") + paint(screen, 2, "LIVE3"),
            nowNanos = 0,
        )
        screen.appendExternalRows(
            listOf("A1", "A2", "A3", "A4").map {
                Array(10) { col -> TerminalCell(text = if (col < it.length) it[col].toString() else " ") }
            },
        )

        val view = screen.snapshot(scrollOffsetRows = 6)
        val text = view.joinToString("\n") { it.joinToString("") { c -> c.text }.trimEnd() }
        assertEquals("A1\nA2\nA3\nA4\nLIVE1\nLIVE2", text)
    }
}
