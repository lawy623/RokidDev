package com.rokid.terminal

/** One remote Claude session (id = JSONL filename; title = first user message). */
data class RemoteSession(val id: String, val title: String, val epochMillis: Long)

/** A selectable folder (project) with its sessions (design 2026-08-07). */
data class RemoteFolder(val path: String, val encodedDir: String, val sessions: List<RemoteSession>)

/** What the user confirmed: a folder plus an optional resume id (null = new conversation). */
data class SessionTarget(val folderPath: String, val sessionId: String?)

/** `rokid-sessions status` output (pid is informational). */
data class SessionStatus(val pid: String, val cwd: String?, val sessionId: String?)
