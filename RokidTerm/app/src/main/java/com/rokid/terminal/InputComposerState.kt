package com.rokid.terminal

/**
 * Local, unsent input owned by RokidTerm. Cursor offsets are UTF-16 indices,
 * but every mutation keeps them on an extended-grapheme-style boundary so a
 * delete never leaves half of a surrogate pair, combining sequence, emoji
 * modifier, flag, or ZWJ sequence behind.
 */
class InputComposerState {
    var text: String = ""
        private set

    var cursor: Int = 0
        private set

    fun insertText(value: String) {
        if (value.isEmpty()) return
        text = text.substring(0, cursor) + value + text.substring(cursor)
        cursor += value.length
        cursor = GraphemeText.boundaryAtOrBefore(text, cursor)
    }

    fun moveLeft(): Boolean {
        if (cursor <= 0) return false
        cursor = GraphemeText.previousBoundary(text, cursor)
        return true
    }

    fun moveRight(): Boolean {
        if (cursor >= text.length) return false
        cursor = GraphemeText.nextBoundary(text, cursor)
        return true
    }

    /**
     * Moves the cursor to the previous visual wrapped line while keeping the
     * column (the draft is logically single-line; wrapping is visual, per
     * [columns] display columns). No-op on the first visual line.
     */
    fun moveUp(columns: Int): Boolean {
        if (cursor <= 0) return false
        val rows = wrapRows(columns)
        val (rowIndex, col) = locate(cursor, rows)
        if (rowIndex == 0) return false
        val target = rows[rowIndex - 1]
        cursor = indexForColumn(target.first, target.second, col)
        return true
    }

    /** Mirrors [moveUp] toward the next visual line; no-op on the last line. */
    fun moveDown(columns: Int): Boolean {
        if (cursor >= text.length) return false
        val rows = wrapRows(columns)
        val (rowIndex, col) = locate(cursor, rows)
        if (rowIndex >= rows.lastIndex) return false
        val target = rows[rowIndex + 1]
        cursor = indexForColumn(target.first, target.second, col)
        return true
    }

    /** Wrapped visual rows as (startIndex, endExclusive) UTF-16 ranges. */
    private fun wrapRows(columns: Int): List<Pair<Int, Int>> {
        val safeColumns = columns.coerceAtLeast(1)
        val bounds = GraphemeText.boundaries(text)
        val rows = mutableListOf<Pair<Int, Int>>()
        var rowStart = 0
        var width = 0
        for (i in 1 until bounds.size) {
            val grapheme = text.substring(bounds[i - 1], bounds[i])
            val w = graphemeWidth(grapheme)
            if (width + w > safeColumns && width > 0) {
                rows += rowStart to bounds[i - 1]
                rowStart = bounds[i - 1]
                width = 0
            }
            width += w
        }
        rows += rowStart to text.length
        return rows
    }

    /** (visual row index, column offset in display columns) of [offset]. */
    private fun locate(offset: Int, rows: List<Pair<Int, Int>>): Pair<Int, Int> {
        for ((index, row) in rows.withIndex()) {
            if (offset <= row.second) {
                var col = 0
                val bounds = GraphemeText.boundaries(text)
                for (i in 1 until bounds.size) {
                    val start = bounds[i - 1]
                    if (start >= offset) break
                    if (start >= row.first) col += graphemeWidth(text.substring(bounds[i - 1], bounds[i]))
                }
                return index to col
            }
        }
        return rows.lastIndex to 0
    }

    /** Index whose row column is closest to (but not past) [targetCol]. */
    private fun indexForColumn(rowStart: Int, rowEndExclusive: Int, targetCol: Int): Int {
        var col = 0
        var best = rowStart
        val bounds = GraphemeText.boundaries(text)
        for (i in 1 until bounds.size) {
            val start = bounds[i - 1]
            if (start < rowStart || start >= rowEndExclusive) continue
            val w = graphemeWidth(text.substring(bounds[i - 1], bounds[i]))
            if (col + w > targetCol) break
            col += w
            best = bounds[i]
        }
        return best
    }

    /** Display width of a grapheme: 2 for wide (CJK/emoji), 1 otherwise, 0 for bare combining marks. */
    private fun graphemeWidth(grapheme: String): Int {
        if (grapheme.isEmpty()) return 0
        val cp = grapheme.codePointAt(0)
        if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) return 0
        return if (isWide(cp)) 2 else 1
    }

    private fun isWide(codePoint: Int): Boolean {
        return codePoint in 0x1100..0x11FF || codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7A3 || codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE30..0xFE4F || codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6 || codePoint in 0x1F000..0x1FFFF
    }

    fun deletePrevious(): Boolean {
        if (cursor <= 0) return false
        val start = GraphemeText.previousBoundary(text, cursor)
        text = text.removeRange(start, cursor)
        cursor = start
        return true
    }

    /** Replaces a known grapheme-aligned span and moves the cursor to its end. */
    internal fun replaceRange(start: Int, endExclusive: Int, value: String): Int {
        require(start in 0..endExclusive && endExclusive <= text.length)
        require(GraphemeText.boundaryAtOrBefore(text, start) == start)
        require(GraphemeText.boundaryAtOrBefore(text, endExclusive) == endExclusive)
        text = text.substring(0, start) + value + text.substring(endExclusive)
        cursor = GraphemeText.boundaryAtOrBefore(text, start + value.length)
        return cursor
    }

    fun clear() {
        text = ""
        cursor = 0
    }

    /** Sets the cursor to a grapheme boundary (used by pixel-based vertical moves). */
    fun setCursor(index: Int) {
        cursor = GraphemeText.boundaryAtOrBefore(text, index.coerceIn(0, text.length))
    }
}

/**
 * Small dependency-free grapheme helper suitable for local JVM tests and API
 * 26. It covers the sequences that matter most for terminal input: combining
 * marks, variation selectors, emoji modifiers/tags, paired regional indicators,
 * and ZWJ-linked emoji. It intentionally avoids Android ICU dependencies.
 */
internal object GraphemeText {
    fun boundaries(value: String): IntArray {
        if (value.isEmpty()) return intArrayOf(0)
        val result = ArrayList<Int>()
        result += 0
        var index = 0
        while (index < value.length) {
            index = nextClusterEnd(value, index)
            result += index
        }
        return result.toIntArray()
    }

    fun previousBoundary(value: String, offset: Int): Int {
        val safe = offset.coerceIn(0, value.length)
        val points = boundaries(value)
        for (index in points.lastIndex downTo 0) {
            if (points[index] < safe) return points[index]
        }
        return 0
    }

    fun nextBoundary(value: String, offset: Int): Int {
        val safe = offset.coerceIn(0, value.length)
        for (point in boundaries(value)) {
            if (point > safe) return point
        }
        return value.length
    }

    fun boundaryAtOrBefore(value: String, offset: Int): Int {
        val safe = offset.coerceIn(0, value.length)
        var answer = 0
        for (point in boundaries(value)) {
            if (point > safe) break
            answer = point
        }
        return answer
    }

    private fun nextClusterEnd(value: String, start: Int): Int {
        var index = start
        val first = value.codePointAt(index)
        index += Character.charCount(first)

        // A pair of regional indicators forms one flag grapheme.
        if (isRegionalIndicator(first) && index < value.length) {
            val second = value.codePointAt(index)
            if (isRegionalIndicator(second)) index += Character.charCount(second)
        }

        index = consumeExtensions(value, index)
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (codePoint != ZERO_WIDTH_JOINER) break
            index += Character.charCount(codePoint)
            if (index >= value.length) break
            val joined = value.codePointAt(index)
            index += Character.charCount(joined)
            index = consumeExtensions(value, index)
        }
        return index
    }

    private fun consumeExtensions(value: String, from: Int): Int {
        var index = from
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (!isExtension(codePoint)) break
            index += Character.charCount(codePoint)
        }
        return index
    }

    private fun isExtension(codePoint: Int): Boolean {
        return when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true
            else -> isVariationSelector(codePoint) ||
                codePoint in EMOJI_MODIFIER_START..EMOJI_MODIFIER_END ||
                codePoint in EMOJI_TAG_START..EMOJI_TAG_END
        }
    }

    private fun isVariationSelector(codePoint: Int): Boolean {
        return codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean {
        return codePoint in 0x1F1E6..0x1F1FF
    }

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val EMOJI_MODIFIER_START = 0x1F3FB
    private const val EMOJI_MODIFIER_END = 0x1F3FF
    private const val EMOJI_TAG_START = 0xE0020
    private const val EMOJI_TAG_END = 0xE007F
}
