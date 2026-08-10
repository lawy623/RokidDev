package com.rokid.terminal

/**
 * Detection and option parsing for Claude Code's AskUserQuestion panels
 * (real-device case 2026-08-10). Pure functions — no Android deps, JVM
 * testable. The panel renders the SELECTED option back on the input line
 * ("❯ 1. title"), the option block below it, and a help line
 * "Enter to select · ↑/↓ to navigate · Esc to cancel". Interaction is
 * arrows / Enter / Esc, identical to the command-panel contract.
 *
 * The Type something. / Chat about this rows are AskUserQuestion-specific
 * (command pickers like /model or /effort never show them), so they are
 * the detection anchor — /effort-style panels are never intercepted.
 */
object AskPanelParser {

    const val TYPE_SOMETHING = "Type something."
    const val CHAT_ABOUT_THIS = "Chat about this"
    const val HELP_MARKER = "Enter to select"
    // Multi-select panels render the entry as "Type something" (no period) —
    // the stem matches both forms.
    private const val TYPE_SOMETHING_STEM = "Type something"

    /** Parsed panel state for one frame: options + mirrored selection. */
    data class Snapshot(
        val options: List<Option>,
        val selected: Int,
        /** True when the panel is multi-select (any option carries a checkbox). */
        val multiSelect: Boolean = false,
        /**
         * True for the TAB form (help line "Tab/Arrow keys to navigate" +
         * a top bar of zones like "☒ 多选 | ☐ 自由输入 | ✔ Submit"): the
         * zones are switched with TAB, and free-text input happens in a
         * dedicated zone — NOT the Type-something entry of the standard
         * forms (real capture 2026-08-10).
         */
        val tabPanel: Boolean = false,
    )

    data class Option(
        val title: String,
        val number: Int,
        val subtitle: String = "",
        val typeSomething: Boolean = false,
        val chatAbout: Boolean = false,
        /** Multi-select checkbox ([ ]/[x]) — single-select panels never have it. */
        val checkbox: Boolean = false,
        /** Checked ([x]) — multi-select only. */
        val checked: Boolean = false,
    )

    // The FIRST option's numbered row IS the input line ("❯ 1. 测试选项
    // 点击" echoes the selection), so the prompt glyph is allowed.
    private val NUMBERED = Regex("^[\\s❯]*([0-9]+)\\.\\s*(.*)$")
    private val NUMBER_IN_LINE = Regex("(\\d+)\\.")

    /** True when the frame shows an AskUserQuestion panel. */
    fun detect(rows: List<String>, inputLineText: String?): Boolean {
        if (rows.any { it.contains(TYPE_SOMETHING_STEM) || it.contains(CHAT_ABOUT_THIS) }) return true
        return false
    }

    /** True when the panel is the TAB form ("Tab/Arrow keys to navigate"). */
    fun isTabPanel(rows: List<String>): Boolean =
        rows.any { it.contains("Tab/Arrow keys to navigate") }

    /**
     * Parses the option block starting AT the input row (the input line
     * itself is the first option's numbered row — it echoes the selected
     * option), up to the help line. Numbered rows start an option
     * ("2. 测试文字输入"); following rows that are not numbered (nor the
     * help line, nor a divider) are subtitle continuation lines, joined
     * onto the current option (option subtitles wrap across rows — real
     * case 2026-08-10).
     */
    fun parseOptions(rows: List<String>, inputRowIndex: Int): List<Option> {
        val numbered = mutableListOf<Pair<Int, String>>()
        val subtitles = LinkedHashMap<Int, MutableList<String>>()
        var currentNumber: Int? = null
        for (index in inputRowIndex until rows.size) {
            val line = rows[index]
            if (line.contains(HELP_MARKER)) break
            val match = NUMBERED.matchEntire(line)
            if (match != null) {
                val number = match.groupValues[1].toIntOrNull() ?: continue
                numbered += number to match.groupValues[2]
                currentNumber = number
                subtitles.getOrPut(number) { mutableListOf() }
                continue
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.all { it == '─' || it == '-' || it == '=' }) continue
            if (currentNumber != null) subtitles[currentNumber]!! += trimmed
        }
        return numbered.map { (number, rawTitle) ->
            // Multi-select panels render each option as "N. [ ] 标题" or
            // "N. [x] 标题" — strip the checkbox marker and flag it.
            val marker = Regex("^\\[([ x])\\]\\s*").find(rawTitle)
            val title = marker?.let { rawTitle.substring(it.range.last + 1) } ?: rawTitle
            Option(
                title = title,
                number = number,
                subtitle = subtitles[number]?.joinToString(" ") ?: "",
                typeSomething = title.startsWith(TYPE_SOMETHING_STEM),
                chatAbout = title.contains(CHAT_ABOUT_THIS),
                checkbox = marker != null,
                checked = marker != null && marker.groupValues[1] == "x",
            )
        }
    }

    /**
     * Index of the option echoed on the input line ("❯ 1. 测试选项点击" →
     * the option numbered 1), mirroring Claude's selection. 0 when the
     * input line carries no number.
     */
    fun selectedIndex(rows: List<String>, inputRowIndex: Int, options: List<Option>): Int {
        if (options.isEmpty()) return 0
        val number = NUMBER_IN_LINE.find(rows[inputRowIndex])
            ?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return options.indexOfFirst { it.number == number }.takeIf { it >= 0 } ?: 0
    }
}
