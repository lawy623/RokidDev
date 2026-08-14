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
            rows = 36,   // real layout: bottom 7 rows are UI chrome (excluded)
            nanoTime = { now },
        )
        // Five consecutive full repaints with entirely new content (the
        // seq-output failure mode: text-shift matching finds nothing).
        // Captures are provisional for one chunk — frames 1-3 settle into
        // the scrollback by the fifth repaint. Each frame paints rows 0-2.
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
    fun statusTickRowIsNotCaptured() {
        // Claude's thinking status ticks ~1/s at a row position that shifts
        // between versions (was 29 for "Cooking for", 28 for "Combobulating…",
        // user report 2026-08-14: 467 captured ticks flooded the history).
        // The capture must reject it by CONTENT, not position.
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 3, "✻ Combobulating… (30s · ↓ 2.0k tokens)"), nowNanos = 0)

        screen.consume(paint(screen, 3, "✻ Combobulating… (31s · thought for 1s)"), nowNanos = 400_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
    }

    @Test
    fun turnEndStatusRowIsNotCaptured() {
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "✻ Brewed for 3m 59s"), nowNanos = 0)

        screen.consume(paint(screen, 0, "NEXT CONTENT"), nowNanos = 400_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
    }

    @Test
    fun toolRunStatusRowIsNotCaptured() {
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "● Running 1 shell command…"), nowNanos = 0)

        screen.consume(paint(screen, 0, "OUTPUT"), nowNanos = 400_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
    }

    @Test
    fun toolRunParenTimerRowIsNotCaptured() {
        // Second flood report 2026-08-14: the newest Claude Code renders the
        // running-tool line with a rising CLOSED-PAREN elapsed timer —
        // "    && ls -la hkustgz_low.* (2m 5s)" — 72 per-second ticks
        // entered the history (the old regex required a "·" after the
        // seconds). Each tick repaints the row; none may be captured.
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "    && ls -la hkustgz_low.* (2m 5s)"), nowNanos = 0)
        screen.consume(paint(screen, 0, "    && ls -la hkustgz_low.* (2m 6s)"), nowNanos = 400_000_000L)
        screen.consume(paint(screen, 0, "    && ls -la hkustgz_low.* (2m 7s)"), nowNanos = 800_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
    }

    @Test
    fun toolRunParenSecondsTimerRowIsNotCaptured() {
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "    && ls -la hkustgz_low.* (10s)"), nowNanos = 0)
        screen.consume(paint(screen, 0, "    && ls -la hkustgz_low.* (11s)"), nowNanos = 400_000_000L)
        screen.consume(paint(screen, 0, "    && ls -la hkustgz_low.* (12s)"), nowNanos = 800_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
    }

    @Test
    fun staticSecondsEndingRowIsStillCaptured() {
        // User guard 2026-08-14: a bare "Ns" ending alone must NOT be
        // status — static content like "提交成功 3s" survives the filter.
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "提交成功 3s"), nowNanos = 0)

        screen.consume(paint(screen, 0, "NEXT"), nowNanos = 400_000_000L)

        val captured = screen.drainReplaceCaptures()
        assertEquals(1, captured.size)
        assertTrue(rowText(captured, 0).startsWith("提交成功 3s"))
    }

    @Test
    fun tickingRowWithUnrecognizedFormatIsNotFlooded() {
        // The ticker guard is FORMAT-INDEPENDENT: a row repainted ~1/s with
        // only the digits changing is a live timer even without any known
        // content signature (this bare "deploy --check 12s" has no parens
        // and no "&&" — a hypothetical future render). Only the first tick
        // may enter history; the rest are dropped.
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "deploy --check 12s"), nowNanos = 0)
        screen.consume(paint(screen, 0, "deploy --check 13s"), nowNanos = 400_000_000L)
        screen.consume(paint(screen, 0, "deploy --check 14s"), nowNanos = 800_000_000L)
        screen.consume(paint(screen, 0, "deploy --check 15s"), nowNanos = 1_200_000_000L)

        val captured = screen.drainReplaceCaptures()
        assertEquals(1, captured.size)
        assertTrue(rowText(captured, 0).startsWith("deploy --check 12s"))
    }

    @Test
    fun toolOutputTickerWithAlternatingMarkerIsNotFlooded() {
        // Trace replica (2026-08-14, third report): the running-tool line
        // ticks via TWO repaints per second — the ● marker at col 1
        // ALTERNATES with a blank (separate chunks) and the timer digits
        // are repainted in place. The alternating marker makes consecutive
        // oldTexts differ by more than just digits ("● …" vs "  …"), which
        // defeated the plain digit-variant guard; the marker must be
        // normalized away. This format ("deploy --check 12s") deliberately
        // has no content signature — it tests the FORMAT-INDEPENDENT guard.
        // The processor drains per frame, so drain per tick like it does.
        val screen = TerminalScreen(columns = 54, rows = 36, excludedBottomRows = 7)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 21, "● deploy --check 12s"), nowNanos = 0)
        var t = 400_000_000L
        var total = 0
        for (sec in 1..5) {
            screen.consume(paint(screen, 21, if (sec % 2 == 1) "●" else " ", col = 1), nowNanos = t)
            screen.consume(paint(screen, 21, "deploy --check ${12 + sec}s", col = 3), nowNanos = t + 100_000_000L)
            total += screen.drainReplaceCaptures().size
            t += 1_000_000_000L
        }
        assertEquals(1, total) // only the first tick may enter history
    }

    @Test
    fun nonTickingRowsAtSamePositionStillCapture() {
        // A genuinely new row at the same position resets the ticker flag.
        // Captures fire when a settled row is OVERWRITTEN, and
        // pendingCaptures has ONE slot per row — drain between stages.
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "deploy --check 12s"), nowNanos = 0)
        screen.consume(paint(screen, 0, "deploy --check 13s"), nowNanos = 400_000_000L) // overwrites 12s -> captured
        assertEquals(1, screen.drainReplaceCaptures().size)
        screen.consume(paint(screen, 0, "deploy --check 14s"), nowNanos = 800_000_000L) // pattern proven -> dropped
        assertTrue(screen.drainReplaceCaptures().isEmpty())
        screen.consume(paint(screen, 0, "构建完成"), nowNanos = 1_200_000_000L) // overwrites the ticker row -> dropped
        assertTrue(screen.drainReplaceCaptures().isEmpty())
        screen.consume(paint(screen, 0, "下一步"), nowNanos = 1_600_000_000L) // overwrites 构建完成 -> captured (reset)
        assertEquals(1, screen.drainReplaceCaptures().size)
    }

    @Test
    fun realContentRowIsStillCaptured() {
        // "● 已提交 …" is a real tool-output row — the ● marker alone must
        // not trigger the status signature.
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "● 已提交 856bcdb（缓存协议修复完成）"), nowNanos = 0)

        screen.consume(paint(screen, 0, "NEXT"), nowNanos = 400_000_000L)

        val captured = screen.drainReplaceCaptures()
        assertEquals(1, captured.size)
        assertTrue(rowText(captured, 0).startsWith("● 已提交"))
    }

    @Test
    fun purgeStatusRowsRemovesOnlyStatusRows() {
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.appendExternalRows(
            listOf(
                "✻ Combobulating… (1m 10s · thought for 4s)",
                "❯ 请等待 3m 59s 后重试",
                "✻ Brewed for 3m 59s",
                "● Generating LOD1 at 50% decimation · 1m 40s",
                "选 A 还是 B？或者先不推",
            ).map { text ->
                Array(54) { col -> TerminalCell(text = if (col < text.length) text[col].toString() else " ") }
            },
        )

        assertEquals(3, screen.purgeStatusRows())
        assertEquals(2, screen.scrollbackSize())
        // User rows and real content survive; the status rows are gone.
        val text = screen.exportScrollbackText()
        assertEquals("❯ 请等待 3m 59s 后重试", text[0].trimEnd())
        assertEquals("选 A 还是 B？或者先不推", text[1].trimEnd())
    }

    @Test
    fun importWrapsLongLinesInsteadOfTruncating() {
        // Server export lines are LOGICAL lines up to 2000 chars; the old
        // import truncated at 54 and silently dropped every long line's
        // tail (bug 2026-08-14: restored history looked unwrapped and
        // incomplete). The import must wrap at the grid width instead.
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        val long = "1234567890".repeat(5) // 50 chars
        screen.importScrollbackTextForce(listOf(long))

        assertEquals(5, screen.scrollbackSize())
        val text = screen.exportScrollbackText()
        assertEquals("1234567890", text[0].trimEnd())
        assertEquals("1234567890", text[1].trimEnd())
        assertEquals("1234567890", text[4].trimEnd())
        assertEquals(50, text.sumOf { it.trimEnd().length })
    }

    @Test
    fun importWrapKeepsBackgroundAcrossWrappedRows() {
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.importScrollbackTextForce(listOf("[48;5;237m  ❯ 一二三四五六七八九十一二三四五六七八九十一二[49m"))

        // 22 CJK chars = 44 display columns + 4-col prefix = 48 → 5 grid
        // rows, all carrying the user-block background (the live TUI fills
        // the whole block too).
        assertEquals(5, screen.scrollbackSize())
        val text = screen.exportScrollbackText()
        text.forEach { row -> assertTrue("row lost background: $row", row.contains("[48;5;237m")) }
        assertEquals(26, text.sumOf { row ->
            row.replace("[48;5;237m", "").replace("[49m", "").trimEnd().length
        })
    }

    @Test
    fun pipeTableRowIsNotCaptured() {
        // Claude streams tables as markdown pipes ("| 方式 | 行为 | 现状 |"),
        // then re-renders them as box-drawing tables in the final repaint.
        // The pipe form is an intermediate — it must not become history
        // (user report 2026-08-14: restored history showed the misaligned
        // pipe rows instead of the final table).
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "| 方式 | 行为 | 现状 |"), nowNanos = 0)
        screen.consume(paint(screen, 1, "|---|---|---|"), nowNanos = 0)
        screen.consume(paint(screen, 2, "| **整包下载**（现在） | 一次拉 157MB → 全部解码 |"), nowNanos = 0)

        screen.consume(paint(screen, 0, "NEXT"), nowNanos = 400_000_000L)
        screen.consume(paint(screen, 1, "NEXT"), nowNanos = 400_000_000L)
        screen.consume(paint(screen, 2, "NEXT"), nowNanos = 400_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
    }

    @Test
    fun codeLikePipeRowIsStillCaptured() {
        // "| a | b |" without CJK cells or a pipe/dash separator is not a
        // Claude table — e.g. a code row — and must still be captured.
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "| a | b |"), nowNanos = 0)

        screen.consume(paint(screen, 0, "NEXT"), nowNanos = 400_000_000L)

        val captured = screen.drainReplaceCaptures()
        assertEquals(1, captured.size)
        assertTrue(rowText(captured, 0).startsWith("| a | b |"))
    }

    @Test
    fun purgeRemovesPipeTableRows() {
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.appendExternalRows(
            listOf(
                "| 方式 | 行为 | 现状 |",
                "|---|---|---|",
                "  | **降采样先行版** | 另做一个低精度 SOG（几十 MB） |",
                "  我的建议：先用缓存方案观察体验",
            ).map { text ->
                Array(54) { col -> TerminalCell(text = if (col < text.length) text[col].toString() else " ") }
            },
        )

        assertEquals(3, screen.purgeStatusRows())
        assertEquals(1, screen.scrollbackSize())
        assertEquals("  我的建议：先用缓存方案观察体验", screen.exportScrollbackText()[0].trimEnd())
    }

    @Test
    fun repaintFragmentIsNotCaptured() {
        // Claude repaints the response area non-sequentially — a row
        // captured at overwrite time is often an INTERMEDIATE fragment
        // whose final form is still on screen (user report 2026-08-14:
        // jumbled multi-generation rows in the history). A capture whose
        // text is contained in another on-screen row is such a fragment.
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        // The fragment (row 0) and its final, longer form (row 1).
        screen.consume(paint(screen, 0, "转换工具链（splat-transform"), nowNanos = 0)
        screen.consume(paint(screen, 1, "转换工具链（splat-transform 是否支持输出）"), nowNanos = 0)

        screen.consume(paint(screen, 0, "NEXT"), nowNanos = 400_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
    }

    @Test
    fun uniqueScrolledRowIsStillCaptured() {
        // The fragment rule must NOT block genuine history: a row whose
        // content is NOT visible elsewhere (it scrolled off) is captured.
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "1234567") + paint(screen, 1, "ABCDEFG"), nowNanos = 0)

        screen.consume(paint(screen, 0, "NEW"), nowNanos = 400_000_000L)

        val captured = screen.drainReplaceCaptures()
        assertEquals(1, captured.size)
        assertEquals("1234567", rowText(captured, 0))
    }

    @Test
    fun userRowIsCapturedEvenWhenTextRepeats() {
        // ❯ rows are the anchors of each turn — a repeated user message
        // must still enter history even though its text is on screen.
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "❯ 我们继续看看这个方案"), nowNanos = 0)
        screen.consume(paint(screen, 1, "❯ 我们继续看看这个方案"), nowNanos = 0)

        screen.consume(paint(screen, 0, "NEXT"), nowNanos = 400_000_000L)

        val captured = screen.drainReplaceCaptures()
        assertEquals(1, captured.size)
        assertTrue(rowText(captured, 0).startsWith("❯"))
    }

    @Test
    fun toolBlockRowIsNotCaptured() {
        // The TUI indents the running command and its output with ⎿ —
        // tool CHROME, not conversation content (user report 2026-08-14:
        // history cluttered with tool-call inputs).
        val screen = TerminalScreen(columns = 54, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(paint(screen, 0, "⎿  $ echo === via CF ==="), nowNanos = 0)
        screen.consume(paint(screen, 1, "⎿  服务端头完全正确"), nowNanos = 0)

        screen.consume(paint(screen, 0, "NEXT"), nowNanos = 400_000_000L)
        screen.consume(paint(screen, 1, "NEXT"), nowNanos = 400_000_000L)

        assertTrue(screen.drainReplaceCaptures().isEmpty())
        // Existing captured tool rows are purged too.
        screen.appendExternalRows(
            listOf("⎿  $ bash deploy.sh", "✅ 修复完成").map { text ->
                Array(54) { col -> TerminalCell(text = if (col < text.length) text[col].toString() else " ") }
            },
        )
        assertEquals(1, screen.purgeStatusRows())
        assertEquals(1, screen.scrollbackSize())
    }

    @Test
    fun browseSkipsScrollbackTailWhenScreenLeadingRowsNotCaptured() {
        // Streaming repaints copy the CURRENT turn (screen rows B1-B3) into
        // the scrollback; the screen's LEADING rows (A1-A2) were never
        // captured (timing gaps), so the forward anchor fails and the old
        // overlap left the current turn doubled at the browse junction
        // (bug 2026-08-14). The upward anchor (scrollback last row on
        // screen) must dedupe it.
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(
            paint(screen, 0, "A1") + paint(screen, 1, "A2") +
                paint(screen, 2, "B1") + paint(screen, 3, "B2") + paint(screen, 4, "B3"),
            nowNanos = 0,
        )
        screen.appendExternalRows(
            listOf("A0", "B1", "B2", "B3").map {
                Array(10) { col -> TerminalCell(text = if (col < it.length) it[col].toString() else " ") }
            },
        )

        val view = screen.snapshot(scrollOffsetRows = 6)
        val text = view.joinToString("\n") { it.joinToString("") { c -> c.text }.trimEnd() }.trim()
        // A0 (scrollback) + the whole screen — B1-B3 appear exactly once.
        assertEquals("A0\nA1\nA2\nB1\nB2\nB3", text)
    }

    @Test
    fun settleTrimDropsScreenDuplicateSuffix() {
        // The persist-time trim (trimSettledScrollback): scrollback tail ==
        // screen rows (repaint copies) must be dropped so the FILE and the
        // browse view never carry the current turn twice.
        val screen = TerminalScreen(columns = 10, rows = 6, excludedBottomRows = 2)
        screen.consume(altEnter, nowNanos = 0)
        screen.consume(
            paint(screen, 0, "OLD1") + paint(screen, 1, "OLD2") +
                paint(screen, 2, "B1") + paint(screen, 3, "B2") + paint(screen, 4, "B3"),
            nowNanos = 0,
        )
        screen.appendExternalRows(
            listOf("OLD0", "B1", "B2", "B3").map {
                Array(10) { col -> TerminalCell(text = if (col < it.length) it[col].toString() else " ") }
            },
        )
        assertEquals(4, screen.scrollbackSize())

        assertEquals(3, screen.trimScrollbackScreenSuffix())
        assertEquals(1, screen.scrollbackSize())
        assertEquals("OLD0", screen.exportScrollbackText()[0].trimEnd())
    }

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
