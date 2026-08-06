package com.rokid.terminal

/** Keeps the wrapped composer line containing the cursor inside a compact viewport. */
internal object ComposerViewportPolicy {
    fun keepCursorVisible(
        currentFirstLine: Int,
        cursorLine: Int,
        totalLines: Int,
        maxVisibleLines: Int,
    ): Int {
        if (totalLines <= 0) return 0
        val visibleCount = maxVisibleLines.coerceAtLeast(1)
        val maxFirstLine = (totalLines - visibleCount).coerceAtLeast(0)
        val safeCursorLine = cursorLine.coerceIn(0, totalLines - 1)
        val safeFirstLine = currentFirstLine.coerceIn(0, maxFirstLine)
        return when {
            safeCursorLine < safeFirstLine -> safeCursorLine
            safeCursorLine >= safeFirstLine + visibleCount -> {
                (safeCursorLine - visibleCount + 1).coerceAtMost(maxFirstLine)
            }
            else -> safeFirstLine
        }
    }
}
