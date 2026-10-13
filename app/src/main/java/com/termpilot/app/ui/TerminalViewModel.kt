package com.termpilot.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.termpilot.app.AppContainer
import com.termpilot.app.data.AuthType
import com.termpilot.app.data.Host
import com.termpilot.app.llm.AgentLoop
import com.termpilot.app.llm.LlmClient
import com.termpilot.app.ssh.SshConnectionManager
import com.termpilot.app.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val TAG = "TerminalVM"

/**
 * Owns the SSH connection, the patched Termux [TerminalSession], and the
 * agent loop for one host tab.
 *
 * Connection flow: [connect] resolves credentials (prompting for a password
 * when none is stored), then delegates to [SshConnectionManager]. Host-key
 * (TOFU) and keyboard-interactive prompts are surfaced as [prompt] state;
 * the SSH transport thread blocks on them via runBlocking inside the
 * manager, which is safe because the dialogs live on the main thread.
 */
class TerminalViewModel(
    private val container: AppContainer,
    private val hostId: String,
) : ViewModel() {

    sealed interface ConnState {
        data object Connecting : ConnState
        data class Connected(val label: String) : ConnState
        data class Failed(val message: String) : ConnState
        data object Disconnected : ConnState
    }

    /** Modal credential/trust prompts, shown one at a time. */
    sealed interface Prompt {
        data object Password : Prompt
        data class UnknownKey(val info: SshConnectionManager.UnknownHostKey) : Prompt
        data class KeyboardInteractive(
            val name: String,
            val instruction: String,
            val prompts: List<SshConnectionManager.KeyboardPrompt>,
        ) : Prompt
    }

    enum class AgentLineKind { GOAL, STATUS, ASSISTANT, CMD, OUTPUT, DONE, ERROR }
    data class AgentLine(val kind: AgentLineKind, val text: String)

    private val _connState = MutableStateFlow<ConnState>(ConnState.Connecting)
    val connState: StateFlow<ConnState> = _connState

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt
    private var promptResult: CompletableDeferred<Any?>? = null

    var hostLabel by mutableStateOf("")
        private set
    var terminalTitle by mutableStateOf("")
        private set

    // Extra-keys modifier state, read by TerminalViewClient on the main thread.
    var ctrlHeld by mutableStateOf(false)
    var altHeld by mutableStateOf(false)
    var shiftHeld by mutableStateOf(false)

    // ---- agent state (per-session toggle, default OFF) ----
    var agentEnabled by mutableStateOf(false)
    var agentRunning by mutableStateOf(false)
        private set
    val agentTranscript = mutableStateListOf<AgentLine>()
    private var agentJob: Job? = null

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {
            terminalTitle = changedSession.title ?: ""
        }
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = container.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val cm = container.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(container.app)?.toString()
            if (!text.isNullOrEmpty()) {
                keystrokeExecutor.execute {
                    runCatching { sshSession?.shellStdin?.write(text.toByteArray()) }
                }
            }
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
        }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }
    }

    /** The patched Termux session (external-I/O). Created on the main thread. */
    val terminalSession: TerminalSession = TerminalSession(sessionClient).apply {
        // Keystrokes arrive on the main thread; never block it on the socket.
        // A single-thread executor preserves keystroke order.
        setOutputSink { data, off, len ->
            keystrokeExecutor.execute {
                runCatching { sshSession?.shellStdin?.write(data, off, len) }
            }
        }
    }

    val viewClient: TerminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) { showKeyboard() }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = ctrlHeld
        override fun readAltKey(): Boolean = altHeld
        override fun readShiftKey(): Boolean = shiftHeld
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() { updateSshWindowSize() }
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }
    }

    private var terminalView: TerminalView? = null
    private var sshSession: SshSession? = null
    private var connectJob: Job? = null
    private var pumpThread: Thread? = null
    private var userClosed = false
    private val keystrokeExecutor = Executors.newSingleThreadExecutor()

    init {
        connect()
    }

    fun onViewCreated(view: TerminalView) {
        terminalView = view
        showKeyboard()
    }

    // ---- connection ----

    fun connect() {
        if (connectJob?.isActive == true) return
        userClosed = false
        connectJob = viewModelScope.launch {
            _connState.value = ConnState.Connecting
            try {
                val host = container.hostRepository.hosts.first().firstOrNull { it.id == hostId }
                    ?: error("Host not found")
                hostLabel = host.label
                val credential = resolveCredential(host)
                val session = container.sshManager.connect(
                    host = host,
                    credential = credential,
                    onUnknownHostKey = { info ->
                        askPrompt<Any?>(Prompt.UnknownKey(info)) as Boolean
                    },
                    onKeyboardInteractive = { name, instruction, prompts ->
                        @Suppress("UNCHECKED_CAST")
                        askPrompt<Any?>(Prompt.KeyboardInteractive(name, instruction, prompts)) as List<String>?
                    },
                    echoToTerminal = { text -> echoToTerminal(text) },
                )
                sshSession = session
                ensureActive() // user may have left during the blocking connect
                _connState.value = ConnState.Connected(host.label)
                startPumping()
                startKeepAlive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                _connState.value = ConnState.Failed(e.message ?: e.toString())
            }
        }
    }

    private suspend fun resolveCredential(host: Host): SshConnectionManager.Credential {
        val ss = container.secureStore
        return when (host.authType) {
            AuthType.PASSWORD -> {
                val stored = ss.get(host.passwordKey())
                if (!stored.isNullOrEmpty()) {
                    SshConnectionManager.Credential.Password(stored)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val result = askPrompt<Any?>(Prompt.Password) as Pair<String, Boolean>?
                        ?: throw CancellationException("password prompt cancelled")
                    val (password, remember) = result
                    if (remember) ss.put(host.passwordKey(), password)
                    SshConnectionManager.Credential.Password(password)
                }
            }
            AuthType.PRIVATE_KEY -> {
                val pem = ss.get(host.privateKeyKey())
                    ?: error("No private key stored for this host — edit the host and import one")
                SshConnectionManager.Credential.PrivateKey(pem, ss.get(host.keyPassphraseKey()))
            }
            AuthType.KEYBOARD_INTERACTIVE ->
                SshConnectionManager.Credential.KeyboardInteractive
        }
    }

    /** Shows [p] and suspends until the dialog resolves it. */
    private suspend fun <T> askPrompt(p: Prompt): T {
        val d = CompletableDeferred<Any?>()
        promptResult = d
        _prompt.value = p
        @Suppress("UNCHECKED_CAST")
        return try {
            d.await() as T
        } finally {
            _prompt.value = null
            promptResult = null
        }
    }

    /** Called by dialogs. */
    fun resolvePrompt(result: Any?) {
        promptResult?.complete(result)
    }

    // ---- terminal I/O ----

    private fun startPumping() {
        pumpThread = thread(isDaemon = true, name = "ssh-shell-reader") {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = sshSession?.shellStdout?.read(buf) ?: break
                    if (n < 0) break
                    terminalSession.feedInput(buf, 0, n)
                }
            } catch (e: Exception) {
                Log.i(TAG, "shell reader ended: ${e.message}")
            }
            viewModelScope.launch {
                if (!userClosed) {
                    // Drop the dead session so a later connect() creates a fresh one.
                    container.sshManager.closeSession(hostId)
                    _connState.value = ConnState.Disconnected
                }
            }
        }
    }

    private fun echoToTerminal(text: String) {
        val bytes = "\r\n$text\r\n".toByteArray(Charsets.UTF_8)
        terminalSession.feedInput(bytes, 0, bytes.size)
    }

    private fun updateSshWindowSize() {
        val emu = terminalSession.getEmulator() ?: return
        sshSession?.changeWindowSize(emu.mColumns, emu.mRows)
    }

    /**
     * Harmless traffic every 45 s while connected: keeps NAT/firewall mappings
     * alive during long idle stretches (e.g. the agent "thinking"), so the
     * server or middleboxes don't silently drop the TCP connection.
     */
    private var keepAliveJob: Job? = null

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(45_000)
                runCatching { updateSshWindowSize() }
            }
        }
    }

    /** Show the soft keyboard, retrying once if the implicit request is ignored
     * (happens when the window isn't focused yet during transitions). */
    fun showKeyboard() {
        val view = terminalView ?: return
        view.post {
            if (!view.hasFocus()) view.requestFocus()
            val imm = container.app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (!imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)) {
                view.postDelayed({
                    if (!imm.isActive(view)) {
                        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                    }
                }, 300)
            }
        }
    }

    /** Manual keyboard toggle (extra-keys row): deterministic control when
     * auto-show misbehaves on a device/keyboard combo. */
    fun toggleKeyboard() {
        val view = terminalView ?: return
        val imm = container.app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imm.isActive(view)) {
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        } else {
            showKeyboard()
        }
    }

    /** Synthesize a hardware key press (extra-keys row). */
    fun sendKey(keyCode: Int) {
        val view = terminalView ?: return
        view.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        view.onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** Send a printable character (extra-keys row). */
    fun sendCodePoint(codePoint: Int) {
        terminalView?.inputCodePoint(codePoint, false, false)
    }

    // ---- agent ----

    fun startAgent(goal: String) {
        if (agentRunning) return
        val session = sshSession ?: return
        agentTranscript.add(AgentLine(AgentLineKind.GOAL, goal))
        agentRunning = true
        agentJob = viewModelScope.launch {
            try {
                val settings = container.settingsRepository.settings.first()
                val apiKey = container.settingsRepository.getApiKey()
                if (apiKey.isNullOrBlank()) {
                    agentTranscript.add(AgentLine(
                        AgentLineKind.ERROR,
                        "No LLM API key set — open Settings and add one first."))
                    return@launch
                }
                val config = LlmClient.Config(settings.baseUrl, apiKey, settings.model)
                container.agentLoop.run(
                    goal = goal,
                    config = config,
                    runner = session,
                    defaultTimeoutSeconds = settings.commandTimeoutSeconds,
                    onEvent = { handleAgentEvent(it) },
                )
            } finally {
                agentRunning = false
            }
        }
    }

    fun stopAgent() {
        agentJob?.cancel()
    }

    private fun handleAgentEvent(e: AgentLoop.Event) {
        // AgentLoop invokes this off the main thread; marshal to it.
        viewModelScope.launch {
            when (e) {
                is AgentLoop.Event.Status ->
                    agentTranscript.add(AgentLine(AgentLineKind.STATUS, e.text))
                is AgentLoop.Event.AssistantText ->
                    agentTranscript.add(AgentLine(AgentLineKind.ASSISTANT, e.text))
                is AgentLoop.Event.CommandStart ->
                    agentTranscript.add(AgentLine(AgentLineKind.CMD, "$ ${e.command}"))
                is AgentLoop.Event.CommandEnd -> {
                    val r = e.result
                    val summary = buildString {
                        append("exit=")
                        append(r.exitCode?.toString() ?: if (r.timedOut) "timeout" else "?")
                        if (r.stdout.isNotBlank()) append("\n").append(r.stdout.take(2000))
                        if (r.stderr.isNotBlank()) append("\n[stderr]\n").append(r.stderr.take(1000))
                    }
                    agentTranscript.add(AgentLine(AgentLineKind.OUTPUT, summary))
                }
                is AgentLoop.Event.Finished ->
                    agentTranscript.add(AgentLine(AgentLineKind.DONE, e.summary))
                is AgentLoop.Event.Failed ->
                    agentTranscript.add(AgentLine(AgentLineKind.ERROR, e.error))
                AgentLoop.Event.Cancelled ->
                    agentTranscript.add(AgentLine(AgentLineKind.STATUS, "Agent stopped by user."))
            }
        }
    }

    // ---- lifecycle ----

    fun disconnect() {
        userClosed = true
        agentJob?.cancel()
        keepAliveJob?.cancel()
        connectJob?.cancel()
        viewModelScope.launch {
            container.sshManager.closeSession(hostId)
            _connState.value = ConnState.Disconnected
        }
    }

    override fun onCleared() {
        userClosed = true
        agentJob?.cancel()
        keepAliveJob?.cancel()
        connectJob?.cancel()
        pumpThread?.interrupt()
        keystrokeExecutor.shutdownNow()
        terminalSession.finishIfRunning()
        // The back button calls disconnect(), but onCleared must also release
        // the connection (e.g. system back). Blocking close on a helper thread.
        thread(isDaemon = true, name = "ssh-close") {
            runBlocking { container.sshManager.closeSession(hostId) }
        }
    }
}
