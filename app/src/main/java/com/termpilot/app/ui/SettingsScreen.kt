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
import com.termpilot.app.llm.LlmClient
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.settingsRepository
    val settings = repo.settings

    var apiKey by mutableStateOf(repo.getApiKey() ?: "")
    var showKey by mutableStateOf(false)
    var testResult by mutableStateOf<String?>(null)
        private set
    var testing by mutableStateOf(false)
        private set

    fun toggleShowKey() { showKey = !showKey }
    fun saveApiKey() = viewModelScope.launch { repo.setApiKey(apiKey) }
    fun setBaseUrl(v: String) = viewModelScope.launch { repo.setBaseUrl(v) }
    fun setModel(v: String) = viewModelScope.launch { repo.setModel(v) }
    fun setTimeout(v: Int) = viewModelScope.launch { repo.setCommandTimeoutSeconds(v) }

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
    var baseUrl by remember(settings) { mutableStateOf(settings?.baseUrl ?: "") }
    var model by remember(settings) { mutableStateOf(settings?.model ?: "") }
    var timeoutText by remember(settings) {
        mutableStateOf(settings?.commandTimeoutSeconds?.toString() ?: "60")
    }
    val scope = rememberCoroutineScope()

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
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://api.openai.com/v1") },
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("gpt-4o-mini") },
            )
            OutlinedTextField(
                value = vm.apiKey, onValueChange = { vm.apiKey = it },
                label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (vm.showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = vm::toggleShowKey) {
                        Text(if (vm.showKey) "Hide" else "Show")
                    }
                },
            )
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { timeoutText = it.filter(Char::isDigit).take(3) },
                label = { Text("Default command timeout (seconds)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        vm.saveApiKey()
                        vm.setBaseUrl(baseUrl)
                        vm.setModel(model)
                        timeoutText.toIntOrNull()?.let { vm.setTimeout(it) }
                    }
                }) { Text("Save") }
                OutlinedButton(
                    onClick = { vm.testConnection(baseUrl.trim(), model.trim()) },
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
