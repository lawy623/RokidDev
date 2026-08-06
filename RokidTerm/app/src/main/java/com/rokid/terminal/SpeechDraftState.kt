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
        val changed = if (value.isNotBlank()) updatePartial(value) else false
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
