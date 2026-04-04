package com.oceanguard.ai.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for the NAS WebDAV access token using EncryptedSharedPreferences
 * backed by Android Keystore (AES256-GCM).
 */
class ContributionTokenStore(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "oceanguard_contribution_token",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""

    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    companion object {
        private const val KEY_TOKEN = "nas_token"
    }
}
