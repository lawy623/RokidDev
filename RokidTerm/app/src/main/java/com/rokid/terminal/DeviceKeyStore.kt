package com.rokid.terminal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceKeyStore(
    private val context: Context,
    profileId: String,
) {
    data class Identity(val privateKey: ByteArray, val publicKey: String)

    private val safeProfileId = profileId.replace(Regex("[^A-Za-z0-9_.-]"), "-")
    private val keyFile = context.filesDir.resolve("ssh_identity_$safeProfileId.enc")
    private val publicKeyFile = context.filesDir.resolve("ssh_public_key_$safeProfileId.txt")
    private val keyAlias = "rokid_terminal_ssh_wrap_$safeProfileId"

    fun getOrCreate(): Identity {
        if (!keyFile.exists() || !publicKeyFile.exists()) createIdentity()
        return Identity(
            privateKey = decrypt(keyFile.readBytes()),
            publicKey = publicKeyFile.readText().trim(),
        )
    }

    fun delete() {
        keyFile.delete()
        publicKeyFile.delete()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
    }

    private fun createIdentity() {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        val privateOut = ByteArrayOutputStream()
        val publicOut = ByteArrayOutputStream()
        try {
            pair.writePrivateKey(privateOut)
            pair.writePublicKey(publicOut, "rokid-terminal-device")
            keyFile.writeBytes(encrypt(privateOut.toByteArray()))
            publicKeyFile.writeText(publicOut.toString(Charsets.UTF_8.name()).trim() + "\n")
        } finally {
            pair.dispose()
            privateOut.reset()
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(plain)
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val ivLength = payload.first().toInt() and 0xff
        val iv = payload.copyOfRange(1, 1 + ivLength)
        val encrypted = payload.copyOfRange(1 + ivLength, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
