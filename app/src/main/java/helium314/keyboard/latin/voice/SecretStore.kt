// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Stores sensitive values (currently: AI provider API keys) in an EncryptedSharedPreferences
 * bucket when the device supports it (API 23+).
 *
 * Migrates any plaintext value in the normal prefs file into the encrypted store on first use.
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val ENCRYPTED_FILE = "wisprboard_secrets"
    @Volatile private var cachedSecurePrefs: SharedPreferences? = null

    fun isSecureStorageAvailable(context: Context): Boolean = securePrefs(context) != null

    /**
     * Force the EncryptedSharedPreferences and AndroidKeyStore master key to be loaded from
     * background code. Called once at app start so the IME's first transcription does not pay
     * the cold-decrypt cost on the input thread. Best-effort: if the keystore is transiently
     * unhappy at boot, the next real read/write will simply retry.
     */
    fun warmUp(context: Context) {
        securePrefs(context)
    }

    fun getApiKey(context: Context, prefKey: String, default: String): String {
        val secure = securePrefs(context)
        if (secure != null) {
            val encrypted = secure.getString(prefKey, null)
            if (encrypted != null) return encrypted
            // First-run migration: if a plaintext value exists in normal prefs, move it here
            // and scrub the original.
            val legacy = context.prefs().getString(prefKey, null)
            if (!legacy.isNullOrBlank()) {
                // Commit the secure copy to disk before scrubbing the plaintext one. If the process
                // dies in between, the worst case is a leftover plaintext key that gets re-scrubbed on
                // the next read — never a lost key.
                secure.edit(commit = true) { putString(prefKey, legacy) }
                context.prefs().edit { remove(prefKey) }
                return legacy
            }
            return default
        }
        return default
    }

    fun setApiKey(context: Context, prefKey: String, value: String) {
        val secure = securePrefs(context)
        if (secure != null) {
            secure.edit(commit = true) { putString(prefKey, value) }
            // Ensure no stale plaintext copy remains (only scrub once the secure write is persisted).
            context.prefs().edit { remove(prefKey) }
        } else {
            throw IllegalStateException("Secure storage unavailable")
        }
    }

    private fun securePrefs(context: Context): SharedPreferences? {
        // EncryptedSharedPreferences relies on KeyGenParameterSpec (API 23+). Keep the reference
        // inside this method so the class isn't loaded on older devices.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        cachedSecurePrefs?.let { return it }
        return synchronized(this) {
            cachedSecurePrefs?.let { return@synchronized it }
            try {
                EncryptedPrefsFactory.create(context.applicationContext, ENCRYPTED_FILE).also {
                    cachedSecurePrefs = it
                }
            } catch (e: Exception) {
                // Do not cache failures: AndroidKeyStore can be transiently unavailable before
                // user unlock or during an OS update, and a later call should be allowed to retry.
                Log.w(TAG, "Failed to open encrypted prefs", e)
                null
            }
        }
    }

    /**
     * Explicit user-initiated reset of the encrypted prefs file. **This destroys all stored API
     * keys.** Wire from a settings screen, never from automatic recovery — the encrypted prefs
     * file is sometimes unreadable for transient reasons (locked keystore, OS update mid-flight)
     * that resolve on their own.
     */
    fun clearSecureStorage(context: Context) {
        synchronized(this) {
            try {
                cachedSecurePrefs?.edit(commit = true) { clear() }
                    ?: context.getSharedPreferences(ENCRYPTED_FILE, Context.MODE_PRIVATE).edit(commit = true) {
                        clear()
                    }
                cachedSecurePrefs = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.deleteSharedPreferences(ENCRYPTED_FILE)
                }
            } catch (e: Exception) {
                cachedSecurePrefs = null
                Log.w(TAG, "Failed to clear encrypted prefs file", e)
            }
        }
    }
}

private object EncryptedPrefsFactory {
    fun create(context: Context, name: String): SharedPreferences {
        val spec = androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
        val masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(spec)
        return androidx.security.crypto.EncryptedSharedPreferences.create(
            name,
            masterKeyAlias,
            context.applicationContext,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
