package com.termpilot.app.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security

/**
 * JCE provider setup for sshj on Android.
 *
 * Root cause of "no such algorithm: X25519 for provider BC": Android ships its
 * own stripped-down BouncyCastle registered under the name "BC". sshj's
 * [SecurityUtils] auto-registers the *bundled* bcprov jar, but
 * `Security.getProvider("BC")` is never null on Android, so every
 * `getInstance(algorithm, "BC")` lookup resolves to the **platform** BC —
 * which lacks X25519 (and Ed25519 on older releases). The bundled jar is
 * effectively shadowed.
 *
 * Fix: expose the bundled BouncyCastle's full algorithm table under a
 * *different* provider name ("TermPilotBC") that cannot collide with the
 * platform, and point sshj at it via [SecurityUtils.setSecurityProvider].
 * `BouncyCastleProvider` is final, so instead of subclassing we copy its
 * algorithm table into a thin [Provider] wrapper. This is the runtime
 * equivalent of the well-known SpongyCastle repackaging trick, but keeps the
 * modern bcprov (X25519/Ed25519 — SpongyCastle 1.58 predates X25519).
 *
 * The wrapper is *appended* (not inserted first) so default, provider-less
 * JCE lookups elsewhere in the app (TLS, AndroidX Security) keep resolving
 * to the platform providers exactly as before; only explicit "TermPilotBC"
 * lookups — i.e. all of sshj's — hit the bundled BouncyCastle.
 *
 * Must run before any sshj crypto use. Idempotent and cheap; safe to call
 * on every connect.
 */
object CryptoProviders {

    const val PROVIDER_NAME = "TermPilotBC"

    @Volatile
    private var installed = false

    fun ensure() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            if (Security.getProvider(PROVIDER_NAME) == null) {
                Security.addProvider(RenamedBouncyCastleProvider())
            }
            // Prevent sshj's auto-registration path (which would resolve to
            // the platform "BC"); all sshj JCE lookups now use our provider.
            SecurityUtils.setSecurityProvider(PROVIDER_NAME)
            installed = true
        }
    }

    /**
     * The bundled BouncyCastle's algorithm table, advertised under
     * [PROVIDER_NAME]. `Security` indexes algorithms by [Provider.getName],
     * so `getInstance(alg, "TermPilotBC")` resolves here.
     */
    private class RenamedBouncyCastleProvider : Provider(
        PROVIDER_NAME, 1.80, "BouncyCastle (renamed for TermPilot to avoid the platform BC)"
    ) {
        init {
            val bc = BouncyCastleProvider()
            for (entry in bc.entries) {
                put(entry.key, entry.value)
            }
        }
    }
}
