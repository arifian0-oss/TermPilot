package com.termpilot.app

import android.app.Application
import com.termpilot.app.data.HostRepository
import com.termpilot.app.data.KnownHostsStore
import com.termpilot.app.data.SecureStore
import com.termpilot.app.data.SettingsRepository
import com.termpilot.app.llm.AgentLoop
import com.termpilot.app.llm.LlmClient
import com.termpilot.app.ssh.SshConnectionManager

/**
 * Manual DI container. Repositories are app-scoped; the [SshConnectionManager]
 * owns live SSH sessions and is closed when the process dies.
 */
class TermPilotApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Fix up the JCE provider before anything touches sshj crypto.
        com.termpilot.app.ssh.CryptoProviders.ensure()
        container = AppContainer(this)
    }
}

class AppContainer(val app: Application) {
    val secureStore by lazy { SecureStore(app) }
    val hostRepository by lazy { HostRepository(app) }
    val settingsRepository by lazy { SettingsRepository(app, secureStore) }
    val knownHostsStore by lazy { KnownHostsStore(app) }
    val llmClient by lazy { LlmClient() }
    val agentLoop by lazy { AgentLoop(llmClient) }
    val sshManager by lazy {
        SshConnectionManager(app, secureStore, knownHostsStore)
    }
}
