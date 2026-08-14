package com.rokid.terminal

/**
 * Claude Code's live status-row recognition (2026-08-14).
 *
 * The TUI shows a floating status row at the bottom of the content area
 * while thinking / running tools: a spinner glyph plus a verb and a
 * ticking timer — "✻ Combobulating… (1m 10s · thought for 2s)",
 * "✻ Brewed for 3m 59s", "● Running 1 shell command…". The verb changes
 * per Claude Code version (Cooking, Brewing, Combobulating…) and the row's
 * screen position shifts with the layout (was row 29 for "Cooking for",
 * row 28 for "Combobulating…"), so neither position nor verb is a stable
 * signature — the timer, the "(thinking…)" state and the spinner/ellipsis
 * forms are.
 *
 * Status rows must never enter the scrollback history (user report
 * 2026-08-14: 467 captured "Combobulating…" ticks flooded the file, and
 * the ~1 Hz repaints made the real-time stream janky), and frames whose
 * only changes are status rows must not trigger full-screen repaints.
 */
object ClaudeStatusRows {

    /**
     * The ticking tool timer: "for 3m 59s", "for 15s,", "(30s · ↓ 2.0k
     * tokens)", "(1m 10s · thought for 2s)", "(2m 9s)", "(10s)", and the
     * bare "· 1m 40s" / "· 2s" row-end form ("Generating LOD1 at 50%
     * decimation · 1m 40s" — 401 ticks flooded the file on 2026-08-14).
     * The "for" form may sit anywhere; the paren and "·" forms are
     * anchored to the row END (after the SGR strip) so assistant prose
     * like "用了 (2m 9s) 完成" or "按 · 继续" stays content.
     */
    private val TIMER = Regex(
        """for\s+\d+\s*m?\s*\d*\s*s|\(\s*\d+\s*m?\s*\d*\s*s(\s*·[^)]*)?\)$|·\s*\d+(\s*m\s+\d+)?s$"""
    )

    /** Inline SGR codes (row backgrounds are stored inline as ESC[48;5;Nm). */
    private val SGR = Regex("\u001b\\[[0-9;]*m")

    /** Spinner glyphs Claude cycles at column 0 while busy (not ● — that
     *  marks real tool-output rows like "● 已提交"). */
    private val SPINNERS = "✻✶✽✢✹"

    fun isStatusRow(text: String): Boolean {
        val t = text.trim().replace(SGR, "")
        if (t.isEmpty() || t.length > 90) return false
        // User-message rows are content, never status.
        if (t.startsWith("❯") || t.startsWith(">")) return false
        if (t.contains("(thinking") || t.contains("(still thinking")) return true
        if (TIMER.containsMatchIn(t)) return true
        // Spinner glyph + ellipsis verb: "✻ Combobulating…", "· Combobulating…".
        if (t.length <= 60 && t.contains("…") && t[0] in SPINNERS) return true
        if (t.length <= 30 && t.contains("…") && t[0] == '·') return true
        // Bare ellipsis tool rows: "Running 1 shell command…".
        if (t.length <= 40 && t.contains("…") && t.contains("shell")) return true
        return false
    }

    /** Whether the row carries the ticking elapsed timer ("… · 1m 40s",
     *  "…(2m 5s)", "Brewed for 3m 59s"). Timer rows carry INFORMATION the
     *  user must see live — the tool is still running, and for how long. */
    fun containsTimer(text: String): Boolean =
        TIMER.containsMatchIn(text.trim().replace(SGR, ""))

    /**
     * Rows that may be FROZEN on screen while they tick — status rows
     * WITHOUT a timer (spinner/thinking animation only, no information).
     * The render path must NOT freeze timer rows: user report 2026-08-14 —
     * after the "· Ns" content rule started classifying the tool timer as
     * status, hasRenderableChange suppressed its repaints and the elapsed
     * time froze until the next non-status change, so the user could not
     * tell whether the tool had finished.
     */
    fun isRenderSuppressible(text: String): Boolean = isStatusRow(text) && !containsTimer(text)

    /**
     * Markdown table SOURCE rows — Claude streams tables in raw pipe form,
     * then re-renders them as box-drawing tables in the final repaint
     * (frame evidence 2026-08-14: "| 方式 | 行为 | 现状 |" streaming vs the
     * final "┌───┬───┐" form). The pipe form is an intermediate state and
     * must not enter history (user report 2026-08-14: restored history
     * showed the misaligned pipe rows; and the text mismatch between the
     * pipe copy and the box-drawing screen broke the browse dedup, making
     * the current turn render twice at the junction). Code rows that merely
     * look like a table (e.g. "| a | b |") are not flagged — CJK cells or
     * a pipe/dash separator mark a real table.
     */
    fun isPipeTableRow(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("|")) return false
        if (t.count { it == '|' } < 3) return false
        if (t.any { it > '⿿' }) return true // CJK cells — a real table
        return TABLE_SEPARATOR.matches(t) // "|---|---|---|"
    }

    private val TABLE_SEPARATOR = Regex("""^[|\s\-]+$""")

    /**
     * Claude's tool-execution block rows — the TUI indents the running
     * command and its output with ⎿ ("⎿ $ bash deploy.sh …", "⎿ Output: …",
     * "⎿ Tip: …"). These are tool CHROME, not conversation content: the
     * command INPUT (full shell commands) and raw output must not enter the
     * history (user report 2026-08-14: the last conversation's history was
     * cluttered with tool-call inputs). ⎿ (U+23BF) never appears in prose.
     */
    fun isToolBlockRow(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("⎿") && t.length > 1
    }
}
