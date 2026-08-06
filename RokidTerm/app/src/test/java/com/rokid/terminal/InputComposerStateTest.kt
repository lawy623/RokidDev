package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputComposerStateTest {
    @Test
    fun `inserts at a cursor moved through Chinese text`() {
        val composer = InputComposerState()
        composer.insertText("你好")
        assertTrue(composer.moveLeft())
        composer.insertText("，Claude")

        assertEquals("你，Claude好", composer.text)
        assertEquals("你，Claude".length, composer.cursor)
    }

    @Test
    fun `deletes a surrogate pair as one grapheme`() {
        val composer = InputComposerState()
        composer.insertText("A😀B")
        composer.moveLeft()

        assertTrue(composer.deletePrevious())
        assertEquals("AB", composer.text)
        assertEquals(1, composer.cursor)
    }

    @Test
    fun `deletes combining mark sequence together`() {
        val composer = InputComposerState()
        composer.insertText("Cafe\u0301")

        assertTrue(composer.deletePrevious())
        assertEquals("Caf", composer.text)
        assertEquals(3, composer.cursor)
    }

    @Test
    fun `moves across a ZWJ family emoji without entering it`() {
        val family = "👨‍👩‍👧‍👦"
        val composer = InputComposerState()
        composer.insertText("A${family}B")

        composer.moveLeft()
        assertEquals(1 + family.length, composer.cursor)
        composer.moveLeft()
        assertEquals(1, composer.cursor)
        composer.moveRight()
        assertEquals(1 + family.length, composer.cursor)
    }

    @Test
    fun `deletes emoji modifier and flag clusters together`() {
        val composer = InputComposerState()
        composer.insertText("👍🏽🇨🇳")

        composer.deletePrevious()
        assertEquals("👍🏽", composer.text)
        composer.deletePrevious()
        assertEquals("", composer.text)
        assertEquals(0, composer.cursor)
    }

    @Test
    fun `edge movement and deletion are no ops`() {
        val composer = InputComposerState()

        assertFalse(composer.moveLeft())
        assertFalse(composer.moveRight())
        assertFalse(composer.deletePrevious())
    }
}
