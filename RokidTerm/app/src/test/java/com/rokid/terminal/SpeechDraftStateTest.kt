package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDraftStateTest {
    @Test
    fun `partial hypotheses replace one anchored span`() {
        val composer = InputComposerState().apply { insertText("请检查") }
        val speech = SpeechDraftState(composer)

        assertTrue(speech.updatePartial("服务器"))
        assertTrue(speech.updatePartial("服务器状态"))

        assertEquals("请检查服务器状态", composer.text)
        assertEquals(composer.text.length, composer.cursor)
        assertTrue(speech.hasActiveHypothesis)
    }

    @Test
    fun `speech inserts at current cursor and final commits it`() {
        val composer = InputComposerState().apply {
            insertText("前后")
            moveLeft()
        }
        val speech = SpeechDraftState(composer)

        speech.updatePartial("中")
        speech.finalize("中间")

        assertEquals("前中间后", composer.text)
        assertEquals("前中间".length, composer.cursor)
        assertFalse(speech.hasActiveHypothesis)
    }

    @Test
    fun `discard removes only active hypothesis`() {
        val composer = InputComposerState().apply { insertText("保留") }
        val speech = SpeechDraftState(composer)

        speech.updatePartial("临时文字")
        speech.discardActive()

        assertEquals("保留", composer.text)
        assertEquals("保留".length, composer.cursor)
    }

    @Test
    fun `committed partial remains editable`() {
        val composer = InputComposerState()
        val speech = SpeechDraftState(composer)

        speech.updatePartial("你好")
        speech.commitActive()
        composer.deletePrevious()

        assertEquals("你", composer.text)
        assertFalse(speech.hasActiveHypothesis)
    }
}
