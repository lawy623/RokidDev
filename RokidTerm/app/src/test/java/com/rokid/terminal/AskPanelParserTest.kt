package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests against the REAL device capture (2026-08-10, frame dump chunk 271):
 * an AskUserQuestion panel with 3 custom options, Type something. and
 * Chat about this, subtitle lines, and the help line.
 */
class AskPanelParserTest {

    private val realPanel = listOf(
        "  ☐ 测试场景 ",
        "你想用这个交互选择面板来做什么测试？",
        "❯ 1. 测试选项点击",
        "    只想测试直接点击选项时的响应效果",
        "  2. 测试文字输入",
        "    想通过\"其他\"选项输入自定义文字来测试自由输入",
        "  3. 两者都测试",
        "    既点击选项，也尝试输入自定义文字，完整测试这个交",
        "    互面板",
        "  4. Type something.",
        "───────────────────────────────────────",
        "  5. Chat about this",
        "Enter to select · ↑/↓ to navigate · Esc to cancel",
    )
    private val inputRow = 2

    @Test
    fun detectFindsRealPanel() {
        assertTrue(AskPanelParser.detect(realPanel, "1. 测试选项点击"))
        assertTrue(AskPanelParser.detect(realPanel, null))
    }

    @Test
    fun detectRejectsOrdinaryConversationAndCommandPanels() {
        // Ordinary conversation: no AskUserQuestion-only rows.
        val conversation = listOf(
            "❯ 你好",
            "● 好的",
            "  ⎿ 完成",
        )
        assertFalse(AskPanelParser.detect(conversation, "你好"))
        // /effort-style slider: no Type something. / Chat about this.
        val slider = listOf(
            "❯ Max effort",
            "←/→ to adjust",
        )
        assertFalse(AskPanelParser.detect(slider, "Max effort"))
        // A user typed "1. test" into a normal input line: numbered input
        // alone must NOT trigger (the Type-something row is the anchor).
        assertFalse(AskPanelParser.detect(conversation, "1. test"))
    }

    @Test
    fun parsesRealPanelOptionsWithNumbersAndSubtitles() {
        val options = AskPanelParser.parseOptions(realPanel, inputRow)
        assertEquals(5, options.size)

        assertEquals(1, options[0].number)
        assertEquals("测试选项点击", options[0].title)
        assertEquals("只想测试直接点击选项时的响应效果", options[0].subtitle)
        assertFalse(options[0].typeSomething)

        assertEquals(2, options[1].number)
        assertEquals("测试文字输入", options[1].title)
        assertEquals("想通过\"其他\"选项输入自定义文字来测试自由输入", options[1].subtitle)

        assertEquals(3, options[2].number)
        assertEquals("两者都测试", options[2].title)
        // Subtitle wraps across two rows and is joined.
        assertEquals("既点击选项，也尝试输入自定义文字，完整测试这个交 互面板", options[2].subtitle)

        assertEquals(4, options[3].number)
        assertEquals(AskPanelParser.TYPE_SOMETHING, options[3].title)
        assertTrue(options[3].typeSomething)

        assertEquals(5, options[4].number)
        assertEquals(AskPanelParser.CHAT_ABOUT_THIS, options[4].title)
        assertTrue(options[4].chatAbout)
    }

    @Test
    fun selectedIndexMirrorsInputLineEcho() {
        val options = AskPanelParser.parseOptions(realPanel, inputRow)

        // Input line echoes the selected option ("❯ 1. 测试选项点击").
        assertEquals(0, AskPanelParser.selectedIndex(realPanel, inputRow, options))
        val downToType = realPanel.toMutableList()
        downToType[inputRow] = "❯ 4. Type something."
        assertEquals(3, AskPanelParser.selectedIndex(downToType, inputRow, options))
        val downToChat = realPanel.toMutableList()
        downToChat[inputRow] = "❯ 5. Chat about this"
        assertEquals(4, AskPanelParser.selectedIndex(downToChat, inputRow, options))
    }

    @Test
    fun selectedIndexDefaultsToZeroWithoutNumber() {
        val options = AskPanelParser.parseOptions(realPanel, inputRow)
        val rows = realPanel.toMutableList()
        rows[inputRow] = "❯ 你好"
        assertEquals(0, AskPanelParser.selectedIndex(rows, inputRow, options))
    }

    @Test
    fun parseStopsAtHelpLineAndSkipsTrailingNoise() {
        val rows = realPanel + listOf(
            "  6. 面板之后的行",
            "普通终端输出",
        )
        val options = AskPanelParser.parseOptions(rows, inputRow)
        assertEquals(5, options.size) // the trailing rows are ignored
    }

    // Real multi-select capture (2026-08-10): options carry [ ] checkboxes,
    // Type something. appears WITHOUT the period, and the panel has the
    // same help line.
    private val multiSelectPanel = listOf(
        "❯ 1. [ ] 选项 A",
        "  选项一，可以和其他选项同时选中",
        "  2. [ ] 选项 B",
        "  3. [x] 选项 C",
        "  5. [ ] Type something",
        "     Submit",
        "  6. Chat about this",
        "Enter to select · ↑/↓ to navigate · Esc to cancel",
    )

    @Test
    fun multiSelectPanelParsesCheckboxes() {
        val options = AskPanelParser.parseOptions(multiSelectPanel, 0)
        assertEquals(5, options.size)

        assertTrue(options[0].checkbox)
        assertEquals("选项 A", options[0].title)
        assertFalse(options[0].typeSomething)

        assertTrue(options[2].checkbox)
        assertEquals("选项 C", options[2].title)

        // Type something without the period still flags as the type entry
        // (its [ ] marker is cosmetic — the toggle logic excludes it).
        assertTrue(options[3].typeSomething)
        assertEquals("Type something", options[3].title)

        assertTrue(options[4].chatAbout)
    }

    @Test
    fun multiSelectPanelDetectsAsAskPanel() {
        assertTrue(AskPanelParser.detect(multiSelectPanel, "1. [ ] 选项 A"))
        assertTrue(AskPanelParser.detect(multiSelectPanel, null))
    }
}
