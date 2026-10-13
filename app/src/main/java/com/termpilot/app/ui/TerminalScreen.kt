package com.termpilot.app.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.view.TerminalView
import com.termpilot.app.AppContainer
import com.termpilot.app.ssh.SshConnectionManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(container: AppContainer, hostId: String, onBack: () -> Unit) {
    val vm: TerminalViewModel = viewModel(factory = VmFactory { TerminalViewModel(container, hostId) })
    val connState by vm.connState.collectAsState()
    val prompt by vm.prompt.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.hostLabel.ifEmpty { "Terminal" }, style = MaterialTheme.typography.titleMedium)
                        if (vm.terminalTitle.isNotEmpty()) {
                            Text(vm.terminalTitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.disconnect(); onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Disconnect and back")
                    }
                },
                actions = {
                    if (connState is TerminalViewModel.ConnState.Connected) {
                        IconButton(onClick = { vm.agentEnabled = !vm.agentEnabled }) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = "Toggle agent mode",
                                tint = if (vm.agentEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = connState) {
                TerminalViewModel.ConnState.Connecting ->
                    CenterMessage("Connecting…")
                is TerminalViewModel.ConnState.Failed ->
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Connection failed", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(s.message, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::connect) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Retry")
                            }
                            OutlinedButton(onClick = onBack) { Text("Back") }
                        }
                    }
                TerminalViewModel.ConnState.Disconnected ->
                    CenterMessage("Disconnected.")
                is TerminalViewModel.ConnState.Connected ->
                    TerminalContent(vm)
            }
        }
    }

    // ---- modal prompts (password / TOFU / keyboard-interactive) ----
    when (val p = prompt) {
        is TerminalViewModel.Prompt.Password -> PasswordDialog(
            onOk = { password, remember -> vm.resolvePrompt(Pair(password, remember)) },
            onCancel = { vm.resolvePrompt(null) },
        )
        is TerminalViewModel.Prompt.UnknownKey -> UnknownKeyDialog(
            info = p.info,
            onAccept = { vm.resolvePrompt(true) },
            onReject = { vm.resolvePrompt(false) },
        )
        is TerminalViewModel.Prompt.KeyboardInteractive -> KeyboardInteractiveDialog(
            name = p.name, instruction = p.instruction, prompts = p.prompts,
            onOk = { answers -> vm.resolvePrompt(answers) },
            onCancel = { vm.resolvePrompt(null) },
        )
        null -> {}
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TerminalContent(vm: TerminalViewModel) {
    Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    setTerminalViewClient(vm.viewClient)
                    // REQUIRED: initializes the renderer before attachSession().
                    setTextSize(30)
                    isFocusableInTouchMode = true
                    attachSession(vm.terminalSession)
                    vm.onViewCreated(this)
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        if (vm.agentEnabled) {
            AgentPanel(vm, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 340.dp))
        }
        ExtraKeysRow(vm)
    }
}

// ---- extra keys ----

@Composable
private fun ExtraKeysRow(vm: TerminalViewModel) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        KeyButton("ESC") { vm.sendKey(KeyEvent.KEYCODE_ESCAPE) }
        KeyButton("TAB") { vm.sendKey(KeyEvent.KEYCODE_TAB) }
        ToggleKeyButton("CTRL", vm.ctrlHeld) { vm.ctrlHeld = it }
        ToggleKeyButton("ALT", vm.altHeld) { vm.altHeld = it }
        KeyButton("←") { vm.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        KeyButton("↑") { vm.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        KeyButton("↓") { vm.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        KeyButton("→") { vm.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        KeyButton("HOME") { vm.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        KeyButton("END") { vm.sendKey(KeyEvent.KEYCODE_MOVE_END) }
        KeyButton("PGUP") { vm.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        KeyButton("PGDN") { vm.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        KeyButton("|") { vm.sendCodePoint('|'.code) }
        KeyButton("-") { vm.sendCodePoint('-'.code) }
        KeyButton("~") { vm.sendCodePoint('~'.code) }
        KeyButton("/") { vm.sendCodePoint('/'.code) }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ToggleKeyButton(label: String, held: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = if (held) ButtonDefaults.textButtonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) else ButtonDefaults.textButtonColors()
    TextButton(
        onClick = { onToggle(!held) },
        colors = colors,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (held) FontWeight.Bold else FontWeight.Normal)
    }
}

// ---- agent panel ----

@Composable
private fun AgentPanel(vm: TerminalViewModel, modifier: Modifier = Modifier) {
    var goal by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll transcript to the bottom.
    LaunchedEffect(vm.agentTranscript.size) {
        if (vm.agentTranscript.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(vm.agentTranscript.size - 1) }
        }
    }

    Column(
        modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SmartToy, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Agent mode", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (vm.agentRunning) {
                Button(
                    onClick = vm::stopAgent,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("STOP", fontSize = 12.sp)
                }
            }
        }
        Text(
            "Describe a goal; the agent runs commands on its own. Max 25 steps per goal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = goal, onValueChange = { goal = it },
                placeholder = { Text("e.g. find large log files and compress them") },
                singleLine = true, modifier = Modifier.weight(1f),
                enabled = !vm.agentRunning,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { vm.startAgent(goal.trim()); goal = "" },
                enabled = !vm.agentRunning && goal.isNotBlank(),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Run agent",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(vm.agentTranscript) { line ->
                AgentLineView(line)
            }
        }
    }
}

@Composable
private fun AgentLineView(line: TerminalViewModel.AgentLine) {
    val (prefix, color) = when (line.kind) {
        TerminalViewModel.AgentLineKind.GOAL -> "🎯 " to MaterialTheme.colorScheme.primary
        TerminalViewModel.AgentLineKind.STATUS -> "… " to MaterialTheme.colorScheme.onSurfaceVariant
        TerminalViewModel.AgentLineKind.ASSISTANT -> "🤖 " to MaterialTheme.colorScheme.onSurface
        TerminalViewModel.AgentLineKind.CMD -> "" to Color(0xFF3FB950)
        TerminalViewModel.AgentLineKind.OUTPUT -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        TerminalViewModel.AgentLineKind.DONE -> "✅ " to MaterialTheme.colorScheme.primary
        TerminalViewModel.AgentLineKind.ERROR -> "❌ " to MaterialTheme.colorScheme.error
    }
    SelectionContainer {
        Text(
            prefix + line.text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (line.kind == TerminalViewModel.AgentLineKind.CMD) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ---- dialogs ----

@Composable
private fun PasswordDialog(onOk: (String, Boolean) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { show = !show }) {
                            Text(if (show) "Hide" else "Show", fontSize = 12.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.toggleable(value = remember, onValueChange = { remember = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = remember, onCheckedChange = null)
                    Text("Remember (stored encrypted)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = { onOk(password, remember) }) {
                Text("Connect")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun UnknownKeyDialog(
    info: SshConnectionManager.UnknownHostKey,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Unknown host key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This server's key is not in your known-hosts. " +
                        "Verify the fingerprint out-of-band before accepting.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Column {
                        Text("${info.hostname}:${info.port}",
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Text(info.keyType, fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(info.fingerprint, fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept & save") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Reject") } },
    )
}

@Composable
private fun KeyboardInteractiveDialog(
    name: String,
    instruction: String,
    prompts: List<SshConnectionManager.KeyboardPrompt>,
    onOk: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val answers = remember { mutableStateListOf(*Array(prompts.size) { "" }) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(name.ifEmpty { "Authentication" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (instruction.isNotEmpty()) {
                    Text(instruction, style = MaterialTheme.typography.bodySmall)
                }
                prompts.forEachIndexed { i, p ->
                    OutlinedTextField(
                        value = answers[i], onValueChange = { answers[i] = it },
                        label = { Text(p.prompt.trim()) }, singleLine = true,
                        visualTransformation = if (p.echo) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(answers.toList()) }) { Text("Submit") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
