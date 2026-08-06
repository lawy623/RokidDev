package com.rokid.terminal

import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest

/**
 * Rejects any server host key that is not the exact pinned ED25519 key.
 *
 * The repository is constructed from the pinned `knownHost` line carried by an
 * endpoint or ASR profile, so both the terminal session (rokid user) and the
 * ASR session (asr-fwd user) verify the same server key with no
 * trust-on-first-use fallback.
 */
class PinnedHostKeyRepository(
    host: String,
    port: Int,
    knownHost: String,
) : HostKeyRepository {
    private val expectedHost: String
    private val expectedKey: ByteArray

    init {
        val parts = knownHost.trim().split(Regex("\\s+"), limit = 3)
        require(parts.size >= 3 && parts[1] == "ssh-ed25519") { "Pinned host key must be ED25519" }
        expectedHost = if (port == 22) host else "[${host}]:${port}"
        require(parts[0] == expectedHost) { "Pinned host does not match endpoint" }
        expectedKey = Base64.decode(parts[2], Base64.DEFAULT)
    }

    override fun check(host: String, key: ByteArray): Int {
        if (host != expectedHost) return HostKeyRepository.NOT_INCLUDED
        return if (MessageDigest.isEqual(expectedKey, key)) {
            HostKeyRepository.OK
        } else {
            HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "RokidTerminal pinned ED25519 key"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
