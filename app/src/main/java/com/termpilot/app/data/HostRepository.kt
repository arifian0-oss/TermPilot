package com.termpilot.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.hostDataStore: DataStore<Preferences> by preferencesDataStore(name = "hosts")

/**
 * Stores host entries (label/hostname/port/username/auth type) in DataStore.
 * Non-secret metadata only — credentials live in [SecureStore] keyed by host id.
 */
class HostRepository(private val context: Context) {

    private val hostsKey = stringPreferencesKey("hosts_json")

    val hosts: Flow<List<Host>> = context.hostDataStore.data.map { prefs ->
        val raw = prefs[hostsKey] ?: return@map emptyList()
        runCatching { decode(raw) }.getOrDefault(emptyList())
    }

    suspend fun upsert(host: Host) {
        context.hostDataStore.edit { prefs ->
            val current = runCatching { decode(prefs[hostsKey] ?: "[]") }.getOrDefault(emptyList())
            val updated = (current.filterNot { it.id == host.id } + host)
                .sortedBy { it.label.lowercase() }
            prefs[hostsKey] = encode(updated)
        }
    }

    suspend fun delete(host: Host, secureStore: SecureStore) {
        context.hostDataStore.edit { prefs ->
            val current = runCatching { decode(prefs[hostsKey] ?: "[]") }.getOrDefault(emptyList())
            prefs[hostsKey] = encode(current.filterNot { it.id == host.id })
        }
        // Wipe any secrets tied to this host.
        secureStore.remove(host.passwordKey())
        secureStore.remove(host.privateKeyKey())
        secureStore.remove(host.keyPassphraseKey())
    }

    private fun encode(hosts: List<Host>): String = JSONArray().apply {
        hosts.forEach { h ->
            put(JSONObject()
                .put("id", h.id)
                .put("label", h.label)
                .put("hostname", h.hostname)
                .put("port", h.port)
                .put("username", h.username)
                .put("authType", h.authType.name))
        }
    }.toString()

    private fun decode(raw: String): List<Host> = buildList {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(Host(
                id = o.getString("id"),
                label = o.getString("label"),
                hostname = o.getString("hostname"),
                port = o.optInt("port", 22),
                username = o.getString("username"),
                authType = runCatching { AuthType.valueOf(o.optString("authType", "PASSWORD")) }
                    .getOrDefault(AuthType.PASSWORD),
            ))
        }
    }
}
