package com.rokid.terminal

import android.util.Log
import java.io.File

/**
 * App-private local cache of sent composer drafts (input history), keyed per
 * CONVERSATION (user decision 2026-08-08: input history must not be shared
 * across conversations). The key is the conversation's folderKey/sessionId;
 * each key owns its own file `input_history_<key>.txt` (key == null uses the
 * legacy global file, which is never merged into per-conversation files).
 *
 * Deliberately local-only (user decision 2026-08-05): re-typing is the
 * fallback when a draft was not sent through RokidTerm. Storage is an
 * app-private file — never /sdcard, per the security invariants. Draft text
 * never leaves the device and is never logged.
 *
 * Threading: called from the main thread only (key handlers / composer).
 */
class InputHistory(filesDir: File, key: String? = null) {
    private val file = if (key == null) {
        File(filesDir, "input_history.txt")
    } else {
        File(filesDir, "input_history_${sanitize(key)}.txt")
    }
    private val entries = mutableListOf<String>()
    // Claude Code's next-input suggestion (from the "❯" line, light text).
    // In-memory only; never persisted, never part of the history entries.
    // It occupies the slot one step past the empty entry.
    private var suggestion: String? = null
    // Cursor models a list [oldest ... newest, empty]. entries[0] is the
    // NEWEST draft; the empty entry sits at index == entries.size (one step
    // past the newest). The pointer STARTS at the empty entry, and key 4
    // (older) moves toward index 0 (oldest), key 6 (newer) toward the empty
    // entry — never wrapping (user-specified 2026-08-05).
    private var cursor = 0

    init {
        load()
        cursor = entries.size
    }

    val size: Int get() = entries.size

    fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (entries.firstOrNull() == trimmed) return // dedupe consecutive sends
        entries.add(0, trimmed)
        if (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        persist()
        cursor = entries.size // back to the empty entry (fresh input)
    }

    /**
     * Move the browsing cursor. Sequence (key 6 walks right), user model
     * 2026-08-06:
     * [oldest … newest] → empty (light suggestion shown by remote Claude)
     * → suggestion (dark). The pointer STARTS at the empty entry. Key 4
     * (older) walks left: empty → newest → older … oldest. Key 6 (newer)
     * walks right: … → newest → empty → suggestion. Clamped at both ends;
     * the suggestion slot is callable repeatedly (not consumed), and the
     * suggestion is never stored in history.
     *
     * Returns the dark text (history draft or suggestion), or null for the
     * empty entry (no overlay — the remote light suggestion shows).
     */
    fun peek(direction: Int): String? {
        val maxIndex = if (suggestion != null) entries.size + 1 else entries.size
        val next = when {
            cursor == entries.size && direction < 0 -> 0
            cursor == entries.size && direction > 0 -> maxIndex
            cursor == 0 && direction > 0 -> entries.size
            cursor == 0 && direction < 0 -> 1
            cursor == maxIndex && direction < 0 -> entries.size
            cursor == maxIndex && direction > 0 -> maxIndex
            direction > 0 -> (cursor - 1).coerceAtLeast(0)
            else -> (cursor + 1).coerceAtMost(entries.size - 1)
        }
        cursor = next
        return when {
            next < entries.size -> entries[next]
            next == entries.size + 1 -> suggestion
            else -> null
        }
    }

    /** Updates Claude's suggestion; null/blank clears the slot. */
    fun setSuggestion(text: String?) {
        suggestion = text?.trim()?.takeIf { it.isNotEmpty() }
        if (cursor > entries.size + 1) cursor = entries.size + 1
    }

    /** Current suggestion text, or null. */
    fun suggestion(): String? = suggestion

    /** Moves the pointer onto the suggestion slot; false when none. */
    fun jumpToSuggestion(): Boolean {
        if (suggestion == null) return false
        cursor = entries.size + 1
        return true
    }

    /** True while a history draft (not the empty entry) is previewed. */
    fun hasPreview(): Boolean = cursor in entries.indices

    /** Returns the pointer to the empty entry (fresh input line). */
    fun resetCursor() {
        cursor = entries.size
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            file.readLines().forEach { line ->
                if (line.isNotEmpty()) entries.add(line)
            }
        }.onFailure { Log.w("InputHistory", "load failed", it) }
    }

    private fun persist() {
        runCatching {
            file.writeText(entries.joinToString("\n"))
        }.onFailure { Log.w("InputHistory", "persist failed", it) }
    }

    companion object {
        private const val MAX_ENTRIES = 50
        fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
