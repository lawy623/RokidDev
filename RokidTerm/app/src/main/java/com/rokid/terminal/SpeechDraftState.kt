package com.rokid.terminal

/**
 * Owns the replaceable ASR hypothesis span inside the local composer draft.
 * Partial transcripts never go to the PTY and never append repeatedly.
 */
class SpeechDraftState(private val composer: InputComposerState) {
    private data class Span(val start: Int, val endExclusive: Int)

    private var activeSpan: Span? = null

    val hasActiveHypothesis: Boolean
        get() = activeSpan != null

    fun updatePartial(value: String): Boolean {
        if (value.isBlank()) return false
        val span = activeSpan ?: Span(composer.cursor, composer.cursor)
        val end = composer.replaceRange(span.start, span.endExclusive, value)
        activeSpan = Span(span.start, end)
        return true
    }

    fun finalize(value: String): Boolean {
        // The server ASR model habitually appends an emoji to every result
        // (user report 2026-08-13) — strip a TRAILING emoji run before the
        // text lands in the draft. No-op when the text ends normally.
        val clean = stripTrailingEmoji(value)
        val changed = if (clean.isNotBlank()) updatePartial(clean) else false
        activeSpan = null
        return changed
    }

    /** Keep the latest visible hypothesis as ordinary editable draft text. */
    fun commitActive() {
        activeSpan = null
    }

    /** Remove only the currently uncommitted speech hypothesis. */
    fun discardActive() {
        val span = activeSpan ?: return
        composer.replaceRange(span.start, span.endExclusive, "")
        activeSpan = null
    }

    fun reset() {
        activeSpan = null
    }
}

/**
 * Strips a TRAILING run of emoji from an ASR transcript (user 2026-08-13).
 * Only emoji-range code points are removed — Chinese/ASCII text and
 * punctuation are never touched. Handles ZWJ sequences, skin tones,
 * keycaps (a trailing digit, hash, or asterisk before a keycap is stripped
 * too) and the variation selector. Pure for JVM tests.
 */
fun stripTrailingEmoji(text: String): String {
    var end = text.length
    var hadVariationSelector = false
    fun isEmojiish(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||          // pictographs, emoticons, transport, extended
            cp in 0x2600..0x27BF ||        // misc symbols + dingbats (❤ 2764, ✨ 2728)
            cp in 0x1F1E6..0x1F1FF ||      // regional indicators (flags)
            cp == 0x2B50 || cp == 0x2B55 || // ⭐ star (common standalone)
            cp == 0xFE0F || cp == 0x20E3 || cp == 0x200D

    // "Text-presentation" chars that become emoji only with a following
    // variation selector (©️ ™️ ®️ ℹ️ 〰️ ↩️ etc.) — stripped only when the
    // FE0F was actually seen.
    fun isTextEmojiWithVs(cp: Int): Boolean =
        cp == 0x00A9 || cp == 0x00AE || cp == 0x2122 || cp == 0x2139 ||
            cp == 0x3030 || cp in 0x2194..0x21AA || cp in 0x231A..0x23FA

    var hadKeycap = false
    while (end > 0) {
        val cp = text.codePointBefore(end)
        // ©️ = 00A9 FE0F: the © is emoji only BECAUSE of the FE0F already
        // consumed to its right — allow it inside the run then, but never
        // strip a bare © (no FE0F).
        if (!isEmojiish(cp) && !(hadVariationSelector && isTextEmojiWithVs(cp))) break
        if (cp == 0xFE0F) hadVariationSelector = true
        if (cp == 0x20E3) hadKeycap = true
        end -= Character.charCount(cp)
    }
    // Keycap sequence "5️⃣" ends with digit + FE0F + 20E3: strip the digit too.
    if (hadKeycap && end > 0) {
        val cp = text.codePointBefore(end)
        if (cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code) {
            end -= Character.charCount(cp)
        }
    }
    return text.substring(0, end)
}
