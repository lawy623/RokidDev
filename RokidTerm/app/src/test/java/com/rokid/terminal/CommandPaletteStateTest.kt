package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPaletteStateTest {
    private val sample = listOf("/help", "/clear", "/skills")

    @Test
    fun openStartsAtFirstItem() {
        val palette = CommandPaletteState().apply { setItems(sample) }

        palette.open()

        assertTrue(palette.open)
        assertEquals(0, palette.selectedIndex)
        assertEquals("/help", palette.select())
    }

    @Test
    fun toggleClosesAndReopens() {
        val palette = CommandPaletteState().apply { setItems(sample) }
        palette.open()
        palette.toggle()
        assertFalse(palette.open)
        assertNull(palette.select())

        palette.toggle()
        assertTrue(palette.open)
        assertEquals(0, palette.selectedIndex)
    }

    @Test
    fun selectionWrapsInBothDirections() {
        val palette = CommandPaletteState().apply { setItems(sample) }
        palette.open()

        palette.moveSelection(-1)
        assertEquals(2, palette.selectedIndex)
        assertEquals("/skills", palette.select())

        palette.moveSelection(1)
        assertEquals(0, palette.selectedIndex)
    }

    @Test
    fun closeKeepsListButReturnsNullSelection() {
        val palette = CommandPaletteState().apply { setItems(sample) }
        palette.open()
        palette.moveSelection(1)
        palette.close()

        assertFalse(palette.open)
        assertNull(palette.select())
        // Reopening starts fresh at the first item.
        palette.open()
        assertEquals(0, palette.selectedIndex)
    }

    @Test
    fun emptyListIsSafe() {
        val palette = CommandPaletteState()

        palette.open()
        assertTrue(palette.open)
        assertNull(palette.select())
        palette.moveSelection(1)
        assertNull(palette.select())
    }

    @Test
    fun setItemsKeepsSelectionInRange() {
        val palette = CommandPaletteState().apply { setItems(sample) }
        palette.open()
        palette.moveSelection(2)

        palette.setItems(listOf("/help"))
        assertEquals(0, palette.selectedIndex)
        palette.setItems(emptyList())
        assertNull(palette.select())
    }

    @Test
    fun displayListLeadsWithSlashAndSessionItemThenSortedUniqueCommands() {
        val defaults = listOf("/model", "/usage", "/clear")
        val remote = listOf("/usage", "/custom")

        val list = CommandPaletteState.displayList(defaults, remote)

        assertEquals("/", list[0])
        assertEquals(CommandPaletteState.SESSION_PICKER_ITEM, list[1])
        assertEquals(listOf("/clear", "/custom", "/model", "/usage"), list.drop(2))
    }

    @Test
    fun displayListWithoutRemoteStillLeadsWithSpecialItems() {
        val list = CommandPaletteState.displayList(listOf("/model"), null)

        assertEquals("/", list[0])
        assertEquals(CommandPaletteState.SESSION_PICKER_ITEM, list[1])
        assertEquals(listOf("/model"), list.drop(2))
    }
}
