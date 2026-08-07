package com.rokid.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class EndpointProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val knownHost: String,
    val workspace: String,
    val sessionName: String,
) {
    val isReady: Boolean
        get() = id.isNotBlank() && name.isNotBlank() && host.isNotBlank() &&
            user.isNotBlank() && user.lowercase() !in FORBIDDEN_USERS && knownHost.isNotBlank()

    /**
     * Attach-only command (design 2026-08-07): ensures the tmux session
     * exists as a plain shell and attaches. The server helper
     * (`rokid-sessions switch`) owns launching Claude with the chosen
     * folder/session; this command no longer starts it.
     */
    val remoteCommand: String
        get() {
            val safeSession = sessionName.replace(Regex("[^A-Za-z0-9_.-]"), "-")
            val ensureSession = "(tmux has-session -t $safeSession 2>/dev/null || " +
                "tmux new-session -d -s $safeSession -c ${shellQuote(workspace)})"
            val configureStatus = TMUX_STATUS_OPTIONS.joinToString(" && ") { (option, value) ->
                "tmux set-option -t $safeSession $option ${shellQuote(value)}"
            }
            return "$ensureSession && $configureStatus && exec tmux attach-session -t $safeSession"
        }

    /**
     * Fallback used only when the session helper is unreachable: the
     * previous behavior — create the session running the fixed launcher in
     * the workspace. The sync watcher later reconciles the conversation
     * binding to the session Claude actually created.
     */
    val legacyRemoteCommand: String
        get() {
            val safeWorkspace = shellQuote(workspace)
            val safeSession = sessionName.replace(Regex("[^A-Za-z0-9_.-]"), "-")
            val launch = "tmux new-session -d -s $safeSession -c $safeWorkspace " +
                "$CLAUDE_LAUNCHER --effort max --dangerously-skip-permissions"
            val ensureSession = "(tmux has-session -t $safeSession 2>/dev/null || $launch)"
            val configureStatus = TMUX_STATUS_OPTIONS.joinToString(" && ") { (option, value) ->
                "tmux set-option -t $safeSession $option ${shellQuote(value)}"
            }
            return "$ensureSession && $configureStatus && exec tmux attach-session -t $safeSession"
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("user", user)
        .put("knownHost", knownHost)
        .put("workspace", workspace)
        .put("sessionName", sessionName)

    companion object {
        val FORBIDDEN_USERS = setOf("root", "ubuntu", "admin", "administrator", "ec2-user")
        private const val CLAUDE_LAUNCHER = "/home/rokid/bin/rokid-claude"
        private val TMUX_STATUS_OPTIONS = listOf(
            "status-left" to "[#{session_name}] ",
            "status-left-length" to "20",
            "status-right" to "%H:%M",
            "status-right-length" to "5",
            "window-status-format" to "#W#{?window_flags,#{window_flags}, }",
            "window-status-current-format" to "#W#{?window_flags,#{window_flags}, }",
            "window-status-separator" to " ",
        )

        fun fromJson(json: JSONObject): EndpointProfile = EndpointProfile(
            id = json.getString("id"),
            name = json.getString("name"),
            host = json.getString("host"),
            port = json.optInt("port", 22),
            user = json.optString("user", "rokid"),
            knownHost = json.getString("knownHost"),
            workspace = json.optString("workspace", "/srv/projects"),
            sessionName = json.optString("sessionName", "rokid-claude"),
        )

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

class EndpointStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val pendingProfile = context.filesDir.resolve(PENDING_PROFILE)

    fun loadAll(): List<EndpointProfile> {
        migrateLegacyProfile()
        val raw = prefs.getString(PROFILES, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val profile = EndpointProfile.fromJson(array.getJSONObject(index))
                    if (profile.isReady) add(profile)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun selectedId(): String? = prefs.getString(SELECTED_ID, null)

    fun select(profileId: String) {
        prefs.edit().putString(SELECTED_ID, profileId).apply()
    }

    fun upsert(profile: EndpointProfile) {
        val profiles = loadAll().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile else profiles.add(profile)
        save(profiles)
        select(profile.id)
    }

    fun delete(profileId: String) {
        val profiles = loadAll().filterNot { it.id == profileId }
        save(profiles)
        if (selectedId() == profileId) {
            prefs.edit().putString(SELECTED_ID, profiles.firstOrNull()?.id).apply()
        }
        DeviceKeyStore(context, profileId).delete()
    }

    fun importPendingProfile(): EndpointProfile? {
        if (!pendingProfile.exists()) return null
        return try {
            val json = JSONObject(pendingProfile.readText())
            when (json.optString("action", "upsert")) {
                "delete" -> {
                    val id = validateId(json.getString("id"))
                    delete(id)
                    null
                }
                "upsert" -> validateProfile(json).also(::upsert)
                else -> error("Unsupported profile action")
            }
        } finally {
            pendingProfile.delete()
        }
    }

    private fun validateProfile(json: JSONObject): EndpointProfile {
        val host = json.getString("host").trim()
        val port = json.optInt("port", 22)
        val id = validateId(json.getString("id"))
        val name = json.optString("name", host).trim().ifBlank { host }
        val user = json.optString("user", "rokid").trim().ifBlank { "rokid" }
        val knownHost = json.getString("knownHost").trim()
        val workspace = json.optString("workspace", "/srv/projects").trim().ifBlank { "/srv/projects" }
        val session = json.optString("sessionName", "rokid-claude").trim().ifBlank { "rokid-claude" }

        require(host.matches(Regex("[A-Za-z0-9._:%-]{1,253}"))) { "Invalid SSH host" }
        require(port in 1..65535) { "Invalid SSH port" }
        require(name.length in 1..48 && !name.containsControlCharacter()) { "Invalid profile name" }
        require(user.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid SSH user" }
        require(user.lowercase() !in EndpointProfile.FORBIDDEN_USERS) {
            "Administrative SSH users are forbidden"
        }
        require(knownHost.length in 20..8192 && !knownHost.contains('\n') && !knownHost.contains('\r')) {
            "Invalid known-host entry"
        }
        require(workspace.startsWith('/') && workspace.length <= 512 && !workspace.containsControlCharacter()) {
            "Invalid workspace"
        }
        require(session.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid tmux session" }

        return EndpointProfile(
            id = id,
            name = name,
            host = host,
            port = port,
            user = user,
            knownHost = knownHost,
            workspace = workspace,
            sessionName = session,
        )
    }

    private fun validateId(value: String): String = value.trim().also {
        require(it.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid profile id" }
    }

    private fun String.containsControlCharacter(): Boolean = any { it.code < 0x20 || it.code == 0x7f }

    private fun save(profiles: List<EndpointProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        prefs.edit().putString(PROFILES, array.toString()).apply()
    }

    private fun migrateLegacyProfile() {
        if (prefs.contains(MIGRATED)) return
        val legacy = context.getSharedPreferences("terminal_config", Context.MODE_PRIVATE)
        val host = legacy.getString("host", "").orEmpty()
        val knownHost = legacy.getString("known_host", "").orEmpty()
        if (host.isNotBlank() && knownHost.isNotBlank()) {
            val profile = EndpointProfile(
                id = "cloud",
                name = "Cloud",
                host = host,
                port = legacy.getInt("port", 22),
                user = legacy.getString("user", "rokid") ?: "rokid",
                knownHost = knownHost,
                workspace = legacy.getString("workspace", "/srv/projects") ?: "/srv/projects",
                sessionName = legacy.getString("session", "rokid-claude") ?: "rokid-claude",
            )
            save(listOf(profile))
            select(profile.id)
        }
        prefs.edit().putBoolean(MIGRATED, true).apply()
    }

    companion object {
        private const val PREFS = "endpoint_profiles"
        private const val PROFILES = "profiles_json"
        private const val SELECTED_ID = "selected_id"
        private const val MIGRATED = "legacy_migrated"
        private const val PENDING_PROFILE = "pending_profile.json"
    }
}
