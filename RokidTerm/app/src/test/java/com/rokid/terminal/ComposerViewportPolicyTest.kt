package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerViewportPolicyTest {
    @Test
    fun `cursor movement inside viewport does not move input window`() {
        assertEquals(
            3,
            ComposerViewportPolicy.keepCursorVisible(
                currentFirstLine = 3,
                cursorLine = 5,
                totalLines = 12,
                maxVisibleLines = 4,
            ),
        )
    }

    @Test
    fun `viewport scrolls only enough to reveal cursor below`() {
        assertEquals(
            4,
            ComposerViewportPolicy.keepCursorVisible(
                currentFirstLine = 3,
                cursorLine = 7,
                totalLines = 12,
                maxVisibleLines = 4,
            ),
        )
    }

    @Test
    fun `viewport scrolls only enough to reveal cursor above`() {
        assertEquals(
            2,
            ComposerViewportPolicy.keepCursorVisible(
                currentFirstLine = 3,
                cursorLine = 2,
                totalLines = 12,
                maxVisibleLines = 4,
            ),
        )
    }

    @Test
    fun `viewport clamps after draft becomes shorter`() {
        assertEquals(
            1,
            ComposerViewportPolicy.keepCursorVisible(
                currentFirstLine = 8,
                cursorLine = 4,
                totalLines = 5,
                maxVisibleLines = 4,
            ),
        )
    }
}
