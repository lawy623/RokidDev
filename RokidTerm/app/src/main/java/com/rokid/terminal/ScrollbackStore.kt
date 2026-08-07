package com.rokid.terminal

import java.io.File

/**
 * Per-conversation scrollback persistence (design spec 2026-08-07).
 * Files: scrollback_<endpointId>_<folderKey>_<sessionId>.txt in filesDir,
 * bounded at MAX_ROWS rows per file and MAX_FILES per endpoint (LRU by
 * mtime). Only java.io.File — unit-testable on the JVM. The legacy
 * per-endpoint file (scrollback_<endpointId>.txt) is read via [legacyFile]
 * for one-time migration.
 */
class ScrollbackStore(private val filesDir: File) {

    fun file(endpointId: String, folderKey: String, sessionId: String): File =
        File(filesDir, "scrollback_${sanitize(endpointId)}_${sanitize(folderKey)}_${sanitize(sessionId)}.txt")

    /** Pre-conversation per-endpoint file (created by builds before 2026-08-08). */
    fun legacyFile(endpointId: String): File =
        File(filesDir, "scrollback_${sanitize(endpointId)}.txt")

    fun read(file: File): List<String> =
        if (file.exists()) {
            runCatching { file.readText().split("\n") }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

    fun write(file: File, rows: List<String>) {
        if (rows.isEmpty()) return
        runCatching {
            file.writeText(rows.takeLast(MAX_ROWS).joinToString("\n"))
        }
    }

    /** Deletes the oldest files of this endpoint until at most [maxFiles] remain. */
    fun prune(endpointId: String, maxFiles: Int = MAX_FILES) {
        val prefix = "scrollback_${sanitize(endpointId)}_"
        val files = filesDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.sortedBy { it.lastModified() }
            ?: return
        val overflow = files.size - maxFiles
        if (overflow > 0) files.take(overflow).forEach { runCatching { it.delete() } }
    }

    companion object {
        const val MAX_ROWS = 1000
        const val MAX_FILES = 30
        fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }
}
