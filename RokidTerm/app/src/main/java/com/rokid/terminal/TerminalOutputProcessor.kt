package com.rokid.terminal

/**
 * Owns terminal emulation for remote PTY output.
 *
 * SSH/network code feeds decoded text chunks here on its worker thread. The
 * processor applies VT/ANSI semantics and publishes an immutable frame for the
 * Android View. Keeping this class free of Android APIs makes terminal behavior
 * independently testable and keeps parsing work off the UI thread.
 */
class TerminalOutputProcessor(
    columns: Int = TerminalSpec.DEFAULT_COLUMNS,
    rows: Int = TerminalSpec.DEFAULT_ROWS,
    maxScrollbackRows: Int = TerminalScreen.DEFAULT_SCROLLBACK_ROWS,
    private val nanoTime: () -> Long = System::nanoTime,
    private val quietRedrawNanos: Long = QUIET_REDRAW_NANOS,
) {
    private val screen = TerminalScreen(
        columns = columns,
        rows = rows,
        maxScrollbackRows = maxScrollbackRows,
    )
    private var revision = 0L

    /** Scroll capture is suppressed until this nanoTime (resume replay). */
    private var suppressCaptureUntilNanos = 0L

    /**
     * Suppresses scroll capture for [durationMs] — called after a
     * conversation switch, because the resume replay scrolls the viewport
     * and would duplicate the imported transcript in the scrollback
     * (2026-08-07). The screen still renders normally.
     */
    @Synchronized
    fun suppressScrollCaptureFor(durationMs: Long) {
        suppressCaptureUntilNanos = nanoTime() + durationMs * 1_000_000L
    }

    /**
     * After the resume replay settles: drops the turns the live screen
     * already shows from the imported scrollback, so browsing (which appends
     * the screen below the scrollback) never duplicates them (2026-08-07).
     */
    @Synchronized
    fun trimScrollbackToScreen() {
        screen.trimScrollbackTurns(screen.activeScreenUserCount())
        scrollBaseline = emptyList()
        scrollOffsetRows = 0
        hasNewOutput = false
        revision++
    }
    private var scrollOffsetRows = 0
    private var hasNewOutput = false

    /**
     * Last settled alternate-screen snapshot, used to detect Claude Code's
     * redraw-style shifts (see [findScrollCapture]). Refreshed on entry into
     * the alternate screen and after quiet pauses; rows vanish from the top
     * of a shifted region, so this baseline is what "history" is measured
     * against.
     */
    private var scrollBaseline: List<Array<TerminalCell>> = emptyList()
    private var lastConsumeNanos = 0L

    /** Current terminal-history offset; 0 means live bottom. */
    val scrollOffset: Int get() = scrollOffsetRows

    /** Scrollback row count (diagnostics; alternate-screen capture check). */
    val scrollbackRows: Int get() = screen.scrollbackSize()

    /** Scrollback rows as plain text (app-side persistence). */
    @Synchronized
    fun exportScrollbackText(): List<String> = screen.exportScrollbackText()

    /** Restores previously persisted scrollback rows (primary screen only). */
    @Synchronized
    fun importScrollbackText(rows: List<String>) {
        screen.importScrollbackText(rows)
        scrollBaseline = emptyList()
        revision++
    }

    /**
     * Replaces the scrollback even while the alternate screen is active —
     * rebuilding a resumed conversation's history from the server export
     * (2026-08-07). Also returns the view to live (the new rows replace the
     * old ones; the user's offset would otherwise index stale rows).
     */
    @Synchronized
    fun importScrollbackTextForce(rows: List<String>) {
        screen.importScrollbackTextForce(rows)
        scrollBaseline = emptyList()
        scrollOffsetRows = 0
        hasNewOutput = false
        revision++
    }

    /**
     * Unconditionally empties the in-memory scrollback (conversation
     * switches must not leak the previous conversation's rows; the import
     * alone is a no-op while the alternate screen is active or for empty
     * files — fixed 2026-08-07).
     */
    @Synchronized
    fun clearScrollback() {
        screen.clearScrollback()
        scrollBaseline = emptyList()
        scrollOffsetRows = 0
        hasNewOutput = false
        revision++
    }

    @Synchronized
    fun consume(raw: String): TerminalFrame {
        val appendedBefore = screen.scrollbackRowsAppended()
        val altBefore = screen.isAlternateActive()
        val now = nanoTime()
        val captureSuppressed = now < suppressCaptureUntilNanos
        // Claude Code re-renders by overwriting cells (no scroll escapes), so
        // scrolled-out rows are found by comparing against the last settled
        // screen. The baseline is taken only after a quiet pause: renders
        // split across network reads produce partial frames, and the attach
        // redraw burst must settle before any comparison — a mid-burst
        // baseline would fabricate duplicate history rows.
        // Capture is ALSO suppressed for a window after a conversation switch:
        // the resume replay genuinely SCROLLS (the conversation exceeds the
        // viewport) and would otherwise duplicate the imported transcript
        // (user report 2026-08-07 — 3 turns showed as 5).
        if (altBefore && !captureSuppressed && now - lastConsumeNanos > quietRedrawNanos) {
            scrollBaseline = screen.snapshotRows()
        }
        lastConsumeNanos = now
        screen.consume(raw)
        if (!captureSuppressed && screen.isAlternateActive() && scrollBaseline.isNotEmpty()) {
            val afterRows = screen.snapshotRows()
            val capture = findScrollCapture(scrollBaseline, afterRows)
            if (capture != null) {
                screen.appendExternalRows(scrollBaseline.subList(capture.start, capture.start + capture.k))
                scrollBaseline = afterRows
            }
        }
        // Entering or leaving the alternate screen invalidates the baseline.
        if (screen.isAlternateActive() != altBefore) scrollBaseline = emptyList()
        val appendedRows = (screen.scrollbackRowsAppended() - appendedBefore)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (scrollOffsetRows > 0) {
            scrollOffsetRows = (scrollOffsetRows.toLong() + appendedRows)
                .coerceAtMost(screen.scrollbackSize().toLong())
                .toInt()
            hasNewOutput = hasNewOutput || (raw.isNotEmpty() && scrollOffsetRows > 0)
        } else {
            hasNewOutput = false
        }
        revision++
        return currentFrame()
    }

    /**
     * Finds the rows that scrolled out of the screen between [baseline] and
     * [after]: the smallest k where rows of `after` equal the rows k below
     * them in `baseline`, with enough agreeing rows covering a contiguous
     * span. Returns null when the screen was redrawn rather than shifted.
     * Rows are compared by text only; a redraw split across network reads
     * never matches until its last chunk arrives.
     */
    private fun findScrollCapture(
        baseline: List<Array<TerminalCell>>,
        after: List<Array<TerminalCell>>,
    ): ScrollCapture? {
        if (baseline.size != after.size || baseline.isEmpty()) return null
        val rows = baseline.size
        val baseText = baseline.map { row -> row.joinToString("") { it.text } }
        val afterText = after.map { row -> row.joinToString("") { it.text } }
        for (k in 1..rows / 2) {
            var matches = 0
            var first = -1
            var last = -1
            for (i in 0 until rows - k) {
                if (afterText[i] == baseText[i + k]) {
                    if (matches == 0) first = i
                    matches++
                    last = i
                }
            }
            if (matches == 0) continue
            val required = (rows - k) * MIN_MATCH_FRACTION
            val span = last - first + 1
            // The first matched row must sit near the top: a shift pushes
            // content out at the top, whereas idle re-renders only match in
            // blank tail regions (and must not fabricate history).
            if (first <= MAX_FIRST_MATCH_ROW && matches >= required && span >= required) {
                return ScrollCapture(k, first)
            }
        }
        return null
    }

    private data class ScrollCapture(val k: Int, val start: Int)

    @Synchronized
    fun resize(columns: Int, rows: Int): TerminalFrame {
        if (screen.resize(columns, rows)) {
            scrollOffsetRows = 0
            hasNewOutput = false
            scrollBaseline = emptyList()
            revision++
        }
        return currentFrame()
    }

    @Synchronized
    fun reset(): TerminalFrame {
        screen.reset()
        scrollOffsetRows = 0
        hasNewOutput = false
        scrollBaseline = emptyList()
        revision++
        return currentFrame()
    }

    @Synchronized
    fun scrollOlder(rows: Int = DEFAULT_SCROLL_STEP_ROWS): TerminalFrame {
        require(rows > 0)
        val nextOffset = (scrollOffsetRows.toLong() + rows)
            .coerceAtMost(screen.scrollbackSize().toLong())
            .toInt()
        if (nextOffset != scrollOffsetRows) {
            scrollOffsetRows = nextOffset
            revision++
        }
        return currentFrame()
    }

    @Synchronized
    fun scrollNewer(rows: Int = DEFAULT_SCROLL_STEP_ROWS): TerminalFrame {
        require(rows > 0)
        val nextOffset = (scrollOffsetRows - rows).coerceAtLeast(0)
        if (nextOffset != scrollOffsetRows) {
            scrollOffsetRows = nextOffset
            if (scrollOffsetRows == 0) hasNewOutput = false
            revision++
        }
        return currentFrame()
    }

    @Synchronized
    fun returnToLive(): TerminalFrame {
        if (scrollOffsetRows != 0 || hasNewOutput) {
            scrollOffsetRows = 0
            hasNewOutput = false
            revision++
        }
        return currentFrame()
    }

    @Synchronized
    fun snapshot(): TerminalFrame = currentFrame()

    private fun currentFrame(): TerminalFrame = TerminalFrame(
        revision = revision,
        columns = screen.columns,
        rows = screen.rows,
        cells = screen.snapshot(scrollOffsetRows),
        cursor = if (scrollOffsetRows == 0) {
            screen.cursor()
        } else {
            screen.cursor().copy(visible = false)
        },
        scrollOffsetRows = scrollOffsetRows,
        scrollbackRows = screen.scrollbackSize(),
        hasNewOutput = hasNewOutput,
    )

    companion object {
        const val DEFAULT_SCROLL_STEP_ROWS = 3

        /** Minimum fraction of rows that must agree (and span) to count as a shift. */
        private const val MIN_MATCH_FRACTION = 0.6

        /** A redraw burst paused longer than this re-baselines the shift detector. */
        private const val QUIET_REDRAW_NANOS = 500_000_000L

        /** The first agreeing row must sit within the top rows of the screen. */
        private const val MAX_FIRST_MATCH_ROW = 2
    }
}
