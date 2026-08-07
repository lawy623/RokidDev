package com.rokid.terminal

/**
 * Local command-palette state (contract in `rules/composer.md` — "local
 * command palette"). Pure JVM so selection/cycling logic is unit-testable;
 * the item list is supplied by the caller (server fetch with local
 * fallback). The palette is modal: while open, directional gestures
 * navigate the list, confirm inserts the selected `/command` into the
 * composer draft, and cancel changes no draft/remote state.
 */
class CommandPaletteState {

    var items: List<String> = emptyList()
        private set
    var open: Boolean = false
        private set
    var selectedIndex: Int = 0
        private set

    /** Replaces the item list (e.g. after a server fetch); keeps selection in range. */
    fun setItems(newItems: List<String>) {
        items = newItems
        selectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    fun open() {
        open = true
        selectedIndex = 0
    }

    fun close() {
        open = false
    }

    fun toggle() {
        if (open) close() else open()
    }

    /** Moves the selection by [delta] with wrap-around. No-op when empty. */
    fun moveSelection(delta: Int) {
        if (items.isEmpty()) return
        selectedIndex = ((selectedIndex + delta) % items.size + items.size) % items.size
    }

    /** The selected command (e.g. "/skills"), or null when closed or empty. */
    fun select(): String? = if (open && items.isNotEmpty()) items[selectedIndex] else null

    companion object {
        /** Local palette action that opens the conversation picker (design 2026-08-07). */
        const val SESSION_PICKER_ITEM = "[切换对话]"

        /**
         * The displayed palette: the bare "/" (voice-continuation) and the
         * session-picker action always lead, followed by the sorted unique
         * defaults merged with any server-side custom commands.
         */
        fun displayList(defaults: List<String>, remote: List<String>?): List<String> =
            listOf("/", SESSION_PICKER_ITEM) + (defaults + (remote ?: emptyList())).distinct().sorted()
    }
}
