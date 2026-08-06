package com.rokid.terminal

import android.content.Context
import org.json.JSONObject

/**
 * ASR connection configuration, separate from the terminal EndpointProfile.
 *
 * The glasses reach the ASR service through a dedicated `asr-fwd` SSH account
 * whose authorized_keys permit only one forward target (127.0.0.1:8765). The
 * terminal SSH session (rokid user) cannot forward at all, so ASR needs its
 * own SSH connection and identity, but on the same server as the endpoint.
 */
data class AsrProfile(
    val id: String,
    val host: String,
    val port: Int,
    val user: String,
    val knownHost: String,
    val localForwardPort: Int = 18765,
    val remoteForwardTarget: String = "127.0.0.1",
    val remoteForwardPort: Int = 8765,
) {
    val isReady: Boolean
        get() = id.isNotBlank() && host.isNotBlank() && user.isNotBlank() && knownHost.isNotBlank()

    /** The HTTP base URL of the local forward endpoint. */
    val baseUrl: String
        get() = "http://127.0.0.1:$localForwardPort"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("host", host)
        .put("port", port)
        .put("user", user)
        .put("knownHost", knownHost)
        .put("localForwardPort", localForwardPort)
        .put("remoteForwardTarget", remoteForwardTarget)
        .put("remoteForwardPort", remoteForwardPort)

    companion object {
        const val DEFAULT_USER = "asr-fwd"

        /** Same host as the terminal endpoint; user is the restricted account. */
        fun fromEndpoint(endpoint: EndpointProfile): AsrProfile = AsrProfile(
            id = "asr-" + endpoint.id,
            host = endpoint.host,
            port = endpoint.port,
            user = DEFAULT_USER,
            knownHost = endpoint.knownHost,
        )

        fun fromJson(json: JSONObject): AsrProfile = AsrProfile(
            id = json.getString("id"),
            host = json.getString("host"),
            port = json.optInt("port", 22),
            user = json.optString("user", DEFAULT_USER),
            knownHost = json.getString("knownHost"),
            localForwardPort = json.optInt("localForwardPort", 18765),
            remoteForwardTarget = json.optString("remoteForwardTarget", "127.0.0.1"),
            remoteForwardPort = json.optInt("remoteForwardPort", 8765),
        )
    }
}
