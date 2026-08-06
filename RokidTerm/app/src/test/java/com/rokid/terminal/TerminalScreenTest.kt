package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenTest {
    @Test
    fun wrapsAtDeclaredColumnCount() {
        val screen = TerminalScreen(columns = 4, rows = 3)

        screen.consume("abcde")

        assertEquals("abcd", rowText(screen, 0))
        assertEquals("e   ", rowText(screen, 1))
    }

    @Test
    fun fullLineWaitsForNextCharacterBeforeWrapping() {
        val screen = TerminalScreen(columns = 4, rows = 3)

        screen.consume("abcd")

        assertEquals("abcd", rowText(screen, 0))
        assertEquals("    ", rowText(screen, 1))
    }

    @Test
    fun treatsChineseCharactersAsTwoCells() {
        val screen = TerminalScreen(columns = 4, rows = 3)

        screen.consume("中ab")

        assertEquals("中·ab", rowText(screen, 0))
    }

    @Test
    fun treatsSupplementaryEmojiAsTwoCells() {
        val screen = TerminalScreen(columns = 4, rows = 2)

        screen.consume("😀a")

        assertEquals("😀·a ", rowText(screen, 0))
    }

    @Test
    fun combiningMarkStaysInPreviousCell() {
        val screen = TerminalScreen(columns = 4, rows = 2)

        screen.consume("e\u0301x")

        assertEquals("e\u0301x  ", rowText(screen, 0))
        assertEquals(2, screen.cursor().column)
    }

    @Test
    fun zeroWidthJoinerEmojiSequenceStaysInOneWideCell() {
        val screen = TerminalScreen(columns = 5, rows = 2)

        screen.consume("👨‍💻x")

        assertEquals("👨‍💻·x  ", rowText(screen, 0))
        assertEquals(3, screen.cursor().column)
    }

    @Test
    fun cursorPositionOverwritesExistingText() {
        val screen = TerminalScreen(columns = 5, rows = 3)
        screen.consume("hello")

        screen.consume("\u001b[1;2HX")

        assertEquals("hXllo", rowText(screen, 0))
    }

    @Test
    fun eraseLineClearsFromCursorToEnd() {
        val screen = TerminalScreen(columns = 5, rows = 3)
        screen.consume("hello")

        screen.consume("\u001b[1;3H\u001b[K")

        assertEquals("he   ", rowText(screen, 0))
    }

    @Test
    fun ignoresCharsetSelectionSequences() {
        val screen = TerminalScreen(columns = 24, rows = 3)

        screen.consume("Welcome\u001b(B back!\u001b(B")

        assertEquals("Welcome back!           ", rowText(screen, 0))
    }

    @Test
    fun keepsEscapeIntermediateStateAcrossNetworkChunks() {
        val screen = TerminalScreen(columns = 6, rows = 3)

        screen.consume("A\u001b(")
        screen.consume("BB")

        assertEquals("AB    ", rowText(screen, 0))
    }

    @Test
    fun ignoresOscPayloadTerminatedByBell() {
        val screen = TerminalScreen(columns = 4, rows = 2)

        screen.consume("A\u001b]0;window title\u0007B")

        assertEquals("AB  ", rowText(screen, 0))
    }

    @Test
    fun ignoresDcsPayloadAndTerminatorSplitAcrossChunks() {
        val screen = TerminalScreen(columns = 4, rows = 2)

        screen.consume("A\u001bPignored\u001b")
        screen.consume("\\B")

        assertEquals("AB  ", rowText(screen, 0))
    }

    @Test
    fun deleteCharacterRemovesStaleCell() {
        val screen = TerminalScreen(columns = 12, rows = 3)
        screen.consume("WelcomeBback")

        screen.consume("\u001b[1;8H\u001b[P")

        assertEquals("Welcomeback ", rowText(screen, 0))
    }

    @Test
    fun eraseCharactersRemovesOldFrameText() {
        val screen = TerminalScreen(columns = 8, rows = 3)
        screen.consume("oldvalue")

        screen.consume("\u001b[1;4H\u001b[5X")

        assertEquals("old     ", rowText(screen, 0))
    }

    @Test
    fun insertCharacterMakesRoomForRedraw() {
        val screen = TerminalScreen(columns = 8, rows = 3)
        screen.consume("Welcme")

        screen.consume("\u001b[1;5H\u001b[@o")

        assertEquals("Welcome ", rowText(screen, 0))
    }

    @Test
    fun scrollRegionOnlyMovesRowsInsideMargins() {
        val screen = TerminalScreen(columns = 5, rows = 5)
        fillRows(screen, "11111", "22222", "33333", "44444", "55555")

        screen.consume("\u001b[2;4r\u001b[4;1H\n")

        assertEquals("11111", rowText(screen, 0))
        assertEquals("33333", rowText(screen, 1))
        assertEquals("44444", rowText(screen, 2))
        assertEquals("     ", rowText(screen, 3))
        assertEquals("55555", rowText(screen, 4))
        assertEquals(0, screen.scrollbackSize())
    }

    @Test
    fun fullViewportScrollDoesNotStoreHistoryRows() {
        // Real scrolls are login/launcher noise and attach-redraw
        // artifacts; conversation history comes from the redraw-shift
        // detector (TerminalOutputProcessor) and imports.
        val screen = TerminalScreen(columns = 4, rows = 3)
        fillRows(screen, "1111", "2222", "3333")

        screen.consume("\u001b[3;1H\r\n")

        assertEquals(0, screen.scrollbackSize())
    }

    @Test
    fun scrollbackLimitEvictsOldestRows() {
        val screen = TerminalScreen(columns = 4, rows = 3, maxScrollbackRows = 2)
        screen.importScrollbackText(listOf("1111", "2222", "3333"))

        // Import is a replace, not an append; eviction applies to rows
        // appended later (detector captures / further imports).
        screen.appendExternalRows(listOf(textRow("4444"), textRow("5555")))

        assertEquals(2, screen.scrollbackSize())
        val oldestAvailable = screen.snapshot(scrollOffsetRows = 2)
        assertEquals("4444", rowText(oldestAvailable, 0))
        assertEquals("5555", rowText(oldestAvailable, 1))
    }

    @Test
    fun scrollsDoNotCreateHistoryAndPrimaryRestoresOnLeave() {
        val screen = TerminalScreen(columns = 4, rows = 3)
        screen.importScrollbackText(listOf("1111"))
        fillRows(screen, "aaaa", "bbbb", "cccc")
        screen.consume("\u001b[3;1H\r\n")
        // Real scrolls never add history (login noise / redraw artifacts).
        assertEquals(1, screen.scrollbackSize())

        screen.consume("\u001b[?1049h")
        fillRows(screen, "dddd", "eeee", "ffff")
        screen.consume("\u001b[3;1H\r\n")
        assertEquals(1, screen.scrollbackSize())

        screen.consume("\u001b[?1049l")
        // The primary content is restored untouched on leave (it had been
        // shifted up by the pre-alt scroll: "bbbb" is now on top).
        assertEquals("1111", rowText(screen.snapshot(scrollOffsetRows = 1), 0))
        assertEquals("bbbb", rowText(screen, 0))
    }

    @Test
    fun resetResizeAndEraseScrollbackClearHistory() {
        val resetScreen = screenWithOneHistoryRow()
        resetScreen.reset()
        assertEquals(0, resetScreen.scrollbackSize())

        val resizedScreen = screenWithOneHistoryRow()
        resizedScreen.resize(newColumns = 5, newRows = 4)
        assertEquals(0, resizedScreen.scrollbackSize())

        val erasedScreen = screenWithOneHistoryRow()
        erasedScreen.consume("\u001b[3J")
        assertEquals(0, erasedScreen.scrollbackSize())
    }

    @Test
    fun emptyScrollRegionParametersRestoreFullScreenMargins() {
        val screen = TerminalScreen(columns = 5, rows = 5)
        fillRows(screen, "11111", "22222", "33333", "44444", "55555")

        screen.consume("\u001b[2;4r\u001b[r\u001b[5;1H\n")

        assertEquals("22222", rowText(screen, 0))
        assertEquals("33333", rowText(screen, 1))
        assertEquals("44444", rowText(screen, 2))
        assertEquals("55555", rowText(screen, 3))
        assertEquals("     ", rowText(screen, 4))
    }

    @Test
    fun alternateScreenRestoresPrimaryContentsAndCursor() {
        val screen = TerminalScreen(columns = 10, rows = 3)
        screen.consume("primary")
        val primaryCursor = screen.cursor()

        screen.consume("\u001b[?1049halt")
        assertEquals("alt       ", rowText(screen, 0))

        screen.consume("\u001b[?1049l")
        assertEquals("primary   ", rowText(screen, 0))
        assertEquals(primaryCursor, screen.cursor())
    }

    @Test
    fun sgrAttributesAreCapturedPerCell() {
        val screen = TerminalScreen(columns = 6, rows = 2)

        screen.consume("\u001b[1;2;4;7mX\u001b[0mY")

        val row = screen.snapshot()[0]
        assertEquals(TerminalStyle(bold = true, dim = true, underline = true, inverse = true), row[0].style)
        assertEquals(TerminalStyle(), row[1].style)
    }

    @Test
    fun privateCursorModeControlsVisibility() {
        val screen = TerminalScreen(columns = 4, rows = 2)

        screen.consume("\u001b[?25l")
        assertFalse(screen.cursor().visible)

        screen.consume("\u001b[?25h")
        assertTrue(screen.cursor().visible)
    }

    @Test
    fun deletingInsideWideCellDoesNotLeaveMalformedSpan() {
        val screen = TerminalScreen(columns = 5, rows = 2)
        screen.consume("中abc")

        screen.consume("\u001b[1;2H\u001b[P")

        val row = screen.snapshot()[0]
        assertEquals(" abc ", rowText(screen, 0))
        assertTrue(row.none { it.continuation })
        assertTrue(row.none { it.span == 2 })
    }

    @Test
    fun shrinkingThroughWideCellClearsClippedGlyph() {
        val screen = TerminalScreen(columns = 4, rows = 2)
        screen.consume("ab中")

        screen.resize(newColumns = 3, newRows = 2)

        assertEquals("ab ", rowText(screen, 0))
        assertTrue(screen.snapshot()[0].none { it.continuation || it.span == 2 })
    }

    private fun fillRows(screen: TerminalScreen, vararg values: String) {
        values.forEachIndexed { index, value ->
            screen.consume("\u001b[${index + 1};1H$value")
        }
    }

    private fun screenWithOneHistoryRow(): TerminalScreen = TerminalScreen(columns = 4, rows = 3).also {
        it.importScrollbackText(listOf("1111"))
        assertEquals(1, it.scrollbackSize())
    }

    private fun textRow(text: String): Array<TerminalCell> =
        Array(4) { column -> TerminalCell(if (column < text.length) text[column].toString() else " ") }

    private fun rowText(screen: TerminalScreen, row: Int): String = screen.snapshot()[row]
        .joinToString("") { if (it.continuation) "·" else it.text }

    private fun rowText(snapshot: List<List<TerminalCell>>, row: Int): String = snapshot[row]
        .joinToString("") { if (it.continuation) "·" else it.text }

    @Test
    fun scrollbackTextExportImportRoundTrips() {
        val screen = TerminalScreen(columns = 6, rows = 3)
        screen.importScrollbackText(listOf("1111", "aaaa"))

        val exported = screen.exportScrollbackText()
        // Rows keep their full width (trailing spaces) for background fills.
        assertEquals(listOf("1111", "aaaa"), exported.map { it.trimEnd() })

        val restored = TerminalScreen(columns = 6, rows = 3)
        restored.importScrollbackText(exported)
        assertEquals(2, restored.scrollbackSize())
        assertEquals("1111", rowText(restored.snapshot(scrollOffsetRows = 2), 0).trimEnd())
        assertEquals("aaaa", rowText(restored.snapshot(scrollOffsetRows = 1), 0).trimEnd())
    }

    @Test
    fun scrollbackTextRoundTripPreservesWideAndBlankRows() {
        val screen = TerminalScreen(columns = 8, rows = 3)
        screen.importScrollbackText(listOf("中文宽", "", "ab"))
        assertEquals(3, screen.scrollbackSize())

        val restored = TerminalScreen(columns = 8, rows = 3)
        restored.importScrollbackText(screen.exportScrollbackText())
        assertEquals(3, restored.scrollbackSize())
        // Blank separator rows are preserved; wide chars occupy two cells.
        assertEquals("中文宽", rowText(restored.snapshot(scrollOffsetRows = 3), 0).replace("·", "").trimEnd())
        assertEquals("", rowText(restored.snapshot(scrollOffsetRows = 2), 0).replace("·", "").trimEnd())
        assertEquals("ab", rowText(restored.snapshot(scrollOffsetRows = 1), 0).replace("·", "").trimEnd())
    }

    @Test
    fun scrollbackImportIgnoredWhileAlternateScreenActive() {
        val screen = TerminalScreen(columns = 4, rows = 3)
        fillRows(screen, "aaaa", "bbbb", "cccc")
        screen.consume("\u001b[?1049h")

        screen.importScrollbackText(listOf("1111"))

        assertEquals(0, screen.scrollbackSize())
    }

    @Test
    fun scrollbackTextRoundTripPreservesBackgroundFills() {
        val screen = TerminalScreen(columns = 12, rows = 3)
        screen.importScrollbackText(
            listOf("\u001b[48;5;237m❯ hi\u001b[49m", "plain reply"),
        )

        val restored = TerminalScreen(columns = 12, rows = 3)
        restored.importScrollbackText(screen.exportScrollbackText())

        val row = restored.snapshot(scrollOffsetRows = 2)[0]
        assertEquals("❯", row[0].text)
        assertEquals(237, row[0].style.background)
        assertEquals(237, row[3].style.background)
        // After the reset marker the fill stops.
        assertEquals(null, row[4].style.background)
        val plain = restored.snapshot(scrollOffsetRows = 1)[0]
        assertEquals(null, plain[0].style.background)
    }

}
