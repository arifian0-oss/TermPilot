package com.termpilot.app.ssh

import android.app.Application
import com.termpilot.app.data.Host
import com.termpilot.app.data.KnownHostsStore
import com.termpilot.app.data.SecureStore
import com.termpilot.app.llm.AgentLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.userauth.password.Resource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Owns live SSH connections (one [SshSession] per host id), built on sshj.
 *
 * Threading notes:
 * - All blocking sshj calls run on [Dispatchers.IO].
 * - [HostKeyVerifier.verify] is invoked on sshj's transport reader thread. It
 *   must never touch the UI directly; the unknown-key prompt is bridged with
 *   [runBlocking], which is safe because the dialog lives on the main thread
 *   and never waits on the transport thread.
 * - Agent commands run on NEW exec channels ([SSHClient.startSession] per
 *   command — one sshj Session is single-use), multiplexed over the same
 *   connection, isolated from the interactive shell.
 */
class SshConnectionManager(
    private val app: Application,
    private val secureStore: SecureStore,
    private val knownHosts: KnownHostsStore,
) {

    sealed interface Credential {
        data class Password(val password: String) : Credential
        data class PrivateKey(val pem: String, val passphrase: String?) : Credential
        data object KeyboardInteractive : Credential
    }

    /** Presented when the server key is not in the TOFU store. */
    data class UnknownHostKey(
        val hostname: String,
        val port: Int,
        val keyType: String,
        val fingerprint: String,
    )

    data class KeyboardPrompt(val prompt: String, val echo: Boolean)

    private val sessions = ConcurrentHashMap<String, SshSession>()
    private val connectingClients = ConcurrentHashMap.newKeySet<SSHClient>()

    fun getSession(hostId: String): SshSession? = sessions[hostId]

    /**
     * Connects and returns a live session. Throws on any failure (network,
     * host-key rejection, auth failure).
     *
     * @param onUnknownHostKey show a TOFU dialog; return true to trust & store.
     * @param onKeyboardInteractive answer keyboard-interactive challenges;
     *   return null/empty to abort.
     */
    suspend fun connect(
        host: Host,
        credential: Credential,
        onUnknownHostKey: suspend (UnknownHostKey) -> Boolean,
        onKeyboardInteractive: suspend (name: String, instruction: String, prompts: List<KeyboardPrompt>) -> List<String>?,
        echoToTerminal: ((String) -> Unit)? = null,
    ): SshSession = withContext(Dispatchers.IO) {
        // JCE provider must be fixed up before any sshj crypto use (Android
        // ships its own "BC" which shadows the bundled BouncyCastle jar).
        CryptoProviders.ensure()

        // Reuse an existing live session.
        sessions[host.id]?.let { return@withContext it }

        val client = SSHClient()
        connectingClients.add(client)
        try {
            client.connectTimeout = 15_000
            client.timeout = 30_000
            client.addHostKeyVerifier(tofuVerifier(host, onUnknownHostKey))
            client.connect(host.hostname, host.port)

            when (credential) {
                is Credential.Password ->
                    client.authPassword(host.username, credential.password)
                is Credential.PrivateKey -> {
                    val finder = PasswordUtils.createOneOff((credential.passphrase ?: "").toCharArray())
                    val keyProvider = client.loadKeys(credential.pem, null, finder)
                    client.authPublickey(host.username, keyProvider)
                }
                Credential.KeyboardInteractive ->
                    client.auth(host.username, AuthKeyboardInteractive(
                        UiChallengeProvider(onKeyboardInteractive)))
            }
            if (!client.isAuthenticated) error("Authentication failed (server rejected all methods)")

            val session = client.startSession()
            try {
                session.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap<PTYMode, Int>())
                val shell = session.startShell()
                val sshSession = SshSession(host, client, session, shell, echoToTerminal)
                sessions[host.id] = sshSession
                sshSession
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        } finally {
            connectingClients.remove(client)
        }
    }

    /** Closes and drops the session for [hostId] (no-op if none). */
    suspend fun closeSession(hostId: String) {
        sessions.remove(hostId)?.close()
    }

    /** Closes everything: live sessions and in-flight connection attempts. */
    suspend fun closeAll() {
        connectingClients.toList().forEach { runCatching { it.disconnect() } }
        sessions.keys.toList().forEach { closeSession(it) }
    }

    // ---- host key verification (TOFU) ----

    private fun tofuVerifier(
        host: Host,
        onUnknownHostKey: suspend (UnknownHostKey) -> Boolean,
    ) = object : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val wire = wireEncode(key)
            val stored = knownHosts.find(host.hostname, host.port)
            if (stored != null) return stored.blob.contentEquals(wire)

            val keyType = sshKeyTypeName(key)
            val accept = runBlocking {
                onUnknownHostKey(
                    UnknownHostKey(
                        hostname = host.hostname,
                        port = host.port,
                        keyType = keyType,
                        fingerprint = KnownHostsStore.sha256Fingerprint(wire),
                    )
                )
            }
            if (accept) {
                knownHosts.addLine(
                    "${knownHosts.hostPart(host.hostname, host.port)} $keyType " +
                        android.util.Base64.encodeToString(wire, android.util.Base64.NO_WRAP)
                )
                return true
            }
            return false
        }

        // Bias key-exchange toward already-known key types; null is safe.
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String>? = null
    }

    // ---- keyboard-interactive ----

    private class UiChallengeProvider(
        private val onPrompts: suspend (name: String, instruction: String, prompts: List<KeyboardPrompt>) -> List<String>?,
    ) : ChallengeResponseProvider {
        private var name = ""
        private var instruction = ""

        override fun getSubmethods(): List<String> = emptyList()

        override fun init(resource: Resource<*>, name: String, instruction: String) {
            this.name = name
            this.instruction = instruction
        }

        override fun getResponse(prompt: String, echo: Boolean): CharArray {
            val answers = runBlocking {
                onPrompts(name, instruction, listOf(KeyboardPrompt(prompt, echo)))
            }
            return (answers?.firstOrNull() ?: "").toCharArray()
        }

        override fun shouldRetry(): Boolean = false
    }

    companion object {
        /** OpenSSH wire encoding of a public key (what ssh-keygen hashes). */
        fun wireEncode(key: PublicKey): ByteArray =
            Buffer.PlainBuffer().putPublicKey(key).compactData

        /** Human-readable key type, e.g. "ssh-ed25519". */
        fun sshKeyTypeName(key: PublicKey): String =
            runCatching {
                when (KeyType.fromKey(key)) {
                    KeyType.RSA -> "ssh-rsa"
                    KeyType.DSA -> "ssh-dss"
                    KeyType.ECDSA256 -> "ecdsa-sha2-nistp256"
                    KeyType.ECDSA384 -> "ecdsa-sha2-nistp384"
                    KeyType.ECDSA521 -> "ecdsa-sha2-nistp521"
                    KeyType.ED25519 -> "ssh-ed25519"
                    else -> null
                }
            }.getOrNull() ?: key.algorithm
    }
}

/**
 * A live SSH connection: an interactive shell channel (bridged to the
 * terminal view) plus [AgentLoop.Runner] for agent exec channels.
 */
class SshSession(
    val host: Host,
    private val client: SSHClient,
    private val shellSession: Session,
    private val shell: Session.Shell,
    private val echoToTerminal: ((String) -> Unit)?,
) : AgentLoop.Runner {

    /** Remote stdout of the interactive shell → feed to the terminal emulator. */
    val shellStdout: InputStream get() = shell.inputStream

    /** Remote stdin of the interactive shell ← terminal keystrokes. */
    val shellStdin: OutputStream get() = shell.outputStream

    fun changeWindowSize(cols: Int, rows: Int) {
        runCatching { shell.changeWindowDimensions(cols, rows, 0, 0) }
    }

    override fun onCommandEcho(text: String) {
        echoToTerminal?.invoke(text)
    }

    /**
     * Runs [command] on a NEW exec channel. Cancellable: closing the channel
     * unblocks the stream readers, so the STOP button takes effect promptly.
     */
    override suspend fun runCommand(command: String, timeoutSeconds: Int): AgentLoop.CommandResult =
        withContext(Dispatchers.IO) {
            val execSession = client.startSession()
            try {
                val cmd = execSession.exec(command)
                try {
                    val out = ByteArrayOutputStream()
                    val err = ByteArrayOutputStream()
                    val tOut = thread(isDaemon = true, name = "ssh-exec-stdout") {
                        runCatching { cmd.inputStream.copyTo(out) }
                    }
                    val tErr = thread(isDaemon = true, name = "ssh-exec-stderr") {
                        runCatching { cmd.errorStream.copyTo(err) }
                    }
                    var timedOut = false
                    try {
                        withTimeout(timeoutSeconds * 1000L) {
                            while (tOut.isAlive || tErr.isAlive) {
                                ensureActive() // STOP button → CancellationException
                                delay(50)
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        timedOut = true
                    }
                    runCatching { cmd.close() }
                    tOut.join(2000)
                    tErr.join(2000)
                    // Per sshj docs, read exit status after close().
                    val exit = if (timedOut) null else runCatching { cmd.exitStatus }.getOrNull()
                    AgentLoop.CommandResult(
                        command = command,
                        stdout = out.toString(Charsets.UTF_8),
                        stderr = err.toString(Charsets.UTF_8),
                        exitCode = exit,
                        timedOut = timedOut,
                    )
                } finally {
                    runCatching { cmd.close() }
                }
            } finally {
                runCatching { execSession.close() }
            }
        }

    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { shell.close() }
        runCatching { shellSession.close() }
        runCatching { client.disconnect() }
    }
}
