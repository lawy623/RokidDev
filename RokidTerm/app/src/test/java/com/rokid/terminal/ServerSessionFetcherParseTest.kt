package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSessionFetcherParseTest {

    @Test
    fun encodeDirMatchesClaudeCodePathEncoding() {
        assertEquals("-srv", ServerSessionFetcher.encodeDir("/srv"))
        assertEquals("-srv-RokidDev", ServerSessionFetcher.encodeDir("/srv/RokidDev"))
        assertEquals("-Users-l-wy623-Desktop", ServerSessionFetcher.encodeDir("/Users/l.wy623/Desktop"))
        assertEquals("plain", ServerSessionFetcher.encodeDir("plain"))
    }

    @Test
    fun parseListGroupsSessionsUnderFolders() {
        val text = "F\t/srv\t-srv\n" +
            "F\t/srv/RokidDev\t-srv-RokidDev\n" +
            "S\t-srv\t11111111-2222-3333-4444-555555555555\t1700000000000\tfirst  chat\n" +
            "S\t-srv\taaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\t1700000100000\tmulti line title\n"

        val folders = ServerSessionFetcher.parseList(text)

        assertEquals(2, folders.size)
        val srv = folders.first { it.path == "/srv" }
        assertEquals("-srv", srv.encodedDir)
        assertEquals(2, srv.sessions.size)
        assertEquals("first  chat", srv.sessions[0].title) // internal spaces preserved
        assertEquals(1_700_000_000_000L, srv.sessions[0].epochMillis)
        assertEquals("multi line title", srv.sessions[1].title)
        assertTrue(folders.first { it.path == "/srv/RokidDev" }.sessions.isEmpty())
    }

    @Test
    fun parseListIgnoresGarbageAndSkipsMalformedLines() {
        val text = "F\t/srv\t-srv\nsome noise\nS\t-srv\tid\tnotanumber\tno time\n"

        val folders = ServerSessionFetcher.parseList(text)

        assertEquals(1, folders.size)
        assertEquals(1, folders[0].sessions.size) // epoch falls back to 0
        assertEquals(0L, folders[0].sessions[0].epochMillis)
    }

    @Test
    fun parseListKeepsFolderOrderAndIsEmptySafe() {
        assertTrue(ServerSessionFetcher.parseList("").isEmpty())
        assertTrue(ServerSessionFetcher.parseList("# only comments").isEmpty())
    }

    @Test
    fun parseStatusReturnsSessionOrNull() {
        assertEquals(
            SessionStatus("123", "/srv", "id-9"),
            ServerSessionFetcher.parseStatus("pid\t123\t/srv\tid-9"),
        )
        assertNull(ServerSessionFetcher.parseStatus("pid\t123\t/srv\t-")?.sessionId)
        assertNull(ServerSessionFetcher.parseStatus("none"))
        assertNull(ServerSessionFetcher.parseStatus(""))
    }

    @Test
    fun parseSwitchResultDistinguishesOkAndError() {
        val ok = ServerSessionFetcher.parseSwitchResult("ok\t-srv\tid-9")
        assertEquals("-srv", ok?.first)
        assertEquals("id-9", ok?.second)
        assertNull(ServerSessionFetcher.parseSwitchResult("error\thelper missing"))
        assertNull(ServerSessionFetcher.parseSwitchResult(""))
    }
}
