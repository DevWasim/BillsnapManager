package com.billsnap.manager.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages App Lock state and PIN storage using EncryptedSharedPreferences.
 */
class AppLockManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "app_lock_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_PIN = "pin_code"
        private const val KEY_USE_FINGERPRINT = "use_fingerprint"

        @Volatile
        private var instance: AppLockManager? = null

        fun getInstance(context: Context): AppLockManager {
            return instance ?: synchronized(this) {
                instance ?: AppLockManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var isLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()

    var useFingerprint: Boolean
        get() = prefs.getBoolean(KEY_USE_FINGERPRINT, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_FINGERPRINT, value).apply()

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN, pin).apply()
    }

    fun verifyPin(pin: String): Boolean {
        return prefs.getString(KEY_PIN, null) == pin
    }

    fun hasPin(): Boolean {
        return prefs.getString(KEY_PIN, null) != null
    }
}
