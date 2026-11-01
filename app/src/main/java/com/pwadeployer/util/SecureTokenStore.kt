package com.pwadeployer.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureTokenStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "github_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences("github_fallback_prefs", Context.MODE_PRIVATE)
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var oauthClientId: String
        get() = prefs.getString(KEY_OAUTH_CLIENT_ID, DEFAULT_GITHUB_DEVICE_CLIENT_ID) ?: DEFAULT_GITHUB_DEVICE_CLIENT_ID
        set(value) = prefs.edit().putString(KEY_OAUTH_CLIENT_ID, value.trim()).apply()

    fun clearAuth() {
        prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "github_access_token"
        private const val KEY_OAUTH_CLIENT_ID = "github_oauth_client_id"

        /**
         * Default public OAuth App Client ID with Device Flow enabled (GitHub CLI public client ID),
         * so OAuth Device Flow works immediately out of the box without requiring a backend server,
         * while also allowing the user to configure their own GitHub OAuth App Client ID.
         */
        const val DEFAULT_GITHUB_DEVICE_CLIENT_ID = "178c6fc778ccc68e1d6a"
    }
}
