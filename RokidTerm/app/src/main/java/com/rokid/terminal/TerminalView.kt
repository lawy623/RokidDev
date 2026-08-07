package com.rokid.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

class TerminalView(context: Context) : View(context) {
    /**
     * True while an external Bluetooth keyboard (e.g. COIDEA KM) is connected.
     * Shown as a small keyboard glyph at the bottom-right of the footer so the
     * user knows typed input is available. Updated from MainActivity via
     * [setKeyboardConnected]; detection needs no Bluetooth permission because
     * it uses the InputManager device list.
     */
    var keyboardConnected: Boolean = false
        private set

    fun setKeyboardConnected(connected: Boolean) {
        if (keyboardConnected != connected) {
            keyboardConnected = connected
            invalidate()
        }
    }

    /** True while an INMO ring is connected; shown as a tilted ring glyph. */
    var ringConnected: Boolean = false
        private set

    fun setRingConnected(connected: Boolean) {
        if (ringConnected != connected) {
            ringConnected = connected
            invalidate()
        }
    }

    /**
     * Input-history draft currently browsed (COIDEA keys 4/6); rendered into
     * the Claude Code "❯" input line, replacing the "_" cursor. Null hides
     * the preview.
     */
    private var historyPreviewText: String? = null

    fun setHistoryPreview(text: String?) {
        if (historyPreviewText != text) {
            historyPreviewText = text
            invalidate()
        }
    }

    // Blinking "_" input cursor on the Claude Code "❯" line while no draft
    // is previewed. The remote cursor is static through tmux, so we animate
    // it locally.
    private var blinkOn = true

    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            postDelayed(this, 500L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blinkRunnable, 500L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }
    enum class Screen { ENDPOINTS, TERMINAL }

    private data class GridGeometry(
        val viewport: TerminalViewport,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val cellWidth: Float,
        val rowHeight: Float,
        val baselineOffset: Float,
    )

    private data class ComposerLine(
        val start: Int,
        val end: Int,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        typeface = Typeface.MONOSPACE
        letterSpacing = 0f
    }
    private var terminalFrame = TerminalFrame.empty(
        columns = TerminalSpec.DEFAULT_COLUMNS,
        rows = TerminalSpec.DEFAULT_ROWS,
    )
    private var viewportListener: ((TerminalViewport) -> Unit)? = null
    private var geometry: GridGeometry? = null
    private var state = "NOT CONFIGURED"
    private var screen = Screen.ENDPOINTS
    private var endpoints: List<EndpointProfile> = emptyList()
    private var selectedEndpoint = 0
    private var activeEndpoint: EndpointProfile? = null
    private var composerVisible = false
    private var composerText = ""
    private var composerCursor = 0
    private var commandPaletteOpen = false
    private var commandPaletteItems: List<String> = emptyList()
    private var commandPaletteSelected = 0
    private var sessionPickerUi = SessionPickerUi()

    /** Command palette state for the composer overlay (modal list). */
    fun setCommandPalette(items: List<String>, selected: Int, open: Boolean) {
        commandPaletteOpen = open
        commandPaletteItems = items
        commandPaletteSelected = selected
        invalidate()
    }

    /** Conversation-picker state for the modal overlay. */
    fun setSessionPicker(ui: SessionPickerUi) {
        sessionPickerUi = ui
        invalidate()
    }

    /** Current composer cursor (for pixel-based vertical moves). */
    fun composerCursor(): Int = composerCursor

    /**
     * Vertical cursor move using the SAME pixel-based wrapping as the
     * renderer (buildComposerLines), so jumps between visual lines land on
     * the same column. Logical-column wrapping (InputComposerState.moveUp/
     * moveDown) mismatches the renderer for CJK text.
     */
    fun moveCursorVertical(direction: Int): Boolean {
        val text = composerText
        if (text.isEmpty() || direction == 0) return false
        // Must match drawComposer's glyph size exactly, or measureText-based
        // wrapping miscomputes rows (paint.textSize is a leftover from the
        // last draw otherwise).
        paint.textSize = 16f
        val textLeft = 24f + 12f
        val textRight = width - 24f - 10f - 8f
        val lines = buildComposerLines(text, textRight - textLeft)
        if (lines.size <= 1) return false
        val cursorLine = lines.indexOfLast { composerCursor >= it.start }
            .coerceAtLeast(0)
            .coerceAtMost(lines.lastIndex)
        val targetLine = (cursorLine + direction).coerceIn(0, lines.lastIndex)
        if (targetLine == cursorLine) return false
        val cursorX = paint.measureText(text.substring(lines[cursorLine].start, composerCursor))
        var best = lines[targetLine].start
        var bestX = 0f
        var i = lines[targetLine].start
        while (i < lines[targetLine].end) {
            val next = com.rokid.terminal.GraphemeText.nextBoundary(text, i)
            val w = paint.measureText(text.substring(i, next))
            if (bestX + w > cursorX) break
            bestX += w
            best = next
            i = next
        }
        composerCursor = best
        return true
    }
    private var composerStatus = ""
    private var composerFirstVisibleLine = 0

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
    }

    fun setOnViewportChangedListener(listener: (TerminalViewport) -> Unit) {
        viewportListener = listener
        geometry?.let { listener(it.viewport) }
    }

    fun setState(value: String) {
        state = value
        invalidate()
    }

    fun showComposer(text: String, cursor: Int, status: String) {
        if (!composerVisible) composerFirstVisibleLine = 0
        composerVisible = true
        composerText = text
        composerCursor = cursor.coerceIn(0, text.length)
        composerStatus = status
        invalidate()
    }

    fun hideComposer() {
        composerVisible = false
        composerText = ""
        composerCursor = 0
        composerStatus = ""
        composerFirstVisibleLine = 0
        invalidate()
    }

    fun showEndpoints(values: List<EndpointProfile>, selected: Int) {
        screen = Screen.ENDPOINTS
        composerVisible = false
        composerFirstVisibleLine = 0
        endpoints = values
        selectedEndpoint = selected.coerceIn(0, (values.size - 1).coerceAtLeast(0))
        invalidate()
    }

    fun showTerminal(endpoint: EndpointProfile, frame: TerminalFrame) {
        screen = Screen.TERMINAL
        activeEndpoint = endpoint
        terminalFrame = frame
        composerVisible = false
        composerFirstVisibleLine = 0
        invalidate()
    }

    fun setTerminalFrame(frame: TerminalFrame) {
        if (frame.revision < terminalFrame.revision) return
        terminalFrame = frame
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val viewport = TerminalSpec.viewportFor(width, height)
        val left = TerminalSpec.SIDE_PADDING
        val right = (width - TerminalSpec.SIDE_PADDING).coerceAtLeast(left + 1f)
        val top = TerminalSpec.TERMINAL_TOP
        val bottom = (height - TerminalSpec.FOOTER_HEIGHT).coerceAtLeast(top + 1f)
        val next = GridGeometry(
            viewport = viewport,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            cellWidth = (right - left) / viewport.columns,
            rowHeight = (bottom - top) / viewport.rows,
            baselineOffset = min(11f, (bottom - top) / viewport.rows * 0.78f),
        )
        val changed = geometry?.viewport != viewport
        geometry = next
        if (changed) viewportListener?.invoke(viewport)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        resetPaint()
        paint.textSize = 22f
        canvas.drawText("ROKID TERMINAL", 24f, 42f, paint)

        if (screen == Screen.ENDPOINTS) {
            drawEndpoints(canvas)
            return
        }

        paint.alpha = 180
        paint.textSize = 15f
        val target = activeEndpoint?.name ?: "Unknown"
        canvas.drawText("$target  |  ${state.take(34)}", 24f, 70f, paint)
        canvas.drawLine(24f, 84f, width - 24f, 84f, paint)

        geometry?.let { drawTerminal(canvas, it) }

        val footerLine = height - TerminalSpec.FOOTER_HEIGHT
        resetPaint()
        paint.alpha = 180
        canvas.drawLine(24f, footerLine, width - 24f, footerLine, paint)
        paint.alpha = 170
        paint.textSize = 11f
        canvas.drawText(
            if (composerVisible) {
                "COMPOSER OPEN   REMOTE TERMINAL STILL LIVE"
            } else if (terminalFrame.scrollOffsetRows > 0) {
                buildString {
                    append("HISTORY -")
                    append(terminalFrame.scrollOffsetRows)
                    append("   SWIPE NEWER")
                    if (terminalFrame.hasNewOutput) append("   NEW OUTPUT")
                }
            } else {
                "SWIPE HISTORY   CLICK INPUT   BACK TARGETS"
            },
            24f,
            footerLine + 22f,
            paint,
        )

        if (composerVisible) {
            drawComposer(canvas)
        } else if (historyPreviewText != null) {
            drawHistoryPreview(canvas)
        } else if (blinkOn) {
            drawBlinkingCursor(canvas)
        }

        if (keyboardConnected) drawKeyboardIcon(canvas, footerLine)
        if (ringConnected) drawRingIcon(canvas, footerLine)

        // Popup banner when new output arrives while browsing history.
        if (!composerVisible && historyPreviewText == null &&
            terminalFrame.scrollOffsetRows > 0 && terminalFrame.hasNewOutput
        ) {
            drawNewOutputBanner(canvas, footerLine)
        }

        if (sessionPickerUi.open) drawSessionPicker(canvas)
    }

    /** Small popup box above the footer announcing new output while viewing history. */
    private fun drawNewOutputBanner(canvas: Canvas, footerLine: Float) {
        val text = "NEW OUTPUT"
        paint.textSize = 13f
        val w = paint.measureText(text) + 26f
        val h = 28f
        val left = width - 24f - w
        val top = footerLine - h - 8f
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.alpha = 246
        canvas.drawRect(left, top, left + w, top + h, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.GREEN
        paint.alpha = 210
        canvas.drawRect(left, top, left + w, top + h, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.GREEN
        paint.alpha = 235
        canvas.drawText(text, left + 13f, top + h / 2f + 4.5f, paint)
        resetPaint()
    }

    /**
     * Tilted ring glyph like 💍 but without the gem: a tilted band with two
     * prongs rising from the top (the setting). Shown to the left of the
     * keyboard glyph while an INMO ring is connected.
     */
    private fun drawRingIcon(canvas: Canvas, footerLine: Float) {
        val size = 20f
        val right = width - 24f - (if (keyboardConnected) 26f + 10f else 0f)
        val cx = right - size / 2f
        val cy = footerLine + 5f + size / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.GREEN
        paint.alpha = 190
        canvas.save()
        canvas.rotate(-30f, cx, cy)
        val bandRadius = size / 2f - 2f
        canvas.drawCircle(cx, cy, bandRadius, paint)
        // Two prongs at the top of the band forming a V (the setting, no gem).
        val topX = cx
        val topY = cy - bandRadius
        canvas.drawLine(topX - 3f, topY, topX, topY - 4.5f, paint)
        canvas.drawLine(topX + 3f, topY, topX, topY - 4.5f, paint)
        canvas.restore()
    }

    /**
     * Input-history preview rendered INTO the Claude Code input line of the
     * terminal grid — the row whose content starts with "> " (the "> _"
     * prompt sits above the "bypass permissions" banner, not on the last
     * grid row). The row is covered with black and redrawn as "> {draft}".
     * Falls back to the last grid row when no "> " row is found. The draft
     * only loads into the composer when the user confirms (TP click /
     * left-knob press).
     */
    /** Text of one terminal grid row (continuation cells skipped). */
    private fun rowText(row: Int): String = buildString {
        val cells = terminalFrame.cells[row]
        for (column in cells.indices) {
            if (!cells[column].continuation) append(cells[column].text)
        }
    }

    /**
     * Detects the open picker's primary axis from the frame content:
     * numbered option rows ("1. ", "2. ", …) above the input line mean a
     * VERTICAL list (e.g. /model); anything else (e.g. /effort's "←/→ to
     * adjust" slider, or the first level of a 2D picker like /usage) is
     * HORIZONTAL. Used to adapt the glasses'/ring's single swipe gesture to
     * the picker's axis (user decision 2026-08-06).
     */
    fun pickerAxis(): PickerAxis {
        val frame = terminalFrame
        if (frame == null || frame.rows <= 0 || frame.cells.isEmpty()) return PickerAxis.HORIZONTAL
        val inputRow = findInputRow() ?: (frame.rows - 2)
        val start = (inputRow - 12).coerceAtLeast(0)
        val end = inputRow.coerceAtMost(frame.cells.size)
        var numberedRows = 0
        for (row in start until end) {
            if (NUMBERED_ITEM.containsMatchIn(rowText(row))) numberedRows++
        }
        return if (numberedRows >= 2) PickerAxis.VERTICAL else PickerAxis.HORIZONTAL
    }

    enum class PickerAxis { VERTICAL, HORIZONTAL }

    /**
     * Bounce direction for a glasses swipe when the picker focus sits off
     * the numbered item list (e.g. /model's "◈ Max effort ←/→ to adjust"
     * slider at the bottom or the header at the top): +1 = send down (focus
     * above the list), -1 = send up (focus below the list), null = the
     * focus is on a numbered item (or no vertical list detected).
     */
    fun pickerBounceDirection(): Int? {
        val frame = terminalFrame ?: return null
        val inputRow = findInputRow() ?: (frame.rows - 2)
        val start = (inputRow - 14).coerceAtLeast(0)
        // Include the focused row itself: while the picker is open the
        // "input row" IS the focus marker (e.g. the effort slider row when
        // the selection wrapped there), and it must be inspected to bounce.
        val end = (inputRow + 1).coerceAtMost(frame.cells.size)
        var focusedRow = -1
        var firstItem = -1
        var lastItem = -1
        for (row in start until end) {
            val text = rowText(row)
            if (text.trimStart().startsWith("❯")) focusedRow = row
            if (NUMBERED_ITEM.containsMatchIn(text)) {
                if (firstItem < 0) firstItem = row
                lastItem = row
            }
        }
        if (focusedRow < 0 || firstItem < 0) return null
        return when {
            focusedRow < firstItem -> 1
            focusedRow > lastItem -> -1
            else -> null
        }
    }

    /**
     * Grid row of the Claude Code input line ("❯ …"), or null. Claude also
     * renders CONVERSATION user messages with a "❯" prefix, so the input
     * line cannot be found by "last ❯ row" alone — prefer the bottom area
     * (the input line sits just above the "bypass permissions" banner and
     * tmux status line), then fall back to any ❯ row, then the row above
     * the banner.
     */
    private fun findInputRow(): Int? {
        val rows = terminalFrame.cells.size
        // While browsing history, the live input line sits at
        // cursor.row + offset in the viewport; once it scrolls out, no row
        // is the input line — every remaining ❯ row is a conversation user
        // message (and must keep its block fill, 2026-08-06).
        if (terminalFrame.scrollOffsetRows > 0) {
            return (terminalFrame.cursor.row + terminalFrame.scrollOffsetRows)
                .takeIf { it in 0 until rows }
        }
        for (row in (rows - 1) downTo (rows - 5).coerceAtLeast(0)) {
            if (rowText(row).contains("❯")) return row
        }
        for (row in rows - 1 downTo 0) {
            if (rowText(row).contains("❯")) return row
        }
        // No ❯ visible (e.g. Claude busy): use the row above the status line.
        return (rows - 2).takeIf { it >= 0 }
    }

    /**
     * Text after the "❯" prompt on the Claude Code input line — Claude's
     * next-input suggestion (light text). Null when the line is empty.
     */
    fun inputLineText(): String? {
        val row = findInputRow() ?: return null
        val line = rowText(row)
        val idx = line.indexOf("❯")
        if (idx < 0) return null
        return line.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Blinking "_" on the "❯" input line while idle. Claude Code itself
     * renders a static "_" there, so we cover that cell with black and draw
     * our own blinking "_" over it — exactly one cursor, toggling.
     */
    private fun drawBlinkingCursor(canvas: Canvas) {
        val grid = geometry ?: return
        val row = findInputRow() ?: return
        val rowTop = grid.top + row * grid.rowHeight
        paint.textSize = min(13f, grid.rowHeight * 0.8f)
        val line = rowText(row)
        val promptEnd = line.indexOf("❯")
        val prefix = if (promptEnd >= 0) line.substring(0, promptEnd + 1) else "❯"
        // A small gap after the prompt so the blinking cursor does not hug
        // the "❯" glyph (user feedback 2026-08-05).
        val x = grid.left + 2f + paint.measureText(prefix) + grid.cellWidth * 0.35f
        val underscoreWidth = paint.measureText("_")
        // Cover the remote static "_" (plus a small margin) with black.
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.alpha = 255
        canvas.drawRect(x - 1f, rowTop + 1f, x + underscoreWidth + 1f, rowTop + grid.rowHeight - 1f, paint)
        if (blinkOn) {
            paint.style = Paint.Style.FILL
            paint.color = Color.GREEN
            paint.alpha = 230
            canvas.drawText("_", x, rowTop + grid.baselineOffset, paint)
        }
    }

    private fun drawHistoryPreview(canvas: Canvas) {
        val preview = historyPreviewText ?: return
        val grid = geometry ?: return
        if (grid.viewport.rows <= 0) return

        val targetRow = findInputRow() ?: (grid.viewport.rows - 1)
        val rowTop = grid.top + targetRow * grid.rowHeight
        val rowBottom = rowTop + grid.rowHeight

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.alpha = 255
        canvas.drawRect(grid.left, rowTop, grid.right, rowBottom, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.GREEN
        paint.alpha = 230
        paint.textSize = min(13f, grid.rowHeight * 0.8f)
        // Truncate by measured pixel width — character counts are wrong for
        // full-width (CJK) drafts.
        val maxWidth = grid.right - grid.left - 6f
        val promptWidth = paint.measureText("❯ ")
        val sb = StringBuilder()
        var width = 0f
        var truncated = false
        for (ch in preview) {
            val cw = paint.measureText(ch.toString())
            if (promptWidth + width + cw > maxWidth) {
                truncated = true
                break
            }
            sb.append(ch)
            width += cw
        }
        val shown = if (truncated) sb.toString() + "…" else preview
        // Keep Claude Code's "❯" prompt, render the draft after it, and
        // never draw the "_" cursor while a history draft is being browsed.
        canvas.drawText("❯ $shown", grid.left + 2f, rowTop + grid.baselineOffset, paint)
    }

    private fun drawKeyboardIcon(canvas: Canvas, footerLine: Float) {
        // Compact keyboard glyph right-aligned on the footer text line:
        // a rounded frame plus two rows of keycap dots.
        val w = 26f
        val h = 17f
        val left = width - 24f - w
        val top = footerLine + 5f
        val bottom = top + h
        paint.color = Color.GREEN
        paint.alpha = 170
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        canvas.drawRoundRect(left, top, left + w, bottom, 2.5f, 2.5f, paint)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        val cols = 4
        val rows = 2
        val stepX = (w - 8f) / cols
        val stepY = (h - 8f) / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                canvas.drawCircle(left + 4f + c * stepX, top + 4f + r * stepY, 1.4f, paint)
            }
        }
    }

    private fun drawTerminal(canvas: Canvas, grid: GridGeometry) {
        val frame = terminalFrame
        if (frame.columns <= 0 || frame.rows <= 0) return
        val cellWidth = (grid.right - grid.left) / frame.columns
        val rowHeight = (grid.bottom - grid.top) / frame.rows
        val baselineOffset = min(grid.baselineOffset, rowHeight * 0.78f)
        paint.textSize = min(12f, rowHeight * 0.78f)

        val drawnRows = minOf(frame.rows, frame.cells.size)
        val inputRow = findInputRow()
        for (rowIndex in 0 until drawnRows) {
            val terminalRow = frame.cells[rowIndex]
            val drawnColumns = minOf(frame.columns, terminalRow.size)
            for (columnIndex in 0 until drawnColumns) {
                val cell = terminalRow[columnIndex]
                if (cell.continuation) continue
                val x = grid.left + columnIndex * cellWidth
                val top = grid.top + rowIndex * rowHeight
                val spanWidth = cellWidth * cell.span.coerceAtLeast(1)
                // Conversation user messages render with a "❯" prefix too;
                // draw a small box instead so they are distinguishable from
                // the real input line (user decision 2026-08-06).
                if (cell.text == "❯" && rowIndex != inputRow && columnIndex == 0) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.GREEN
                    paint.alpha = 60
                    if (cell.style.background != null) {
                        canvas.drawRect(x, top, x + spanWidth, top + rowHeight, paint)
                    } else {
                        // Imported history rows lost their SGR background;
                        // infer the standard user-block fill so old rows
                        // match live ones (2026-08-06).
                        canvas.drawRect(grid.left, top, grid.right, top + rowHeight, paint)
                    }
                    paint.alpha = 230
                    canvas.drawRect(
                        x + cellWidth * 0.25f,
                        top + rowHeight * 0.3f,
                        x + cellWidth * 0.65f,
                        top + rowHeight * 0.7f,
                        paint,
                    )
                    continue
                }
                if (rowIndex == inputRow) {
                    // The input line keeps its original look: raw "❯" prompt,
                    // no dark fill, blinking "_" cursor (user decision
                    // 2026-08-06 — only conversation messages get boxes).
                    paint.color = Color.GREEN
                    paint.alpha = if (cell.style.dim) 120 else 230
                } else if (cell.style.background != null) {
                    // SGR background (e.g. Claude Code's user-message block):
                    // rendered as a light green fill so input is visually
                    // separated from output in the monochrome display.
                    paint.color = Color.GREEN
                    paint.alpha = 60
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(x, top, x + spanWidth, top + rowHeight, paint)
                    paint.color = Color.GREEN
                    paint.alpha = 230
                } else if (cell.style.inverse) {
                    paint.color = Color.GREEN
                    paint.alpha = if (cell.style.dim) 140 else 230
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(x, top, x + spanWidth, top + rowHeight, paint)
                    paint.color = Color.BLACK
                    paint.alpha = 255
                } else {
                    paint.color = Color.GREEN
                    paint.alpha = if (cell.style.dim) 120 else 230
                }
                paint.isFakeBoldText = cell.style.bold
                val visibleText = TerminalRenderPolicy.visibleText(cell.text)
                if (visibleText != " " && visibleText.isNotEmpty()) {
                    canvas.drawText(visibleText, x, top + baselineOffset, paint)
                }
                if (cell.style.underline) {
                    canvas.drawRect(
                        x,
                        top + rowHeight - 1.5f,
                        x + spanWidth,
                        top + rowHeight - 0.5f,
                        paint,
                    )
                }
            }
        }

        // Idle state: the Claude Code input-line cursor is replaced by our
        // blinking "_" (drawBlinkingCursor); skip the frame's static
        // underline cursor there so exactly one cursor shows.
        val cursorOnInputLine = findInputRow()?.let { frame.cursor.row == it } == true
        val idleInputLine = cursorOnInputLine && !composerVisible && historyPreviewText == null
        if (frame.cursor.visible && frame.cursor.row in 0 until frame.rows && !idleInputLine) {
            val cursorColumn = frame.cursor.column.coerceIn(0, frame.columns - 1)
            val x = grid.left + cursorColumn * cellWidth
            val bottom = grid.top + (frame.cursor.row + 1) * rowHeight
            paint.color = Color.GREEN
            paint.alpha = 230
            paint.isFakeBoldText = false
            canvas.drawRect(x, bottom - 1.5f, x + cellWidth, bottom, paint)
        }
        resetPaint()
    }

    private fun drawComposer(canvas: Canvas) {
        val left = 24f
        val right = width - 24f
        val bottom = height - TerminalSpec.FOOTER_HEIGHT - 8f
        val availableHeight = (bottom - TerminalSpec.TERMINAL_TOP - 12f).coerceAtLeast(1f)
        // The command palette gets a taller overlay so the list has room.
        val composerHeight = if (commandPaletteOpen) {
            min(430f, availableHeight).coerceAtLeast(280f)
        } else {
            min(260f, availableHeight).coerceAtLeast(190f)
        }
        val top = bottom - composerHeight

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.alpha = 246
        canvas.drawRect(left, top, right, bottom, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.GREEN
        paint.alpha = 245
        canvas.drawRect(left, top, right, bottom, paint)

        resetPaint()
        paint.isFakeBoldText = true
        paint.textSize = 16f
        canvas.drawText(
            if (commandPaletteOpen) "COMMANDS / UP-DOWN SELECT" else "LOCAL INPUT",
            left + 12f,
            top + 24f,
            paint,
        )

        paint.isFakeBoldText = false
        paint.alpha = 180
        paint.textSize = 11f
        canvas.drawText(composerStatus.take(54), left + 12f, top + 43f, paint)
        canvas.drawLine(left + 10f, top + 54f, right - 10f, top + 54f, paint)

        if (commandPaletteOpen) {
            drawCommandPaletteList(canvas, left, right, top, bottom)
            return
        }

        paint.alpha = 255
        paint.textSize = 16f
        val textLeft = left + 12f
        val textAreaTop = top + 64f
        val textAreaBottom = bottom - 70f
        val lineHeight = 22f
        val scrollTrackX = right - 10f
        val textRight = scrollTrackX - 8f
        val lines = buildComposerLines(composerText, textRight - textLeft)
        val cursorLine = lines.indexOfLast { composerCursor >= it.start }
            .coerceAtLeast(0)
            .coerceAtMost(lines.lastIndex)
        val maxVisibleLines = ((textAreaBottom - textAreaTop) / lineHeight).toInt().coerceAtLeast(1)
        composerFirstVisibleLine = ComposerViewportPolicy.keepCursorVisible(
            currentFirstLine = composerFirstVisibleLine,
            cursorLine = cursorLine,
            totalLines = lines.size,
            maxVisibleLines = maxVisibleLines,
        )
        val firstLine = composerFirstVisibleLine
        val visibleLines = lines.drop(firstLine).take(maxVisibleLines)
        val firstBaseline = textAreaTop + 17f

        canvas.save()
        canvas.clipRect(textLeft, textAreaTop, textRight, textAreaBottom)
        visibleLines.forEachIndexed { visibleIndex, line ->
            val baseline = firstBaseline + visibleIndex * lineHeight
            val lineText = composerText.substring(line.start, line.end)
            canvas.drawText(lineText, textLeft, baseline, paint)

            val absoluteLineIndex = firstLine + visibleIndex
            if (absoluteLineIndex == cursorLine) {
                val cursorInLine = composerCursor.coerceIn(line.start, line.end)
                val cursorX = textLeft + paint.measureText(
                    composerText.substring(line.start, cursorInLine),
                )
                canvas.drawRect(cursorX, baseline - 16f, cursorX + 2f, baseline + 3f, paint)
            }
        }
        canvas.restore()

        if (lines.size > maxVisibleLines) {
            val trackTop = textAreaTop + 2f
            val trackBottom = textAreaBottom - 2f
            val trackHeight = (trackBottom - trackTop).coerceAtLeast(1f)
            val thumbHeight = (trackHeight * maxVisibleLines / lines.size)
                .coerceIn(18f, trackHeight)
            val maxFirstLine = (lines.size - maxVisibleLines).coerceAtLeast(1)
            val scrollFraction = firstLine.toFloat() / maxFirstLine
            val thumbTop = trackTop + (trackHeight - thumbHeight) * scrollFraction

            paint.style = Paint.Style.FILL
            paint.color = Color.GREEN
            paint.alpha = 65
            canvas.drawRect(scrollTrackX, trackTop, scrollTrackX + 2f, trackBottom, paint)
            paint.alpha = 210
            canvas.drawRect(scrollTrackX - 1f, thumbTop, scrollTrackX + 3f, thumbTop + thumbHeight, paint)
        }

        paint.alpha = 175
        paint.textSize = 11f
        canvas.drawLine(left + 10f, bottom - 60f, right - 10f, bottom - 60f, paint)
        canvas.drawText("LEFT/RIGHT CURSOR   SHUTTER DELETE", left + 12f, bottom - 36f, paint)
        canvas.drawText("HOLD SEND   DOUBLE/BACK CANCEL", left + 12f, bottom - 15f, paint)
        resetPaint()
    }

    /**
     * /skills-style command list inside the composer overlay: selected item
     * highlighted, hint lines at the bottom. Modal — the draft is hidden
     * while the palette is open and reappears on close/confirm.
     */
    private fun drawCommandPaletteList(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ) {
        val listLeft = left + 12f
        val listTop = top + 64f
        val rowHeight = 22f
        val rowWidth = right - left - 34f
        val visible = minOf(commandPaletteItems.size, 12)
        // The visible window follows the selection so every command is
        // reachable even when the list is longer than the panel.
        val windowStart = (commandPaletteSelected - visible / 2)
            .coerceIn(0, (commandPaletteItems.size - visible).coerceAtLeast(0))

        paint.textSize = 16f
        for (i in 0 until visible) {
            val itemIndex = windowStart + i
            val rowTop = listTop + i * rowHeight
            val item = commandPaletteItems[itemIndex]
            if (itemIndex == commandPaletteSelected) {
                paint.style = Paint.Style.FILL
                paint.color = Color.GREEN
                paint.alpha = 90
                canvas.drawRect(listLeft - 4f, rowTop, right - 14f, rowTop + rowHeight, paint)
                paint.alpha = 255
            } else {
                paint.alpha = 230
            }
            paint.style = Paint.Style.FILL
            val text = if (paint.measureText(item) > rowWidth) item.take(12) + "…" else item
            val x = if (item == CommandPaletteState.SESSION_PICKER_ITEM) {
                // Banner item: centered (user 2026-08-08).
                listLeft + (rowWidth - paint.measureText(text)) / 2f
            } else {
                listLeft
            }
            canvas.drawText(text, x, rowTop + 17f, paint)
        }

        // Scrollbar when the list overflows the visible window.
        if (commandPaletteItems.size > visible) {
            val trackTop = listTop + 2f
            val trackBottom = listTop + visible * rowHeight - 2f
            val trackHeight = (trackBottom - trackTop).coerceAtLeast(1f)
            val thumbHeight = (trackHeight * visible / commandPaletteItems.size).coerceIn(14f, trackHeight)
            val maxStart = (commandPaletteItems.size - visible).coerceAtLeast(1)
            val thumbTop = trackTop + (trackHeight - thumbHeight) * windowStart / maxStart
            paint.style = Paint.Style.FILL
            paint.color = Color.GREEN
            paint.alpha = 65
            canvas.drawRect(right - 12f, trackTop, right - 10f, trackBottom, paint)
            paint.alpha = 210
            canvas.drawRect(right - 13f, thumbTop, right - 9f, thumbTop + thumbHeight, paint)
        }

        paint.alpha = 175
        paint.textSize = 11f
        canvas.drawLine(left + 10f, bottom - 60f, right - 10f, bottom - 60f, paint)
        canvas.drawText("UP/DOWN SELECT   CONFIRM INSERT", left + 12f, bottom - 36f, paint)
        canvas.drawText("BACK / KNOB-R CANCEL", left + 12f, bottom - 15f, paint)
        resetPaint()
    }

    private fun drawSessionPicker(canvas: Canvas) {
        // Full-bleed opaque cover below the top info bar (user 2026-08-08):
        // the terminal footer and any live output must never bleed through
        // the modal.
        val left = 0f
        val right = width.toFloat()
        val top = 90f
        val bottom = height.toFloat()

        // Explicit FILL: drawRingIcon/drawKeyboardIcon leave paint.style =
        // STROKE, so an implicit fill here silently draws nothing — the
        // "transparent picker" bug (2026-08-08, ring connected).
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.alpha = 255
        canvas.drawRect(left, top, right, bottom, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.GREEN
        paint.alpha = 245
        canvas.drawRect(left, top, right, bottom, paint)
        resetPaint()

        paint.isFakeBoldText = true
        paint.textSize = 16f
        val header = if (sessionPickerUi.level == 0) {
            "PROJECTS / UP-DOWN SELECT"
        } else {
            val folder = sessionPickerUi.folders.getOrNull(sessionPickerUi.folderIndex)
            "CONVERSATIONS / " + (folder?.path?.substringAfterLast('/')?.ifBlank { folder.path } ?: "?")
        }
        canvas.drawText(header, left + 12f, top + 24f, paint)
        paint.isFakeBoldText = false
        canvas.drawLine(left + 10f, top + 54f, right - 10f, top + 54f, paint)

        if (sessionPickerUi.loading) {
            paint.alpha = 255
            paint.textSize = 16f
            canvas.drawText("LOADING…", left + 12f, top + 96f, paint)
            resetPaint()
            return
        }

        val listLeft = left + 12f
        val listTop = top + 64f
        val rowHeight = 22f
        val rowWidth = right - left - 34f
        val items = if (sessionPickerUi.level == 0) {
            sessionPickerUi.folders.map { it.path }
        } else {
            val folder = sessionPickerUi.folders.getOrNull(sessionPickerUi.folderIndex)
            listOf("+ New Chat") + (folder?.sessions?.map { it.title } ?: emptyList())
        }
        val selected = if (sessionPickerUi.level == 0) {
            sessionPickerUi.folderIndex
        } else {
            sessionPickerUi.sessionIndex
        }
        val visible = minOf(items.size, 12)
        val windowStart = (selected - visible / 2)
            .coerceIn(0, (items.size - visible).coerceAtLeast(0))

        if (sessionPickerUi.error && items.size <= 1) {
            paint.alpha = 170
            paint.textSize = 13f
            canvas.drawText("HELPER UNAVAILABLE / CONFIRM = NEW CHAT", listLeft, listTop + 90f, paint)
        }

        paint.textSize = 16f
        for (i in 0 until visible) {
            val itemIndex = windowStart + i
            val rowTop = listTop + i * rowHeight
            var text = items[itemIndex]
            if (sessionPickerUi.deleteArmed && sessionPickerUi.level == 1 && itemIndex == selected) {
                text = "$text Delete?"
            }
            if (itemIndex == selected) {
                paint.style = Paint.Style.FILL
                paint.color = Color.GREEN
                paint.alpha = 90
                canvas.drawRect(listLeft - 4f, rowTop, right - 14f, rowTop + rowHeight, paint)
                paint.alpha = 255
            } else {
                paint.alpha = 230
            }
            val current = if (sessionPickerUi.level == 0) {
                sessionPickerUi.currentFolderPath == sessionPickerUi.folders.getOrNull(itemIndex)?.path
            } else {
                itemIndex >= 1 && sessionPickerUi.currentSessionId ==
                    sessionPickerUi.folders.getOrNull(sessionPickerUi.folderIndex)
                        ?.sessions?.getOrNull(itemIndex - 1)?.id
            }
            if (current) text = "▶ $text"
            if (paint.measureText(text) > rowWidth) text = truncateToWidth(text, rowWidth)
            paint.style = Paint.Style.FILL
            canvas.drawText(text, listLeft, rowTop + 17f, paint)
        }

        if (items.size > visible) {
            val trackTop = listTop + 2f
            val trackBottom = listTop + visible * rowHeight - 2f
            val trackHeight = (trackBottom - trackTop).coerceAtLeast(1f)
            val thumbHeight = (trackHeight * visible / items.size).coerceIn(14f, trackHeight)
            val maxStart = (items.size - visible).coerceAtLeast(1)
            val thumbTop = trackTop + (trackHeight - thumbHeight) * windowStart / maxStart
            paint.style = Paint.Style.FILL
            paint.color = Color.GREEN
            paint.alpha = 65
            canvas.drawRect(right - 12f, trackTop, right - 10f, trackBottom, paint)
            paint.alpha = 210
            canvas.drawRect(right - 13f, thumbTop, right - 9f, thumbTop + thumbHeight, paint)
        }

        if (sessionPickerUi.level == 1 && sessionPickerUi.deleteInFlight) {
            // Server-side delete round trip in flight: show DELETING… and
            // lock input (user 2026-08-08).
            paint.alpha = 255
            paint.textSize = 16f
            val msg = "DELETING…"
            val msgW = paint.measureText(msg)
            canvas.drawText(msg, (left + right) / 2f - msgW / 2f, bottom - 68f, paint)
            paint.alpha = 175
            paint.textSize = 11f
            canvas.drawText("PLEASE WAIT", left + 12f, bottom - 36f, paint)
        } else if (sessionPickerUi.deleteArmed && sessionPickerUi.level == 1) {
            paint.alpha = 255
            paint.textSize = 16f
            val cancelText = "Cancel"
            val deleteText = "Delete"
            val midX = (left + right) / 2f
            paint.style = Paint.Style.FILL
            paint.color = Color.GREEN
            paint.alpha = 90
            if (sessionPickerUi.deleteOption == 0) {
                canvas.drawRect(left + 4f, bottom - 92f, midX, bottom - 62f, paint)
            } else {
                canvas.drawRect(midX, bottom - 92f, right - 4f, bottom - 62f, paint)
            }
            // English labels (user 2026-08-08), no arrows, centered in each
            // half of the option bar.
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            val cancelW = paint.measureText(cancelText)
            val deleteW = paint.measureText(deleteText)
            canvas.drawText(cancelText, midX / 2f - cancelW / 2f, bottom - 68f, paint)
            canvas.drawText(deleteText, midX + (right - midX) / 2f - deleteW / 2f, bottom - 68f, paint)
            paint.alpha = 175
            paint.textSize = 11f
            canvas.drawText("SWIPE SELECT   CONFIRM DELETE   CANCEL UNMARK", left + 12f, bottom - 36f, paint)
        } else {
            paint.alpha = 175
            paint.textSize = 11f
            canvas.drawLine(left + 10f, bottom - 60f, right - 10f, bottom - 60f, paint)
            val hint = if (sessionPickerUi.level == 0) {
                "UP/DOWN SELECT   CONFIRM OPEN   BACK CANCEL"
            } else {
                "CONFIRM SWITCH   BACK = UP"
            }
            canvas.drawText(hint, left + 12f, bottom - 36f, paint)
        }
        resetPaint()
    }

    /** Ellipsizes [value] to fit [maxWidth] using the current paint. */
    private fun truncateToWidth(value: String, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && paint.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end) + "…"
    }

    private fun buildComposerLines(value: String, maxWidth: Float): List<ComposerLine> {
        if (value.isEmpty()) return listOf(ComposerLine(0, 0))
        val result = ArrayList<ComposerLine>()
        val boundaries = GraphemeText.boundaries(value)
        var lineStart = 0
        var lineWidth = 0f
        for (index in 0 until boundaries.lastIndex) {
            val clusterStart = boundaries[index]
            val clusterEnd = boundaries[index + 1]
            val cluster = value.substring(clusterStart, clusterEnd)
            if (cluster == "\n" || cluster == "\r" || cluster == "\r\n") {
                result += ComposerLine(lineStart, clusterStart)
                lineStart = clusterEnd
                lineWidth = 0f
                continue
            }
            val clusterWidth = paint.measureText(cluster)
            if (lineWidth + clusterWidth > maxWidth && clusterStart > lineStart) {
                result += ComposerLine(lineStart, clusterStart)
                lineStart = clusterStart
                lineWidth = 0f
            }
            lineWidth += clusterWidth
        }
        result += ComposerLine(lineStart, value.length)
        return result
    }

    /**
     * Masks IPv4 middle octets for screen-recording safety
     * (e.g. 203.0.113.5 -> 203.xx.xx.5). Non-IP hosts pass through unchanged.
     */
    private fun maskHost(host: String): String {
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            return "${parts[0]}.xx.xx.${parts[3]}"
        }
        return host
    }

    private fun drawEndpoints(canvas: Canvas) {
        resetPaint()
        paint.alpha = 180
        paint.textSize = 15f
        canvas.drawText("CONNECTION TARGETS", 24f, 72f, paint)
        canvas.drawLine(24f, 88f, width - 24f, 88f, paint)

        if (endpoints.isEmpty()) {
            paint.alpha = 255
            paint.textSize = 19f
            canvas.drawText("NO TARGETS", 24f, 150f, paint)
            paint.alpha = 170
            paint.textSize = 15f
            canvas.drawText("ADD ONE WITH TRUSTED ADB OR QR", 24f, 185f, paint)
        } else {
            val visibleCount = 6
            val maxStart = (endpoints.size - visibleCount).coerceAtLeast(0)
            val start = (selectedEndpoint - visibleCount / 2).coerceIn(0, maxStart)
            val visible = endpoints.drop(start).take(visibleCount)
            var y = 130f
            visible.forEachIndexed { visibleIndex, endpoint ->
                val index = start + visibleIndex
                val selected = index == selectedEndpoint
                paint.alpha = if (selected) 255 else 130
                paint.textSize = if (selected) 21f else 18f
                canvas.drawText(if (selected) "> ${endpoint.name}" else "  ${endpoint.name}", 24f, y, paint)
                paint.alpha = if (selected) 190 else 90
                paint.textSize = 13f
                canvas.drawText("  ${endpoint.user}@${maskHost(endpoint.host)}", 24f, y + 23f, paint)
                y += 76f
            }
            if (endpoints.size > visibleCount) {
                paint.alpha = 120
                paint.textSize = 13f
                canvas.drawText("${selectedEndpoint + 1} / ${endpoints.size}", width - 88f, 72f, paint)
            }
        }

        paint.alpha = 145
        paint.textSize = 12f
        canvas.drawText("SWIPE CHOOSE   CENTER CONNECT   BACK EXIT", 24f, height - 14f, paint)
    }

    private fun resetPaint() {
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 1f
        paint.color = Color.GREEN
        paint.alpha = 255
        paint.isFakeBoldText = false
    }

    companion object {
        private val NUMBERED_ITEM = Regex("""\d\.""")
    }
}

/** Snapshot of the conversation picker overlay (design 2026-08-07). */
data class SessionPickerUi(
    val open: Boolean = false,
    val loading: Boolean = false,
    val error: Boolean = false,
    val level: Int = 0,
    val folders: List<RemoteFolder> = emptyList(),
    val folderIndex: Int = 0,
    val sessionIndex: Int = 0,
    val currentFolderPath: String? = null,
    val currentSessionId: String? = null,
    val deleteArmed: Boolean = false,
    val deleteOption: Int = 0,
    val deleteInFlight: Boolean = false,
)
