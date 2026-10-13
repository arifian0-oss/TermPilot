package com.termpilot.app.data

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.MessageDigest

/**
 * Trust-on-first-use (TOFU) host-key store in app-private storage
 * (`filesDir/known_hosts`, OpenSSH line format: `<host> <keytype> <base64>`).
 *
 * Keys are compared by their OpenSSH wire-format bytes (computed by the SSH
 * layer with sshj's encoder, so they match `ssh-keygen -lf` exactly).
 * We never silently trust unknown keys: [find] returns null for a host we
 * have not seen, and the UI must show the SHA-256 fingerprint and get
 * explicit user approval before [addLine] is called.
 */
class KnownHostsStore(context: Context) {

    private val file = File(context.filesDir, "known_hosts")

    data class StoredKey(val type: String, val blob: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is StoredKey && type == other.type && blob.contentEquals(other.blob)
        override fun hashCode(): Int = 31 * type.hashCode() + blob.contentHashCode()
    }

    /** OpenSSH host field: `host`, or `[host]:port` for non-standard ports. */
    fun hostPart(hostname: String, port: Int): String =
        if (port == 22) hostname else "[$hostname]:$port"

    /** Returns the stored key for [hostname]/[port], or null if never seen. */
    fun find(hostname: String, port: Int): StoredKey? {
        if (!file.exists()) return null
        val prefix = hostPart(hostname, port) + " "
        for (line in file.readLines()) {
            val l = line.trim()
            if (l.startsWith(prefix)) {
                val parts = l.split(" ")
                if (parts.size >= 3) {
                    return StoredKey(parts[1], Base64.decode(parts[2], Base64.DEFAULT))
                }
            }
        }
        return null
    }

    /**
     * Adds a full known_hosts line (`<hostpart> <keytype> <base64>`),
     * replacing any previous entry for the same host field.
     */
    fun addLine(line: String) {
        val host = line.substringBefore(" ").trim()
        val lines = if (file.exists()) {
            file.readLines().filterNot { it.substringBefore(" ").trim() == host }
        } else emptyList()
        file.writeText((lines + line.trim()).joinToString("\n") + "\n")
    }

    companion object {
        /** `SHA256:<base64>` exactly like `ssh-keygen -lf` (over the wire blob). */
        fun sha256Fingerprint(wireBlob: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(wireBlob)
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
        }
    }
}
