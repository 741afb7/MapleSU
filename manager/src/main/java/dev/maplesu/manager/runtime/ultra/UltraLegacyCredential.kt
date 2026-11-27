package dev.maplesu.manager.runtime.ultra

import android.content.Context
import android.content.SharedPreferences
import dev.maplesu.manager.compat.ultraPreferences
import java.security.MessageDigest

/** A historical, locally verified record; the SDK still authenticates every use. */
internal class UltraLegacyCredential private constructor(val value: String, val fingerprint: String) {
    fun isCurrent(context: Context): Boolean = read(context)?.fingerprint == fingerprint

    internal fun storeVerified(context: Context, current: () -> Boolean): Boolean =
        synchronized(dev.maplesu.manager.modules.UltraRootKeyStore) {
            if (!current()) return@synchronized false
            check(context.ultraPreferences("maplesu-ultra", Context.MODE_PRIVATE).edit()
                .putString("rootKey", value).putBoolean("keyVerified", true)
                .putString("keyVerifiedFor", fingerprint).commit()) { "ULTRA_PAIRING_STORE_FAILED" }
            read(context)?.fingerprint == fingerprint
        }

    override fun toString(): String = "UltraLegacyCredential(verifiedRecord)"

    companion object {
        /** Only the protected claim reply enters this path; it is not yet persisted. */
        internal fun fromKernelClaim(value: String): UltraLegacyCredential? =
            value.takeIf { it.length == 48 && it.all { character ->
                character in '0'..'9' || character in 'a'..'z' || character in 'A'..'Z'
            } }?.let { UltraLegacyCredential(it, fingerprint(it)) }

        private fun fingerprint(key: String): String = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun read(context: Context): UltraLegacyCredential? =
            read(context.ultraPreferences("maplesu-ultra", Context.MODE_PRIVATE))

        internal fun read(prefs: SharedPreferences): UltraLegacyCredential? {
            if (prefs.getString("environmentRemoval", null) != null ||
                !prefs.getString("rootKeySuspended", null).isNullOrBlank() ||
                !prefs.getBoolean("keyVerified", false)) return null
            val key = prefs.getString("rootKey", null)?.trim() ?: return null
            if (key.length !in 16..1023 || key.any { it.code !in 0x21..0x7e }) return null
            val fingerprint = fingerprint(key)
            if (prefs.getString("keyVerifiedFor", null) != fingerprint) return null
            return UltraLegacyCredential(key, fingerprint)
        }
    }
}
