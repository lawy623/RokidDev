package com.rokid.terminal

/**
 * COIDEA knob letter/digit picker (user design 2026-08-14).
 *
 * Rotating a knob steps through a character set; the candidate shows at
 * the composer cursor; when rotation stops for 1 s the candidate is
 * committed as normal text (deletable like typed input). The picker sits
 * "one before" the first item when idle: only RIGHT rotation wakes it
 * (left is a no-op at -1), the right boundary is the last item (no wrap —
 * rotating beyond the end does nothing). Once active, left/right both
 * move the停留 position; left from the first item returns to the idle
 * position (nothing selected).
 *
 * Two pickers exist (left knob = a-zA-Z, right knob = 0-9); MainActivity
 * keeps at most ONE active (the last-rotated knob wins — only one
 * candidate can render at the cursor).
 */
class KnobPicker(val items: String) {

    /** Index into [items]; -1 = the idle "before first" position. */
    var index: Int = -1
        private set

    val active: Boolean get() = index >= 0

    /** One detent right; returns false at the last item (no-op, no wrap). */
    fun stepRight(): Boolean {
        if (index >= items.length - 1) return false
        index++
        return true
    }

    /** One detent left; a no-op at the idle position (spec: initially only right). */
    fun stepLeft(): Boolean {
        if (index <= -1) return false
        index--
        return true
    }

    /** The letter/digit at the停留 position, or null when idle/armed-empty. */
    fun candidate(): Char? = if (index >= 0) items[index] else null

    fun reset() {
        index = -1
    }

    companion object {
        /** Left knob: a-z then A-Z, 52 items (user design 2026-08-14). */
        const val LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        /** Right knob: 0-9, 10 items. */
        const val DIGITS = "0123456789"
    }
}
