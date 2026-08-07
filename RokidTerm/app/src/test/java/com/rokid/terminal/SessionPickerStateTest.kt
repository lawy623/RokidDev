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

    // --- Task 14: delete-arm state ---

    @Test
    fun armDeleteWorksOnSessionRowAndBlocksCurrent() {
        val picker = SessionPickerState().apply {
            open(null, "id-1")
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm() // descend
        picker.move(1)   // session "id-1" — the CURRENT one (▶)

        assertFalse(picker.armDelete()) // current session is not deletable

        picker.move(1)   // session "id-2"
        assertTrue(picker.armDelete())
        assertTrue(picker.deleteArmed)
        assertEquals(0, picker.deleteOption) // default = cancel (safe)
    }

    @Test
    fun armDeleteFailsOnNewConversationSlot() {
        val picker = SessionPickerState().apply {
            open(null, null)
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm() // new-slot selected

        assertFalse(picker.armDelete())
        assertFalse(picker.deleteArmed)
    }

    @Test
    fun moveDeleteOptionWrapsAndConfirmExecutesOnlyOnDelete() {
        val picker = SessionPickerState().apply {
            open(null, "id-1")
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm()
        picker.move(2) // "id-2"
        picker.armDelete()

        assertFalse(picker.confirmDeleteOption()) // on 取消 → caller disarms
        picker.moveDeleteOption(1)
        assertTrue(picker.confirmDeleteOption())  // on 删除 → caller deletes
        picker.moveDeleteOption(1)                // wraps back to 取消
        assertFalse(picker.confirmDeleteOption())
    }

    @Test
    fun armedStateBlocksNormalNavigation() {
        val picker = SessionPickerState().apply {
            open(null, "id-1")
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm()
        picker.move(2)
        picker.armDelete()

        val level = picker.level
        val index = picker.sessionIndex
        picker.move(1)
        assertEquals(level, picker.level)
        assertEquals(index, picker.sessionIndex)
    }

    @Test
    fun removeCurrentSessionClampsSelectionAndDisarms() {
        val picker = SessionPickerState().apply {
            open(null, "id-1")
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm()
        picker.move(2)
        picker.armDelete()

        picker.removeCurrentSession()

        assertFalse(picker.deleteArmed)
        assertEquals(2, picker.conversationCount) // new-slot + id-1
        assertEquals(1, picker.sessionIndex)      // clamped to last row
    }

    @Test
    fun removeSessionRemovesByIdentityAfterNavigation() {
        val picker = SessionPickerState().apply {
            open(null, "id-1")
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm()
        picker.move(2) // "id-2"
        picker.move(1) // back to "id-1"

        picker.removeSession("/srv", "id-2")

        assertEquals(listOf("id-1"), picker.selectedFolder()?.sessions?.map { it.id })
        assertFalse(picker.deleteArmed)
    }

    @Test
    fun selectFolderMovesToRememberedFolder() {
        val picker = SessionPickerState().apply {
            open("/srv/RokidDev", null)
            setFolders(listOf(folderA, folderB), failed = false)
        }

        assertTrue(picker.selectFolder(picker.currentFolderPath))
        assertEquals(1, picker.folderIndex)

        assertFalse(picker.selectFolder("/nonexistent"))
        assertEquals(1, picker.folderIndex)
        assertFalse(picker.selectFolder(null))
    }

    @Test
    fun selectCurrentSessionMovesOntoTheMarkedConversation() {
        val picker = SessionPickerState().apply {
            open(null, "id-2")
            setFolders(listOf(folderA), failed = false)
        }
        picker.confirm() // descend, new-chat slot selected

        picker.selectCurrentSession()

        assertEquals(2, picker.sessionIndex) // + New Chat(0), id-1(1), id-2(2)

        // Not in the list: stays on the new-chat slot.
        val other = SessionPickerState().apply {
            open(null, "unknown-id")
            setFolders(listOf(folderA), failed = false)
        }
        other.confirm()
        other.selectCurrentSession()
        assertEquals(0, other.sessionIndex)
    }
}