package com.rokid.terminal

/**
 * Two-level conversation picker state (design:
 * docs/superpowers/specs/2026-08-07-multi-conversation-design.md). Pure JVM
 * so navigation logic is unit-testable. Level 0 = folders; level 1 = a
 * "＋ 新对话" slot at index 0 followed by the selected folder's sessions.
 * Modal: while open, directional gestures navigate and confirm/cancel decide.
 */
class SessionPickerState {

    var folders: List<RemoteFolder> = emptyList()
        private set
    var open: Boolean = false
        private set
    var loading: Boolean = false
        private set
    var error: Boolean = false
        private set
    var level: Int = 0
        private set
    var folderIndex: Int = 0
        private set
    var sessionIndex: Int = 0
        private set
    var currentFolderPath: String? = null
        private set
    var currentSessionId: String? = null
        private set

    var deleteArmed: Boolean = false
        private set
    var deleteOption: Int = 0
        private set

    /**
     * Session-visible count: the new-conversation slot plus the selected
     * folder's sessions. Always at least 1 (the new-conversation slot)
     * even when the folder has zero sessions or no folder is selected.
     */
    val conversationCount: Int
        get() = (selectedFolder()?.sessions?.size ?: 0) + 1

    /** Applies the fetched list; resets navigation to the folder level. */
    fun setFolders(value: List<RemoteFolder>, failed: Boolean) {
        folders = value
        error = failed
        loading = false
        level = 0
        folderIndex = 0
        sessionIndex = 0
    }

    /** Opens with the remembered folder/session as the ▶ markers; loading until setFolders. */
    fun open(preferredFolderPath: String?, preferredSessionId: String?) {
        currentFolderPath = preferredFolderPath
        currentSessionId = preferredSessionId
        open = true
        loading = true
        level = 0
        folderIndex = 0
        sessionIndex = 0
    }

    fun close() {
        open = false
        loading = false
    }

    /** Moves the selection with wrap-around within the current level; no-op while loading/empty/armed. */
    fun move(delta: Int) {
        if (!open || loading || deleteArmed) return
        if (level == 0) {
            if (folders.isEmpty()) return
            folderIndex = ((folderIndex + delta) % folders.size + folders.size) % folders.size
        } else {
            sessionIndex = ((sessionIndex + delta) % conversationCount + conversationCount) % conversationCount
        }
    }

    /** Level 1 -> level 0 (true); level 0 -> stays open, returns false (caller closes). Blocked when armed. */
    fun back(): Boolean {
        if (!open || deleteArmed || level != 1) return false
        level = 0
        sessionIndex = 0
        return true
    }

    fun selectedFolder(): RemoteFolder? = folders.getOrNull(folderIndex)

    /**
     * Moves the folder-level selection to the folder at [path] (e.g. the
     * remembered last-used folder after a fetch). No-op (false) when the
     * picker is closed, the path is null, or the folder is not listed —
     * the selection then stays at the first folder (the base dir).
     */
    fun selectFolder(path: String?): Boolean {
        if (!open || path == null) return false
        val index = folders.indexOfFirst { it.path == path }
        if (index < 0) return false
        folderIndex = index
        return true
    }

    /**
     * Level 0: descends to conversations, returns null. Level 1: returns the
     * chosen target (session id null = new conversation). Does NOT close.
     */
    fun confirm(): SessionTarget? {
        if (!open || deleteArmed) return null
        if (level == 0) {
            if (folders.isEmpty()) return null
            level = 1
            sessionIndex = 0
            return null
        }
        val folder = selectedFolder() ?: return null
        val session = folder.sessions.getOrNull(sessionIndex - 1)
        return SessionTarget(folder.path, session?.id)
    }

    /**
     * Arms the delete selector on the selected session row. False when the
     * picker is closed, not on a session row, or the row IS the current
     * conversation (▶) — the running conversation is never deletable.
     */
    fun armDelete(): Boolean {
        if (!open || level != 1 || sessionIndex < 1) return false
        val session = selectedFolder()?.sessions?.getOrNull(sessionIndex - 1) ?: return false
        if (session.id == currentSessionId) return false
        deleteArmed = true
        deleteOption = 0 // default on 取消 (safe position)
        return true
    }

    fun disarmDelete() {
        deleteArmed = false
        deleteOption = 0
    }

    /** Moves between 取消 (0) and 删除 (1) with wrap; no-op when not armed. */
    fun moveDeleteOption(delta: Int) {
        if (!deleteArmed) return
        deleteOption = ((deleteOption + delta) % 2 + 2) % 2
    }

    /** True only when armed on 删除 — the caller executes the delete. */
    fun confirmDeleteOption(): Boolean = deleteArmed && deleteOption == 1

    /** Removes the selected session from the folder and clamps the selection. */
    fun removeCurrentSession() {
        val folder = selectedFolder() ?: return
        val index = sessionIndex - 1
        val updated = folder.sessions.filterIndexed { i, _ -> i != index }
        folders = folders.map { if (it.encodedDir == folder.encodedDir) it.copy(sessions = updated) else it }
        sessionIndex = sessionIndex.coerceAtMost(conversationCount - 1)
        disarmDelete()
    }

    /** Removes the session with [sessionId] from the folder at [folderPath] (identity-based; safe
     *  even if the user navigated during the delete round trip) and disarms. */
    fun removeSession(folderPath: String, sessionId: String) {
        val folder = folders.firstOrNull { it.path == folderPath } ?: return
        val updated = folder.sessions.filterNot { it.id == sessionId }
        folders = folders.map { if (it.path == folderPath) it.copy(sessions = updated) else it }
        sessionIndex = sessionIndex.coerceAtMost(conversationCount - 1)
        disarmDelete()
    }

    /** Updates the ▶ markers after a successful switch. */
    fun markCurrent(folderPath: String?, sessionId: String?) {
        currentFolderPath = folderPath
        currentSessionId = sessionId
    }
}
