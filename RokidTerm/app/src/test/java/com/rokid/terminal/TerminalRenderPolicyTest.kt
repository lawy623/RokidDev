package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalRenderPolicyTest {
    @Test
    fun `angle brackets remain visible on every row`() {
        assertEquals("<lau>", TerminalRenderPolicy.visibleText("<lau>"))
        assertEquals("<lau>", TerminalRenderPolicy.visibleText("<lau>"))
        assertEquals("<", TerminalRenderPolicy.visibleText("<"))
        assertEquals(">", TerminalRenderPolicy.visibleText(">"))
    }

    @Test
    fun `ordinary text remains unchanged`() {
        assertEquals("B", TerminalRenderPolicy.visibleText("B"))
        assertEquals("Claude Code", TerminalRenderPolicy.visibleText("Claude Code"))
    }
}
