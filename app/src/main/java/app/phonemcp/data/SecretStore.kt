package app.phonemcp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the connector secret, the Cloudflare named-tunnel token and the relay token.
 * Values are AES-GCM encrypted with an Android Keystore key (never leaves secure hardware where available).
 */
class SecretStore(
    context: Context,
    /** Called when a saved secret could not be read and had to be replaced: the owner's connector URL just died. */
    private val onSecretReset: () -> Unit = {}
) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    /** Decrypted once, then served from memory: every HTTP request needs it, and each Keystore call can fail. */
    @Volatile private var cached: String? = null

    @Synchronized
    fun mcpSecret(): String {
        cached?.let { return it }
        val existed = prefs.contains(KEY_SECRET)
        val stored = read(KEY_SECRET) ?: if (existed) { Thread.sleep(100); read(KEY_SECRET) } else null // one retry for a Keystore hiccup
        if (stored != null && stored.length >= 32) {
            cached = stored
            return stored
        }
        val fresh = rotate()
        if (existed) onSecretReset()
        return fresh
    }

    @Synchronized
    fun rotate(): String {
        val s = generate()
        write(KEY_SECRET, s)
        cached = s
        return s
    }

    fun namedToken(): String? = read(KEY_TOKEN)?.takeIf { it.isNotBlank() }

    fun setNamedToken(token: String?) {
        if (token.isNullOrBlank()) prefs.edit().remove(KEY_TOKEN).apply() else write(KEY_TOKEN, token.trim())
    }

    fun relayToken(): String? = read(KEY_RELAY)?.takeIf { it.isNotBlank() }

    fun setRelayToken(token: String?) {
        if (token.isNullOrBlank()) prefs.edit().remove(KEY_RELAY).apply() else write(KEY_RELAY, token.trim())
    }

    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            when {
                stored.startsWith("v1:") -> decrypt(stored.removePrefix("v1:"))
                stored.startsWith("p:") -> stored.removePrefix("p:").also { // legacy plaintext: re-encrypt or drop it
                    try { write(name, it) } catch (_: Exception) { prefs.edit().remove(name).apply() }
                }
                else -> null
            }
        } catch (_: Exception) {
            null // key lost (e.g. new lock screen setup): the caller regenerates
        }
    }

    private fun write(name: String, value: String) {
        // Fail closed: if the Keystore is unusable the secret is never written to disk in plaintext.
        val enc = "v1:" + encrypt(value)
        prefs.edit().putString(name, enc).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    companion object {
        private const val ALIAS = "phonemcp_secret_key"
        private const val KEY_SECRET = "mcp_secret"
        private const val KEY_TOKEN = "named_token"
        private const val KEY_RELAY = "relay_token"

        /** 32 random bytes, URL-safe base64 without padding (43 chars). */
        fun generate(): String {
            val b = ByteArray(32)
            SecureRandom().nextBytes(b)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
    }
}
