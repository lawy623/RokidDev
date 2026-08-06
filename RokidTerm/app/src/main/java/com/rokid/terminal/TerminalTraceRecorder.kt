package com.rokid.terminal

import java.io.File

/**
 * App-private, bounded diagnostics for comparing the SSH PTY stream with the
 * terminal frame produced from it. Nothing is written to logcat.
 */
class TerminalTraceRecorder(private val directory: File) {
    private val rawFile = File(directory, RAW_FILE_NAME)
    private val frameFile = File(directory, FRAME_FILE_NAME)
    private var chunkIndex = 0L

    @Synchronized
    fun reset() {
        directory.mkdirs()
        chunkIndex = 0L
        rawFile.writeText("")
        frameFile.writeText("")
    }

    @Synchronized
    fun recordChunk(value: String) {
        directory.mkdirs()
        val entry = buildString {
            append("--- chunk ")
            append(++chunkIndex)
            append(" chars=")
            append(value.length)
            append(" ---\n")
            append(escapeControls(value))
            append('\n')
        }
        appendBounded(rawFile, entry)
    }

    @Synchronized
    fun recordFrame(frame: TerminalFrame) {
        directory.mkdirs()
        frameFile.writeText(buildString {
            append("revision=")
            append(frame.revision)
            append(" columns=")
            append(frame.columns)
            append(" rows=")
            append(frame.rows)
            append(" cursor=")
            append(frame.cursor.row)
            append(',')
            append(frame.cursor.column)
            append(" visible=")
            append(frame.cursor.visible)
            append('\n')
            frame.cells.forEachIndexed { rowIndex, row ->
                append(rowIndex.toString().padStart(2, '0'))
                append('|')
                row.forEach { cell ->
                    if (!cell.continuation) append(cell.text)
                }
                append("|\n")
            }
        })
    }

    private fun appendBounded(file: File, value: String) {
        val old = if (file.exists()) file.readText() else ""
        val combined = old + value
        file.writeText(if (combined.length <= MAX_CHARS) combined else combined.takeLast(MAX_CHARS))
    }

    companion object {
        const val RAW_FILE_NAME = "terminal_trace.txt"
        const val FRAME_FILE_NAME = "terminal_frame.txt"
        private const val MAX_CHARS = 256 * 1024

        internal fun escapeControls(value: String): String = buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '\u001b' -> append("\\x1b")
                    '\r' -> append("\\r")
                    '\n' -> append("\\n\n")
                    '\t' -> append("\\t")
                    else -> {
                        if (character.code < 0x20 || character.code == 0x7f) {
                            append("\\x")
                            append(character.code.toString(16).padStart(2, '0'))
                        } else {
                            append(character)
                        }
                    }
                }
            }
        }
    }
}
