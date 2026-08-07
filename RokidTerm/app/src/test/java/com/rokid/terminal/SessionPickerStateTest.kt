package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPickerStateTest {
    private val folderA = RemoteFolder("/srv", "-srv", listOf(
        RemoteSession("id-1", "first chat", 1_700_000_000_000L),
        RemoteSession("id-2", "second chat", 1_700_000_100_000L),
    ))
    private val folderB = RemoteFolder("/srv/RokidDev", "-srv-RokidDev", emptyList())

    @Test
    fun openStartsAtFolderLevelWithPreferredMarkers() {
        val picker = SessionPickerState()
        picker.setFolders(listOf(folderA, folderB), failed = false)

        picker.open("/srv", "id-2")

        assertTrue(picker.open)
        assertEquals(0, picker.level)
        assertEquals(0, picker.folderIndex)
        assertEquals("/srv", picker.currentFolderPath)
        assertEquals("id-2", picker.currentSessionId)
        assertTrue(picker.loading)
        assertEquals(3, picker.conversationCount) // 2 sessions + 1 new-slot
    }

    @Test
    fun moveWrapsWithinFolderLevel() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA, folderB), failed = false)
        }

        picker.move(-1)
        assertEquals(1, picker.folderIndex)
        picker.move(1)
        assertEquals(0, picker.folderIndex)
    }

    @Test
    fun confirmOnFolderLevelDescendsToConversations() {
        val picker = SessionPickerState().apply {
            setFolders(listOf(folderA, folderB), failed = false)
            open(null, null)
        }

        assertNull(picker.confirm()) // descends, returns null
        assertEquals(1, picker.level)
        assertEquals(0, picker.sessionIndex)
        assertEquals(3, picker.conversationCount) // new-slot + 2 sessions
    }

    @Test
    fun confirmOnConversationLevelReturnsTarget() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm() // descend
        picker.move(2)   // wrap: 0 -> 1 -> 2 (the second session)

        assertEquals(SessionTarget("/srv", "id-2"), picker.confirm())
        assertTrue(picker.open) // confirm does not close; the app closes it
    }

    @Test
    fun newConversationSlotYieldsNullSessionId() {
        val picker = SessionPickerState().apply {
            setFolders(listOf(folderA), failed = false)
            open(null, null)
        }
        picker.confirm() // descend, sessionIndex = 0 = new slot

        assertEquals(SessionTarget("/srv", null), picker.confirm())
    }

    @Test
    fun backMovesUpOneLevelThenCloses() {
        val picker = SessionPickerState().apply {
            setFolders(listOf(folderA, folderB), failed = false)
            open(null, null)
        }
        picker.confirm() // descend

        assertTrue(picker.back())
        assertEquals(0, picker.level)
        assertFalse(picker.back()) // level 0: back() returns false, state stays open
        assertTrue(picker.open)
    }

    @Test
    fun moveIsBlockedWhileLoading() {
        val picker = SessionPickerState().apply {
            setFolders(listOf(folderA, folderB), failed = false)
            open(null, null)
        }

        picker.move(1) // loading == true after open()
        assertEquals(0, picker.folderIndex)
    }

    @Test
    fun emptyFoldersAreSafe() {
        val picker = SessionPickerState().apply {
            setFolders(emptyList(), failed = true)
            open(null, null)
        }

        assertTrue(picker.error)
        picker.move(1)
        assertEquals(0, picker.folderIndex)
        picker.confirm() // level 0 with no folders: no-op, stays at level 0
        assertEquals(0, picker.level)
        assertEquals(1, picker.conversationCount)
    }

    @Test
    fun setFoldersReappliedAfterOpenKeepsLevelsUsable() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm()
        picker.move(1)
        picker.setFolders(listOf(folderA, folderB), failed = false)

        assertEquals(0, picker.level)
        assertEquals(0, picker.folderIndex)
        assertFalse(picker.loading)
    }
}
