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

    /** Moves the selection with wrap-around within the current level; no-op while loading/empty. */
    fun move(delta: Int) {
        if (!open || loading) return
        if (level == 0) {
            if (folders.isEmpty()) return
            folderIndex = ((folderIndex + delta) % folders.size + folders.size) % folders.size
        } else {
            sessionIndex = ((sessionIndex + delta) % conversationCount + conversationCount) % conversationCount
        }
    }

    /** Level 1 -> level 0 (true); level 0 -> stays open, returns false (caller closes). */
    fun back(): Boolean {
        if (!open || level != 1) return false
        level = 0
        sessionIndex = 0
        return true
    }

    fun selectedFolder(): RemoteFolder? = folders.getOrNull(folderIndex)

    /**
     * Level 0: descends to conversations, returns null. Level 1: returns the
     * chosen target (session id null = new conversation). Does NOT close.
     */
    fun confirm(): SessionTarget? {
        if (!open) return null
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

    /** Updates the ▶ markers after a successful switch. */
    fun markCurrent(folderPath: String?, sessionId: String?) {
        currentFolderPath = folderPath
        currentSessionId = sessionId
    }
}
