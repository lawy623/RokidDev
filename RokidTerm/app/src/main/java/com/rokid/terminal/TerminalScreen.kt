package com.rokid.terminal

/**
 * VT-style screen buffer for the controls emitted by tmux and Claude Code.
 * The buffer is independent of Android and can be resized with the remote PTY.
 */
class TerminalScreen(
    columns: Int,
    rows: Int,
    private val maxScrollbackRows: Int = DEFAULT_SCROLLBACK_ROWS,
) {
    var columns: Int = columns
        private set
    var rows: Int = rows
        private set

    private class Buffer(columns: Int, rows: Int) {
        var cells = blankCells(columns, rows)
        var row = 0
        var column = 0
        var savedRow = 0
        var savedColumn = 0
        var scrollTop = 0
        var scrollBottom = rows - 1

        companion object {
            fun blankCells(columns: Int, rows: Int) =
                Array(rows) { Array(columns) { TerminalCell() } }
        }
    }

    private var primary = Buffer(columns, rows)
    private var alternate = Buffer(columns, rows)
    private var active = primary
    private val scrollback = ArrayList<List<TerminalCell>>()
    private var totalScrollbackRowsAppended = 0L
    private var state = State.TEXT
    private val sequence = StringBuilder()
    private var wrapPending = false
    private var autoWrap = true
    private var cursorVisible = true
    private var style = TerminalStyle()
    private var lastPrintedText = " "
    private var lastPrintedWidth = 1
    private var joinNextCodePoint = false

    private enum class State {
        TEXT,
        ESCAPE,
        ESCAPE_INTERMEDIATE,
        CSI,
        STRING,
        STRING_ESCAPE,
    }

    init {
        require(columns > 0 && rows > 0)
        require(maxScrollbackRows >= 0)
    }

    fun consume(input: String) {
        var index = 0
        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            consumeCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
    }

    /** Resize both normal and alternate buffers. Remote full-screen TUIs redraw after SIGWINCH. */
    fun resize(newColumns: Int, newRows: Int): Boolean {
        require(newColumns > 0 && newRows > 0)
        if (newColumns == columns && newRows == rows) return false
        val wasPrimary = active === primary
        primary = resizeBuffer(primary, newColumns, newRows)
        alternate = resizeBuffer(alternate, newColumns, newRows)
        active = if (wasPrimary) primary else alternate
        columns = newColumns
        rows = newRows
        scrollback.clear()
        wrapPending = false
        return true
    }

    private fun resizeBuffer(source: Buffer, newColumns: Int, newRows: Int): Buffer {
        val resized = Buffer(newColumns, newRows)
        val copiedRows = minOf(rows, newRows)
        val copiedColumns = minOf(columns, newColumns)
        for (targetRow in 0 until copiedRows) {
            for (targetColumn in 0 until copiedColumns) {
                resized.cells[targetRow][targetColumn] = source.cells[targetRow][targetColumn]
            }
        }
        resized.row = source.row.coerceIn(0, newRows - 1)
        resized.column = source.column.coerceIn(0, newColumns - 1)
        resized.savedRow = source.savedRow.coerceIn(0, newRows - 1)
        resized.savedColumn = source.savedColumn.coerceIn(0, newColumns - 1)
        val usedFullRegion = source.scrollTop == 0 && source.scrollBottom == rows - 1
        resized.scrollTop = source.scrollTop.coerceIn(0, newRows - 1)
        resized.scrollBottom = if (usedFullRegion) {
            newRows - 1
        } else {
            source.scrollBottom.coerceIn(resized.scrollTop, newRows - 1)
        }
        normalizeWideCells(resized)
        return resized
    }

    fun reset() {
        primary = Buffer(columns, rows)
        alternate = Buffer(columns, rows)
        active = primary
        scrollback.clear()
        state = State.TEXT
        sequence.clear()
        wrapPending = false
        autoWrap = true
        cursorVisible = true
        style = TerminalStyle()
        lastPrintedText = " "
        lastPrintedWidth = 1
        joinNextCodePoint = false
    }

    fun snapshot(scrollOffsetRows: Int = 0): List<List<TerminalCell>> {
        val offset = scrollOffsetRows.coerceIn(0, scrollback.size)
        if (offset == 0) return active.cells.map { it.toList() }

        // Browsing shows the scrollback rows ABOVE the live screen (the
        // screen is appended below, so scrolling up "pulls the full screen
        // up" — user expectation 2026-08-07). No duplication: captured rows
        // are always above the screen, and imported transcripts are trimmed
        // to exclude the turns the screen already shows
        // (trimScrollbackToScreen).
        val viewportStart = scrollback.size - offset
        return List(rows) { viewportRow ->
            val combinedRow = viewportStart + viewportRow
            if (combinedRow < scrollback.size) {
                scrollback[combinedRow].toList()
            } else {
                active.cells[combinedRow - scrollback.size].toList()
            }
        }
    }

    /** Count of user-message rows (❯ with content) on the ACTIVE screen —
     *  the turns the resumed live view already shows. */
    fun activeScreenUserCount(): Int =
        active.cells.count { row -> row.joinToString("") { it.text }.trim().let { it.startsWith("❯") && it.length > 4 } }

    /**
     * Drops the last [count] user-message turns from the scrollback (a turn
     * spans from one ❯ row through the row before the next ❯ row). Called
     * after a resume replay settles: the imported transcript contains the
     * full conversation, and the turns the live screen shows must not be
     * duplicated in the scrollback (2026-08-07).
     */
    fun trimScrollbackTurns(count: Int) {
        if (count <= 0 || scrollback.isEmpty()) return
        val userIndices = scrollback.indices.filter { i ->
            scrollback[i].joinToString("") { it.text }.trim().let { it.startsWith("❯") && it.length > 4 }
        }
        if (userIndices.isEmpty()) return
        val keepUntil = userIndices.getOrNull(userIndices.size - count) ?: return
        if (keepUntil <= 0) {
            scrollback.clear()
            totalScrollbackRowsAppended = 0L
        } else {
            val removed = scrollback.size - keepUntil
            repeat(removed) { scrollback.removeAt(scrollback.lastIndex) }
            if (totalScrollbackRowsAppended > scrollback.size) {
                totalScrollbackRowsAppended = scrollback.size.toLong()
            }
        }
    }

    fun scrollbackSize(): Int = scrollback.size

    fun scrollbackRowsAppended(): Long = totalScrollbackRowsAppended

    /** Whether the alternate screen (Claude TUI) is active. */
    fun isAlternateActive(): Boolean = active === alternate

    /**
     * Snapshot of the active buffer's rows (for scroll detection). Row arrays
     * are copied: later writes mutate the live arrays in place, so consumers
     * that compare this snapshot against a later state need a stable copy.
     * (Cells themselves are immutable and may be shared.)
     */
    fun snapshotRows(): List<Array<TerminalCell>> = active.cells.map { it.copyOf() }

    /** Pushes externally-detected scrolled-out rows into the scrollback. */
    fun appendExternalRows(rows: List<Array<TerminalCell>>) {
        rows.forEach { appendScrollbackRow(it) }
    }

    /**
     * Scrollback rows for app-side persistence. Background styles are encoded
     * inline with the terminal's own SGR vocabulary (`ESC[48;5;Nm` / `ESC[49m`)
     * so user-message blocks keep their fill after import; other attributes
     * are not retained.
     */
    fun exportScrollbackText(): List<String> = scrollback.map { row ->
        val sb = StringBuilder()
        var background: Int? = null
        row.forEach { cell ->
            val bg = cell.style.background
            if (bg != background) {
                if (bg == null) sb.append("\u001b[49m") else sb.append("\u001b[48;5;").append(bg).append('m')
                background = bg
            }
            if (!cell.continuation) sb.append(cell.text)
        }
        if (background != null) sb.append("\u001b[49m")
        // Full row width is kept (trailing spaces included) so background
        // fills spanning the whole block width survive the round trip.
        sb.toString()
    }

    /** Replaces the scrollback with previously persisted rows (text only). */
    fun importScrollbackText(rows: List<String>) {
        if (rows.isEmpty() || active !== primary) return
        scrollback.clear()
        totalScrollbackRowsAppended = 0L
        rows.forEach { appendScrollbackRow(textRow(it)) }
    }

    /**
     * Unconditionally empties the scrollback — unlike [importScrollbackText]
     * it is NOT a no-op for empty rows or while the alternate screen is
     * active. Conversation switching must clear the previous conversation's
     * rows even when the import cannot apply (fixed 2026-08-07: browsing a
     * new conversation showed the previous one's history).
     */
    fun clearScrollback() {
        scrollback.clear()
        totalScrollbackRowsAppended = 0L
    }

    /**
     * Replaces the scrollback even while the alternate screen is active —
     * for rebuilding a resumed conversation's history from the server
     * transcript (2026-08-07). The live alt screen is untouched; the rows
     * become browsable immediately (browsing shows the primary screen +
     * scrollback regardless of the active screen).
     */
    fun importScrollbackTextForce(rows: List<String>) {
        if (rows.isEmpty()) return
        scrollback.clear()
        totalScrollbackRowsAppended = 0L
        rows.forEach { appendScrollbackRow(textRow(it)) }
    }

    private fun textRow(text: String): Array<TerminalCell> {
        val row = Array(columns) { TerminalCell() }
        var column = 0
        var index = 0
        var background: Int? = null
        while (index < text.length && column < columns) {
            when {
                text.startsWith(SGR_BG_256_PREFIX, index) -> {
                    val end = text.indexOf('m', index)
                    if (end > 0) {
                        background = text.substring(index + SGR_BG_256_PREFIX.length, end).toIntOrNull()
                        index = end + 1
                    } else {
                        index = text.length
                    }
                }
                text.startsWith(SGR_BG_RESET, index) -> {
                    background = null
                    index += SGR_BG_RESET.length
                }
                else -> {
                    val codePoint = text.codePointAt(index)
                    index += Character.charCount(codePoint)
                    val width = displayWidth(codePoint)
                    val style = TerminalStyle(background = background)
                    when (width) {
                        1 -> {
                            row[column] = TerminalCell(String(Character.toChars(codePoint)), style = style)
                            column++
                        }
                        2 -> {
                            if (column + 1 >= columns) return row
                            row[column] = TerminalCell(
                                String(Character.toChars(codePoint)),
                                span = 2,
                                style = style,
                            )
                            row[column + 1] = TerminalCell(text = "", continuation = true, style = style)
                            column += 2
                        }
                    }
                }
            }
        }
        return row
    }

    fun cursor(): TerminalCursor = TerminalCursor(
        row = active.row,
        column = active.column,
        visible = cursorVisible,
    )

    fun plainText(): String = active.cells.joinToString("\n") { terminalRow ->
        terminalRow.joinToString("") { cell -> if (cell.continuation) "" else cell.text }.trimEnd()
    }

    private fun consumeCodePoint(codePoint: Int) {
        when (state) {
            State.TEXT -> consumeText(codePoint)
            State.ESCAPE -> consumeEscape(codePoint)
            State.ESCAPE_INTERMEDIATE -> consumeEscapeIntermediate(codePoint)
            State.CSI -> consumeCsi(codePoint)
            State.STRING -> consumeString(codePoint)
            State.STRING_ESCAPE -> state = if (codePoint == '\\'.code) State.TEXT else State.STRING
        }
    }

    private fun consumeText(codePoint: Int) {
        when (codePoint) {
            0x1b -> state = State.ESCAPE
            0x9b -> {
                sequence.clear()
                state = State.CSI
            }
            0x90, 0x98, 0x9d, 0x9e, 0x9f -> state = State.STRING
            '\r'.code -> {
                active.column = 0
                wrapPending = false
            }
            '\n'.code, 0x0b, 0x0c -> {
                lineFeed()
                wrapPending = false
            }
            '\b'.code -> {
                active.column = (active.column - 1).coerceAtLeast(0)
                wrapPending = false
            }
            '\t'.code -> {
                active.column = ((active.column / 8 + 1) * 8).coerceAtMost(columns - 1)
                wrapPending = false
            }
            0x00, 0x07, 0x0e, 0x0f, 0x7f -> Unit
            else -> if (codePoint >= 0x20) putCodePoint(codePoint)
        }
    }

    private fun consumeEscape(codePoint: Int) {
        when (codePoint) {
            '['.code -> {
                sequence.clear()
                state = State.CSI
            }
            ']'.code, 'P'.code, 'X'.code, '^'.code, '_'.code -> {
                sequence.clear()
                state = State.STRING
            }
            '7'.code -> {
                active.savedRow = active.row
                active.savedColumn = active.column
                state = State.TEXT
            }
            '8'.code -> {
                active.row = active.savedRow.coerceIn(0, rows - 1)
                active.column = active.savedColumn.coerceIn(0, columns - 1)
                wrapPending = false
                state = State.TEXT
            }
            'D'.code -> {
                lineFeed()
                state = State.TEXT
            }
            'E'.code -> {
                active.column = 0
                lineFeed()
                state = State.TEXT
            }
            'M'.code -> {
                reverseLineFeed()
                state = State.TEXT
            }
            'c'.code -> {
                reset()
                state = State.TEXT
            }
            else -> {
                state = if (codePoint in 0x20..0x2f) State.ESCAPE_INTERMEDIATE else State.TEXT
            }
        }
    }

    private fun consumeEscapeIntermediate(codePoint: Int) {
        state = when {
            codePoint == 0x1b -> State.ESCAPE
            codePoint in 0x20..0x2f -> State.ESCAPE_INTERMEDIATE
            else -> State.TEXT
        }
    }

    private fun consumeCsi(codePoint: Int) {
        if (codePoint in 0x40..0x7e) {
            applyCsi(codePoint.toChar(), sequence.toString())
            sequence.clear()
            state = State.TEXT
        } else if (codePoint in 0x20..0x3f && sequence.length < 128) {
            sequence.append(codePoint.toChar())
        } else if (codePoint == 0x1b) {
            sequence.clear()
            state = State.ESCAPE
        } else {
            sequence.clear()
            state = State.TEXT
        }
    }

    private fun consumeString(codePoint: Int) {
        when (codePoint) {
            0x07, 0x9c -> state = State.TEXT
            0x1b -> state = State.STRING_ESCAPE
            else -> Unit
        }
    }

    private fun applyCsi(command: Char, raw: String) {
        val privatePrefix = raw.firstOrNull()?.takeIf { it in "?><!" }
        val normalized = if (privatePrefix == null) raw else raw.drop(1)
        val params = if (normalized.isEmpty()) {
            emptyList()
        } else {
            normalized.split(';').map { it.substringBefore(':').toIntOrNull() ?: 0 }
        }
        fun param(index: Int, fallback: Int = 1): Int = params.getOrNull(index)?.takeIf { it != 0 } ?: fallback

        when (command) {
            'A' -> active.row = (active.row - param(0)).coerceAtLeast(0)
            'B', 'e' -> active.row = (active.row + param(0)).coerceAtMost(rows - 1)
            'C', 'a' -> active.column = (active.column + param(0)).coerceAtMost(columns - 1)
            'D' -> active.column = (active.column - param(0)).coerceAtLeast(0)
            'E' -> {
                active.row = (active.row + param(0)).coerceAtMost(rows - 1)
                active.column = 0
            }
            'F' -> {
                active.row = (active.row - param(0)).coerceAtLeast(0)
                active.column = 0
            }
            'G', '`' -> active.column = (param(0) - 1).coerceIn(0, columns - 1)
            'd' -> active.row = (param(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                active.row = (param(0) - 1).coerceIn(0, rows - 1)
                active.column = (param(1) - 1).coerceIn(0, columns - 1)
            }
            'J' -> eraseDisplay(params.firstOrNull() ?: 0)
            'K' -> eraseLine(params.firstOrNull() ?: 0)
            'X' -> eraseCharacters(param(0))
            '@' -> insertCharacters(param(0))
            'P' -> deleteCharacters(param(0))
            'L' -> insertLines(param(0))
            'M' -> deleteLines(param(0))
            'S' -> scrollUp(param(0))
            'T' -> scrollDown(param(0))
            'b' -> repeat(param(0)) { put(lastPrintedText, lastPrintedWidth) }
            'r' -> setScrollRegion(param(0), param(1, rows))
            's' -> {
                active.savedRow = active.row
                active.savedColumn = active.column
            }
            'u' -> {
                active.row = active.savedRow.coerceIn(0, rows - 1)
                active.column = active.savedColumn.coerceIn(0, columns - 1)
            }
            'm' -> applySgr(params)
            'h', 'l' -> applyMode(privatePrefix, params, command == 'h')
            'c', 'g', 'n', 'q', 't' -> Unit
        }
        if (command !in setOf('m', 'h', 'l', 'n', 't')) wrapPending = false
    }

    private fun applyMode(privatePrefix: Char?, params: List<Int>, enabled: Boolean) {
        if (privatePrefix != '?') return
        params.forEach { mode ->
            when (mode) {
                7 -> autoWrap = enabled
                25 -> cursorVisible = enabled
                47, 1047, 1049 -> if (enabled) enterAlternateScreen() else leaveAlternateScreen()
            }
        }
    }

    private fun enterAlternateScreen() {
        if (active === alternate) return
        primary.savedRow = primary.row
        primary.savedColumn = primary.column
        alternate = Buffer(columns, rows)
        active = alternate
        wrapPending = false
    }

    private fun leaveAlternateScreen() {
        if (active === primary) return
        active = primary
        primary.row = primary.savedRow.coerceIn(0, rows - 1)
        primary.column = primary.savedColumn.coerceIn(0, columns - 1)
        wrapPending = false
    }

    private fun applySgr(params: List<Int>) {
        val values = if (params.isEmpty()) listOf(0) else params
        var i = 0
        while (i < values.size) {
            val value = values[i]
            when (value) {
                0 -> style = TerminalStyle()
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                4, 21 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                22 -> style = style.copy(bold = false, dim = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                48 -> {
                    // Background color: 48;5;N (256 palette) or 48;2;r;g;b.
                    if (i + 2 < values.size && values[i + 1] == 5) {
                        style = style.copy(background = values[i + 2])
                        i += 2
                    } else if (i + 5 < values.size && values[i + 1] == 2) {
                        val rgb = (values[i + 2] and 0xFF shl 16) or
                            (values[i + 3] and 0xFF shl 8) or (values[i + 4] and 0xFF)
                        style = style.copy(background = rgb)
                        i += 4
                    }
                }
                49 -> style = style.copy(background = null)
            }
            i++
        }
    }

    private fun setScrollRegion(topOneBased: Int, bottomOneBased: Int) {
        val top = (topOneBased - 1).coerceIn(0, rows - 1)
        val bottom = (bottomOneBased - 1).coerceIn(0, rows - 1)
        if (top < bottom) {
            active.scrollTop = top
            active.scrollBottom = bottom
            active.row = 0
            active.column = 0
        }
    }

    private fun putCodePoint(codePoint: Int) {
        val text = String(Character.toChars(codePoint))
        val width = displayWidth(codePoint)
        if (width == 0 || joinNextCodePoint) {
            appendToPreviousCell(text)
            joinNextCodePoint = codePoint == 0x200d
            return
        }
        put(text, width)
        lastPrintedText = text
        lastPrintedWidth = width
    }

    private fun appendToPreviousCell(text: String) {
        var targetRow = active.row
        var targetColumn = when {
            wrapPending -> active.column
            active.column > 0 -> active.column - 1
            targetRow > 0 -> columns - 1
            else -> return
        }
        if (!wrapPending && active.column == 0 && targetRow > 0) targetRow--
        if (active.cells[targetRow][targetColumn].continuation && targetColumn > 0) targetColumn--
        val target = active.cells[targetRow][targetColumn]
        if (!target.continuation && target.text != " ") {
            active.cells[targetRow][targetColumn] = target.copy(text = target.text + text)
        }
    }

    private fun put(text: String, width: Int) {
        val safeWidth = width.coerceIn(1, minOf(2, columns))
        if (wrapPending && autoWrap) {
            active.column = 0
            lineFeed()
            wrapPending = false
        } else if (wrapPending) {
            wrapPending = false
        }
        if (safeWidth == 2 && active.column == columns - 1) {
            if (autoWrap) {
                active.column = 0
                lineFeed()
            } else {
                return
            }
        }

        clearWideCellAt(active, active.row, active.column)
        active.cells[active.row][active.column] = TerminalCell(text, span = safeWidth, style = style)
        if (safeWidth == 2) {
            clearWideCellAt(active, active.row, active.column + 1)
            active.cells[active.row][active.column + 1] = TerminalCell(
                text = "",
                continuation = true,
                span = 0,
                style = style,
            )
        }
        val nextColumn = active.column + safeWidth
        if (nextColumn >= columns) {
            active.column = columns - 1
            wrapPending = autoWrap
        } else {
            active.column = nextColumn
        }
    }

    private fun clearWideCellAt(buffer: Buffer, targetRow: Int, targetColumn: Int) {
        val cell = buffer.cells[targetRow][targetColumn]
        if (cell.continuation && targetColumn > 0) {
            buffer.cells[targetRow][targetColumn - 1] = TerminalCell()
        }
        if (cell.span == 2 && targetColumn + 1 < columns) {
            buffer.cells[targetRow][targetColumn + 1] = TerminalCell()
        }
        buffer.cells[targetRow][targetColumn] = TerminalCell()
    }

    private fun lineFeed() {
        when {
            active.row == active.scrollBottom -> scrollRegionUp(1)
            active.row < rows - 1 -> active.row++
        }
    }

    private fun reverseLineFeed() {
        when {
            active.row == active.scrollTop -> scrollRegionDown(1)
            active.row > 0 -> active.row--
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            1 -> {
                for (targetRow in 0 until active.row) clearRow(active, targetRow)
                for (targetColumn in 0..active.column) clearWideCellAt(active, active.row, targetColumn)
            }
            2 -> clearAll(active)
            3 -> {
                clearAll(active)
                scrollback.clear()
            }
            else -> {
                for (targetColumn in active.column until columns) {
                    clearWideCellAt(active, active.row, targetColumn)
                }
                for (targetRow in active.row + 1 until rows) clearRow(active, targetRow)
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            1 -> for (targetColumn in 0..active.column) {
                clearWideCellAt(active, active.row, targetColumn)
            }
            2 -> clearRow(active, active.row)
            else -> for (targetColumn in active.column until columns) {
                clearWideCellAt(active, active.row, targetColumn)
            }
        }
    }

    private fun eraseCharacters(count: Int) {
        val end = (active.column + count).coerceAtMost(columns)
        for (targetColumn in active.column until end) clearWideCellAt(active, active.row, targetColumn)
    }

    private fun insertCharacters(count: Int) {
        val amount = count.coerceIn(1, columns - active.column)
        val current = active.cells[active.row].copyOf()
        for (targetColumn in columns - 1 downTo active.column) {
            val source = targetColumn - amount
            active.cells[active.row][targetColumn] = if (source >= active.column) current[source] else TerminalCell()
        }
        normalizeWideCells(active, active.row)
    }

    private fun deleteCharacters(count: Int) {
        val amount = count.coerceIn(1, columns - active.column)
        val current = active.cells[active.row].copyOf()
        for (targetColumn in active.column until columns) {
            val source = targetColumn + amount
            active.cells[active.row][targetColumn] = if (source < columns) current[source] else TerminalCell()
        }
        normalizeWideCells(active, active.row)
    }

    private fun insertLines(count: Int) {
        if (active.row !in active.scrollTop..active.scrollBottom) return
        val amount = count.coerceIn(1, active.scrollBottom - active.row + 1)
        for (targetRow in active.scrollBottom downTo active.row) {
            val source = targetRow - amount
            active.cells[targetRow] = if (source >= active.row) {
                active.cells[source]
            } else {
                Array(columns) { TerminalCell() }
            }
        }
    }

    private fun deleteLines(count: Int) {
        if (active.row !in active.scrollTop..active.scrollBottom) return
        val amount = count.coerceIn(1, active.scrollBottom - active.row + 1)
        for (targetRow in active.row..active.scrollBottom) {
            val source = targetRow + amount
            active.cells[targetRow] = if (source <= active.scrollBottom) {
                active.cells[source]
            } else {
                Array(columns) { TerminalCell() }
            }
        }
    }

    private fun scrollUp(count: Int) = scrollRegionUp(count)

    private fun scrollDown(count: Int) = scrollRegionDown(count)

    private fun scrollRegionUp(count: Int) {
        repeat(count.coerceIn(1, active.scrollBottom - active.scrollTop + 1)) {
            // No scrollback capture from real scrolls (2026-08-06): primary-
            // screen scrolls are login/launcher noise, and alternate-screen
            // scrolls are attach-redraw artifacts that duplicate the live
            // screen. Claude conversation history is captured by the
            // redraw-shift detector in TerminalOutputProcessor instead.
            for (targetRow in active.scrollTop until active.scrollBottom) {
                active.cells[targetRow] = active.cells[targetRow + 1]
            }
            active.cells[active.scrollBottom] = Array(columns) { TerminalCell() }
        }
    }

    private fun scrollRegionDown(count: Int) {
        repeat(count.coerceIn(1, active.scrollBottom - active.scrollTop + 1)) {
            for (targetRow in active.scrollBottom downTo active.scrollTop + 1) {
                active.cells[targetRow] = active.cells[targetRow - 1]
            }
            active.cells[active.scrollTop] = Array(columns) { TerminalCell() }
        }
    }

    private fun appendScrollbackRow(row: Array<TerminalCell>) {
        totalScrollbackRowsAppended++
        if (maxScrollbackRows == 0) return
        if (scrollback.size == maxScrollbackRows) scrollback.removeAt(0)
        scrollback += row.toList()
    }

    private fun normalizeWideCells(buffer: Buffer) {
        for (targetRow in buffer.cells.indices) normalizeWideCells(buffer, targetRow)
    }

    private fun normalizeWideCells(buffer: Buffer, targetRow: Int) {
        val terminalRow = buffer.cells[targetRow]
        for (targetColumn in terminalRow.indices) {
            val cell = terminalRow[targetColumn]
            if (cell.span == 2) {
                val valid = targetColumn + 1 < terminalRow.size &&
                    terminalRow[targetColumn + 1].continuation
                if (!valid) terminalRow[targetColumn] = TerminalCell()
            } else if (cell.continuation) {
                val valid = targetColumn > 0 && terminalRow[targetColumn - 1].span == 2
                if (!valid) terminalRow[targetColumn] = TerminalCell()
            }
        }
    }

    private fun clearAll(buffer: Buffer) {
        for (targetRow in 0 until rows) clearRow(buffer, targetRow)
    }

    private fun clearRow(buffer: Buffer, targetRow: Int) {
        buffer.cells[targetRow] = Array(columns) { TerminalCell() }
    }

    private fun displayWidth(codePoint: Int): Int {
        if (
            codePoint == 0x200d || codePoint in 0xfe00..0xfe0f ||
            codePoint in 0xe0100..0xe01ef || codePoint in 0x1f3fb..0x1f3ff
        ) return 0
        return when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> 0
            else -> if (isWide(codePoint)) 2 else 1
        }
    }

    private fun isWide(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115f || codePoint == 0x2329 || codePoint == 0x232a ||
            codePoint in 0x2e80..0xa4cf || codePoint in 0xac00..0xd7a3 ||
            codePoint in 0xf900..0xfaff || codePoint in 0xfe10..0xfe19 ||
            codePoint in 0xfe30..0xfe6f || codePoint in 0xff00..0xff60 ||
            codePoint in 0xffe0..0xffe6 || codePoint in 0x1f1e6..0x1f1ff ||
            codePoint in 0x1f300..0x1faff || codePoint in 0x20000..0x3fffd

    companion object {
        const val DEFAULT_SCROLLBACK_ROWS = 5000

        /** Inline SGR markers used by scrollback persistence for background style. */
        private const val SGR_BG_256_PREFIX = "\u001b[48;5;"
        private const val SGR_BG_RESET = "\u001b[49m"
    }
}
