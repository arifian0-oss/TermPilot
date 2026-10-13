package com.termpilot.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class LlmSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val commandTimeoutSeconds: Int = 60,
)

/**
 * Non-secret LLM/agent settings in DataStore; the API key lives in
 * [SecureStore] under [API_KEY_SECRET].
 */
class SettingsRepository(
    private val context: Context,
    private val secureStore: SecureStore,
) {
    companion object {
        const val API_KEY_SECRET = "llm_api_key"
        private val baseUrlKey = stringPreferencesKey("llm_base_url")
        private val modelKey = stringPreferencesKey("llm_model")
        private val timeoutKey = intPreferencesKey("agent_cmd_timeout_s")
    }

    val settings: Flow<LlmSettings> = context.appSettings.data.map { p ->
        LlmSettings(
            baseUrl = p[baseUrlKey]?.trim()?.trimEnd('/') ?: LlmSettings().baseUrl,
            model = p[modelKey]?.trim().orEmpty().ifEmpty { LlmSettings().model },
            commandTimeoutSeconds = p[timeoutKey] ?: 60,
        )
    }

    suspend fun setBaseUrl(v: String) = context.appSettings.edit { it[baseUrlKey] = v.trim() }
    suspend fun setModel(v: String) = context.appSettings.edit { it[modelKey] = v.trim() }
    suspend fun setCommandTimeoutSeconds(v: Int) =
        context.appSettings.edit { it[timeoutKey] = v.coerceIn(5, 600) }

    fun getApiKey(): String? = secureStore.get(API_KEY_SECRET)?.takeIf { it.isNotBlank() }
    fun setApiKey(v: String) {
        if (v.isBlank()) secureStore.remove(API_KEY_SECRET) else secureStore.put(API_KEY_SECRET, v.trim())
    }
    fun hasApiKey(): Boolean = getApiKey() != null
}

// DataStore singleton for app settings.
private val Context.appSettings by preferencesDataStore(name = "settings")
