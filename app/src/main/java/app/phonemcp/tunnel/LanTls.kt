package app.phonemcp.tunnel

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

/**
 * Self-signed server identity for LAN mode. The EC key is created inside the Android Keystore (the platform signs the
 * certificate), so the private key never leaves secure storage. Clients must PIN [fingerprint] instead of trusting a CA.
 */
object LanTls {
    private const val ALIAS = "phonemcp_lan_tls"

    private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun ensureKey(): KeyStore {
        val store = ks()
        if (!store.containsAlias(ALIAS)) {
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            gen.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=Phone MCP LAN"))
                    .build()
            )
            gen.generateKeyPair()
        }
        return store
    }

    /** SHA-256 of the certificate, upper-case hex pairs separated by colons. */
    fun fingerprint(): String {
        val cert = ensureKey().getCertificate(ALIAS) as X509Certificate
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
    }

    fun serverSocketFactory(): SSLServerSocketFactory {
        val store = ensureKey()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, null)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return ctx.serverSocketFactory
    }
}
