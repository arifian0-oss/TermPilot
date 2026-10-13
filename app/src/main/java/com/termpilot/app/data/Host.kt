package com.termpilot.app.data

import java.util.UUID

/** How the user authenticates to a host. Secrets themselves live in [SecureStore]. */
enum class AuthType {
    PASSWORD,
    KEYBOARD_INTERACTIVE,
    PRIVATE_KEY,
}

data class Host(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
) {
    /** Keys under which this host's secrets are stored in [SecureStore]. */
    fun passwordKey() = "host_pw_$id"
    fun privateKeyKey() = "host_key_$id" // PEM text
    fun keyPassphraseKey() = "host_keypass_$id"
}
