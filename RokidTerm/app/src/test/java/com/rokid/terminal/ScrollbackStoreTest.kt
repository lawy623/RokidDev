package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScrollbackStoreTest {
    private fun tempDir(): File = Files.createTempDirectory("scrollback-test").toFile()

    @Test
    fun fileNamesAreSanitizedAndKeyedByEndpointFolderSession() {
        val store = ScrollbackStore(tempDir())
        val file = store.file("cloud", "-srv", "11111111-2222-3333-4444-555555555555")

        assertEquals("scrollback_cloud_-srv_11111111-2222-3333-4444-555555555555.txt", file.name)
        assertTrue(store.file("a/b", "-srv", "id x").name.startsWith("scrollback_a_b_"))
    }

    @Test
    fun writeKeepsOnlyNewestRows() {
        val store = ScrollbackStore(tempDir())
        val file = store.file("cloud", "-srv", "id")
        val rows = (0 until 1500).map { "row $it" }

        store.write(file, rows)
        val loaded = store.read(file)

        assertEquals(ScrollbackStore.MAX_ROWS, loaded.size)
        assertEquals("row 500", loaded.first())
        assertEquals("row 1499", loaded.last())
    }

    @Test
    fun readMissingFileReturnsEmpty() {
        val store = ScrollbackStore(tempDir())
        assertTrue(store.read(store.file("cloud", "-srv", "id")).isEmpty())
    }

    @Test
    fun pruneDeletesOldestBeyondCap() {
        val dir = tempDir()
        val store = ScrollbackStore(dir)
        for (i in 0 until 5) {
            val f = store.file("cloud", "-srv", "id-$i")
            f.writeText("x")
            f.setLastModified(1_700_000_000_000L + i * 1000L)
        }

        store.prune("cloud", maxFiles = 3)

        val remaining = dir.listFiles { f -> f.name.startsWith("scrollback_cloud_") }!!.map { it.name }
        assertEquals(3, remaining.size)
        assertFalse(remaining.any { it.contains("id-0") || it.contains("id-1") })
        assertTrue(remaining.any { it.contains("id-4") })
    }

    @Test
    fun pruneIsNoopBelowCapAndIgnoresOtherEndpoints() {
        val dir = tempDir()
        val store = ScrollbackStore(dir)
        for (i in 0 until 4) store.file("cloud", "-srv", "id-$i").writeText("x")
        store.file("other", "-srv", "id-0").writeText("x")

        store.prune("cloud", maxFiles = 5)

        assertEquals(4, dir.listFiles { f -> f.name.startsWith("scrollback_cloud_") }!!.size)
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("scrollback_other_") }!!.size)
    }
}
