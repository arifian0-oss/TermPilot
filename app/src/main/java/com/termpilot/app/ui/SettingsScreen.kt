package com.termpilot.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termpilot.app.AppContainer
import com.termpilot.app.data.LlmSettings
import com.termpilot.app.llm.LlmClient
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.settingsRepository
    val settings = repo.settings

    // Fields persist immediately on every change (write-through): the agent
    // and "Test connection" can never see different values.
    var baseUrl by mutableStateOf("")
        private set
    var model by mutableStateOf("")
        private set
    var timeoutText by mutableStateOf("60")
        private set
    var apiKey by mutableStateOf(repo.getApiKey() ?: "")
        private set
    var showKey by mutableStateOf(false)
    var testResult by mutableStateOf<String?>(null)
        private set
    var testing by mutableStateOf(false)
        private set
    private var seeded = false

    /** Seed fields once from DataStore; later emissions are our own writes. */
    fun seedFrom(s: LlmSettings) {
        if (seeded) return
        seeded = true
        baseUrl = s.baseUrl
        model = s.model
        timeoutText = s.commandTimeoutSeconds.toString()
    }

    fun onBaseUrlChange(v: String) {
        baseUrl = v
        viewModelScope.launch { repo.setBaseUrl(v) }
    }

    fun onModelChange(v: String) {
        model = v
        viewModelScope.launch { repo.setModel(v) }
    }

    fun onTimeoutChange(v: String) {
        timeoutText = v.filter(Char::isDigit).take(3)
        timeoutText.toIntOrNull()?.let { t ->
            viewModelScope.launch { repo.setCommandTimeoutSeconds(t) }
        }
    }

    fun onApiKeyChange(v: String) {
        apiKey = v
        repo.setApiKey(v) // SecureStore is synchronous
    }

    fun toggleShowKey() { showKey = !showKey }

    fun testConnection(baseUrl: String, model: String) = viewModelScope.launch {
        testing = true
        testResult = null
        val key = apiKey.trim()
        if (key.isEmpty()) {
            testResult = "Enter an API key first."
            testing = false
            return@launch
        }
        val result = container.llmClient.testConnection(LlmClient.Config(baseUrl, key, model))
        testResult = result.fold(
            onSuccess = { models ->
                val extra = if (models.isNotEmpty())
                    "\nModels visible: " + models.take(5).joinToString(", ") +
                        if (models.size > 5) " (+${models.size - 5} more)" else ""
                else ""
                "Connection OK (HTTP 200).$extra"
            },
            onFailure = { "Failed: ${it.message?.take(300)}" },
        )
        testing = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = VmFactory { SettingsViewModel(container) })
    val settings by vm.settings.collectAsState(initial = null)
    LaunchedEffect(settings) {
        (settings as LlmSettings?)?.let { vm.seedFrom(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LLM settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Agent mode calls an OpenAI-compatible /chat/completions endpoint. " +
                    "The API key is stored encrypted in the Android Keystore and never leaves the device except to your endpoint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = vm.baseUrl, onValueChange = vm::onBaseUrlChange,
                label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://api.openai.com/v1") },
            )
            OutlinedTextField(
                value = vm.model, onValueChange = vm::onModelChange,
                label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("gpt-4o-mini") },
            )
            OutlinedTextField(
                value = vm.apiKey, onValueChange = vm::onApiKeyChange,
                label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (vm.showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = vm::toggleShowKey) {
                        Text(if (vm.showKey) "Hide" else "Show")
                    }
                },
            )
            OutlinedTextField(
                value = vm.timeoutText,
                onValueChange = vm::onTimeoutChange,
                label = { Text("Default command timeout (seconds)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text(
                "Changes save automatically as you type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.testConnection(vm.baseUrl.trim(), vm.model.trim()) },
                    enabled = !vm.testing,
                ) { Text(if (vm.testing) "Testing…" else "Test connection") }
            }
            vm.testResult?.let { result ->
                val ok = result.startsWith("Connection OK")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(result, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
