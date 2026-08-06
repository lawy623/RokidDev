package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalModelsTest {
    @Test
    fun rokidViewportResolvesToFiftyFourByThirtySix() {
        val viewport = TerminalSpec.viewportFor(pixelWidth = 480, pixelHeight = 640)

        assertEquals(54, viewport.columns)
        assertEquals(36, viewport.rows)
        assertEquals(432, viewport.pixelWidth)
        assertEquals(476, viewport.pixelHeight)
    }

    @Test
    fun viewportAdaptsToDifferentWindowSizes() {
        val small = TerminalSpec.viewportFor(pixelWidth = 320, pixelHeight = 480)
        val large = TerminalSpec.viewportFor(pixelWidth = 960, pixelHeight = 1280)

        assertTrue(small.columns < large.columns)
        assertTrue(small.rows < large.rows)
        assertEquals(272, small.pixelWidth)
        assertEquals(316, small.pixelHeight)
        assertEquals(912, large.pixelWidth)
        assertEquals(1116, large.pixelHeight)
    }
}
