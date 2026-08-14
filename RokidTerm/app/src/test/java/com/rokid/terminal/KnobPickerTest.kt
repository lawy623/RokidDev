package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COIDEA knob letter/digit picker (design 2026-08-14): rotation steps
 * through the item set from an idle position one before the first item —
 * only right rotation wakes it, right stops at the last item (no wrap),
 * and the 1 s stop commits the candidate as normal text.
 */
class KnobPickerTest {

    @Test
    fun idleStartsBeforeFirstItem() {
        val picker = KnobPicker(KnobPicker.LETTERS)
        assertFalse(picker.active)
        assertNull(picker.candidate())
    }

    @Test
    fun leftRotationIsNoopWhileIdle() {
        val picker = KnobPicker(KnobPicker.LETTERS)
        assertFalse(picker.stepLeft())
        assertFalse(picker.active)
        assertNull(picker.candidate())
    }

    @Test
    fun rightRotationWalksALetterRange() {
        val picker = KnobPicker(KnobPicker.LETTERS)
        assertTrue(picker.stepRight())
        assertEquals('a', picker.candidate())
        repeat(25) { picker.stepRight() }
        assertEquals('z', picker.candidate()) // 26th item
        picker.stepRight()
        assertEquals('A', picker.candidate()) // a-z then A-Z (52 items)
        repeat(25) { picker.stepRight() }
        assertEquals('Z', picker.candidate())
        assertFalse(picker.stepRight()) // stop at Z, no wrap
        assertEquals('Z', picker.candidate())
    }

    @Test
    fun digitsRangeIsZeroToNine() {
        val picker = KnobPicker(KnobPicker.DIGITS)
        picker.stepRight()
        assertEquals('0', picker.candidate())
        repeat(9) { picker.stepRight() }
        assertEquals('9', picker.candidate())
        assertFalse(picker.stepRight())
    }

    @Test
    fun leftFromFirstItemReturnsToIdle() {
        val picker = KnobPicker(KnobPicker.DIGITS)
        picker.stepRight() // -> '0'
        assertTrue(picker.stepLeft())
        assertFalse(picker.active) // back at the idle position
        assertNull(picker.candidate())
    }

    @Test
    fun leftRightCanChangeTheStopPosition() {
        val picker = KnobPicker(KnobPicker.LETTERS)
        picker.stepRight() // a
        picker.stepRight() // b
        picker.stepRight() // c
        picker.stepLeft() // b
        picker.stepRight() // c
        assertEquals('c', picker.candidate())
    }

    @Test
    fun resetReturnsToIdle() {
        val picker = KnobPicker(KnobPicker.LETTERS)
        repeat(30) { picker.stepRight() }
        assertTrue(picker.active)
        picker.reset()
        assertFalse(picker.active)
        assertNull(picker.candidate())
    }
}
