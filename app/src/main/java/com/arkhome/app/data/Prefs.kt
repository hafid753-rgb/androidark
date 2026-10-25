package com.arkhome.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Server URL and access token. The token goes in EncryptedSharedPreferences
 * (Keystore-backed) rather than plain prefs - the Android counterpart of the
 * iOS app keeping it in the Keychain.
 */
class Prefs(context: Context) {

    private val store: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "ark_secure",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // A broken or rotated Keystore entry must not brick the app; fall back
        // to plain prefs so the user can at least re-enter the token.
        Log.w(TAG, "Encrypted prefs unavailable, falling back to plain prefs", e)
        context.getSharedPreferences("ark_plain", Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = store.getString(KEY_URL, "").orEmpty()
        set(value) = store.edit().putString(KEY_URL, value).apply()

    var token: String
        get() = store.getString(KEY_TOKEN, "").orEmpty()
        set(value) = store.edit().putString(KEY_TOKEN, value).apply()

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    fun clear() = store.edit().remove(KEY_URL).remove(KEY_TOKEN).apply()

    private companion object {
        const val TAG = "ArkPrefs"
        const val KEY_URL = "base_url"
        const val KEY_TOKEN = "access_token"
    }
}
