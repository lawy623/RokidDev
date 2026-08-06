package com.rokid.terminal

/** Display-only policy. Terminal cells are rendered exactly as received. */
internal object TerminalRenderPolicy {
    fun visibleText(text: String): String = text
}
