package com.rokid.terminal

import kotlin.math.floor

/** Shared defaults and Rokid display geometry. */
object TerminalSpec {
    const val DEFAULT_COLUMNS = 46
    const val DEFAULT_ROWS = 30
    const val DISPLAY_WIDTH = 480
    const val DISPLAY_HEIGHT = 640

    const val SIDE_PADDING = 24f
    const val TERMINAL_TOP = 92f
    const val FOOTER_HEIGHT = 72f
    const val PREFERRED_CELL_WIDTH = 8.0f
    const val PREFERRED_ROW_HEIGHT = 13.0f
    const val MIN_COLUMNS = 10
    const val MIN_ROWS = 4

    fun viewportFor(pixelWidth: Int, pixelHeight: Int): TerminalViewport {
        if (pixelWidth <= 0 || pixelHeight <= 0) return TerminalViewport.default()
        val availableWidth = (pixelWidth - SIDE_PADDING * 2f).coerceAtLeast(PREFERRED_CELL_WIDTH)
        val availableHeight = (pixelHeight - TERMINAL_TOP - FOOTER_HEIGHT)
            .coerceAtLeast(PREFERRED_ROW_HEIGHT)
        return TerminalViewport(
            columns = floor(availableWidth / PREFERRED_CELL_WIDTH).toInt().coerceAtLeast(MIN_COLUMNS),
            rows = floor(availableHeight / PREFERRED_ROW_HEIGHT).toInt().coerceAtLeast(MIN_ROWS),
            pixelWidth = availableWidth.toInt(),
            pixelHeight = availableHeight.toInt(),
        )
    }
}

/** Negotiated character grid and its drawable pixel viewport. */
data class TerminalViewport(
    val columns: Int,
    val rows: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
) {
    init {
        require(columns > 0 && rows > 0)
        require(pixelWidth > 0 && pixelHeight > 0)
    }

    companion object {
        fun default(): TerminalViewport = TerminalSpec.viewportFor(
            TerminalSpec.DISPLAY_WIDTH,
            TerminalSpec.DISPLAY_HEIGHT,
        )
    }
}

/** Monochrome subset of terminal text attributes needed by the renderer. */
data class TerminalStyle(
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    /** SGR background color (48;5;N palette index or 48;2;r;g;b packed RGB), or null. */
    val background: Int? = null,
)

/** One immutable terminal grid cell shared by the emulator and renderer. */
data class TerminalCell(
    val text: String = " ",
    val continuation: Boolean = false,
    val span: Int = 1,
    val style: TerminalStyle = TerminalStyle(),
)

data class TerminalCursor(
    val row: Int = 0,
    val column: Int = 0,
    val visible: Boolean = true,
)

/** Immutable render input. A frame never changes after it is published to the View. */
data class TerminalFrame(
    val revision: Long,
    val columns: Int,
    val rows: Int,
    val cells: List<List<TerminalCell>>,
    val cursor: TerminalCursor,
    val scrollOffsetRows: Int = 0,
    val scrollbackRows: Int = 0,
    val hasNewOutput: Boolean = false,
) {
    companion object {
        fun empty(columns: Int, rows: Int): TerminalFrame = TerminalFrame(
            revision = 0,
            columns = columns,
            rows = rows,
            cells = List(rows) { List(columns) { TerminalCell() } },
            cursor = TerminalCursor(),
        )
    }
}
