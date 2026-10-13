package com.termpilot.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termpilot.app.AppContainer
import com.termpilot.app.data.AuthType
import com.termpilot.app.data.Host
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HostsViewModel(private val container: AppContainer) : ViewModel() {
    val hosts = container.hostRepository.hosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun save(
        existing: Host?,
        label: String, hostname: String, port: Int, username: String,
        authType: AuthType, pemText: String?, passphrase: String?,
    ) = viewModelScope.launch {
        val host = (existing?.copy(
            label = label, hostname = hostname, port = port,
            username = username, authType = authType,
        ) ?: Host(label = label, hostname = hostname, port = port, username = username, authType = authType))
        container.hostRepository.upsert(host)
        val ss = container.secureStore
        if (authType == AuthType.PRIVATE_KEY) {
            if (!pemText.isNullOrBlank()) ss.put(host.privateKeyKey(), pemText.trim())
            if (!passphrase.isNullOrBlank()) ss.put(host.keyPassphraseKey(), passphrase) else ss.remove(host.keyPassphraseKey())
        } else {
            ss.remove(host.privateKeyKey())
            ss.remove(host.keyPassphraseKey())
        }
    }

    fun delete(host: Host) = viewModelScope.launch {
        container.hostRepository.delete(host, container.secureStore)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    container: AppContainer,
    onOpenTerminal: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: HostsViewModel = viewModel(factory = VmFactory { HostsViewModel(container) })
    val hosts by vm.hosts.collectAsState()
    var editing by remember { mutableStateOf<Host?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Host?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TermPilot") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "LLM settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add host")
            }
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No hosts yet — tap + to add one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(hosts, key = { it.id }) { host ->
                    HostRow(
                        host = host,
                        onConnect = { onOpenTerminal(host.id) },
                        onEdit = { editing = host },
                        onDelete = { deleting = host },
                    )
                }
            }
        }
    }

    if (adding || editing != null) {
        HostEditorDialog(
            container = container,
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { l, h, p, u, a, pem, pp ->
                vm.save(editing, l, h, p, u, a, pem, pp)
                adding = false; editing = null
            },
        )
    }
    deleting?.let { host ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete host?") },
            text = { Text("“${host.label}” and its stored credentials will be removed.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(host); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HostRow(
    host: Host,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onConnect),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(host.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${host.username}@${host.hostname}:${host.port} · ${host.authType.name.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onConnect) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Connect", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditorDialog(
    container: AppContainer,
    existing: Host?,
    onDismiss: () -> Unit,
    onSave: (label: String, hostname: String, port: Int, username: String, authType: AuthType, pem: String?, passphrase: String?) -> Unit,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var hostname by remember { mutableStateOf(existing?.hostname ?: "") }
    var portText by remember { mutableStateOf(existing?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var authType by remember { mutableStateOf(existing?.authType ?: AuthType.PASSWORD) }
    var pemText by remember {
        mutableStateOf(existing?.let { container.secureStore.get(it.privateKeyKey()) } ?: "")
    }
    var passphrase by remember {
        mutableStateOf(existing?.let { container.secureStore.get(it.keyPassphraseKey()) } ?: "")
    }
    var authExpanded by remember { mutableStateOf(false) }

    val pemPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()
            }.onSuccess { text -> pemText = text?.trim().orEmpty() }
        }
    }

    val valid = label.isNotBlank() && hostname.isNotBlank() && username.isNotBlank() &&
        portText.toIntOrNull() in 1..65535 &&
        (authType != AuthType.PRIVATE_KEY || pemText.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add host" else "Edit host") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(hostname, { hostname = it }, label = { Text("Hostname or IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        portText, { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(2f))
                }
                ExposedDropdownMenuBox(expanded = authExpanded, onExpandedChange = { authExpanded = it }) {
                    OutlinedTextField(
                        readOnly = true,
                        value = authType.name.lowercase().replace('_', ' '),
                        onValueChange = {},
                        label = { Text("Auth method") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = authExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = authExpanded, onDismissRequest = { authExpanded = false }) {
                        AuthType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.name.lowercase().replace('_', ' ')) },
                                onClick = { authType = t; authExpanded = false },
                            )
                        }
                    }
                }
                if (authType == AuthType.PRIVATE_KEY) {
                    OutlinedButton(onClick = { pemPicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (pemText.isBlank()) "Import private key file…" else "Re-import private key file…")
                    }
                    OutlinedTextField(
                        pemText, { pemText = it }, label = { Text("…or paste PEM here") },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        passphrase, { passphrase = it }, label = { Text("Key passphrase (if encrypted)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Text(
                        "Private keys are stored encrypted via Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(label.trim(), hostname.trim(), portText.toInt(), username.trim(),
                        authType, pemText.ifBlank { null }, passphrase.ifBlank { null })
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Minimal ViewModel factory without extra deps. */
class VmFactory<T : ViewModel>(private val create: () -> T) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <M : ViewModel> create(modelClass: Class<M>): M = create() as M
}
