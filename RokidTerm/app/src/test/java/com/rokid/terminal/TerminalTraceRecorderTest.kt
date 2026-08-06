package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalTraceRecorderTest {
    @Test
    fun `escapes terminal controls without changing printable status text`() {
        val raw = "\u001b[1;1Hcloud-claude <lau>\r\n"

        assertEquals(
            "\\x1b[1;1Hcloud-claude <lau>\\r\\n\n",
            TerminalTraceRecorder.escapeControls(raw),
        )
    }
}
