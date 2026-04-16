package com.billsnap.manager.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages admin-level store profile and payment method settings.
 * Uses SharedPreferences for lightweight config persistence — no Room migration required.
 */
class AdminProfileManager private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "admin_profile_prefs"
        private const val KEY_STORE_NAME = "store_name"
        private const val KEY_STORE_LOGO_PATH = "store_logo_path"
        private const val KEY_PAYMENT_METHODS = "payment_methods_json"

        @Volatile
        private var instance: AdminProfileManager? = null

        fun getInstance(context: Context): AdminProfileManager {
            return instance ?: synchronized(this) {
                instance ?: AdminProfileManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Store Info ──────────────────────────────────────────────

    var storeName: String
        get() = prefs.getString(KEY_STORE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STORE_NAME, value).apply()

    var storeLogoPath: String?
        get() = prefs.getString(KEY_STORE_LOGO_PATH, null)
        set(value) = prefs.edit().putString(KEY_STORE_LOGO_PATH, value).apply()

    // ── Payment Methods ────────────────────────────────────────

    fun getPaymentMethods(): List<PaymentMethodInfo> {
        val json = prefs.getString(KEY_PAYMENT_METHODS, null) ?: return getDefaultMethods()
        return try {
            val type = object : TypeToken<List<PaymentMethodInfo>>() {}.type
            gson.fromJson<List<PaymentMethodInfo>>(json, type) ?: getDefaultMethods()
        } catch (e: Exception) {
            getDefaultMethods()
        }
    }

    fun savePaymentMethods(methods: List<PaymentMethodInfo>) {
        prefs.edit().putString(KEY_PAYMENT_METHODS, gson.toJson(methods)).apply()
    }

    /**
     * Returns only payment methods that are both enabled AND have allowShare = true.
     * These are the methods that appear on exported share card images.
     */
    fun getShareablePaymentMethods(): List<PaymentMethodInfo> {
        return getPaymentMethods().filter { it.enabled && it.allowShare }
    }

    fun updatePaymentMethod(type: PaymentMethodType, updater: (PaymentMethodInfo) -> PaymentMethodInfo) {
        val methods = getPaymentMethods().toMutableList()
        val index = methods.indexOfFirst { it.type == type }
        if (index >= 0) {
            methods[index] = updater(methods[index])
            savePaymentMethods(methods)
        }
    }

    private fun getDefaultMethods(): List<PaymentMethodInfo> = listOf(
        PaymentMethodInfo(PaymentMethodType.BANK, "", "", enabled = false, allowShare = false),
        PaymentMethodInfo(PaymentMethodType.EASYPAISA, "", "", enabled = false, allowShare = false),
        PaymentMethodInfo(PaymentMethodType.JAZZCASH, "", "", enabled = false, allowShare = false)
    )
}

/**
 * Represents a single payment method configuration.
 * Serialized to JSON and stored in SharedPreferences.
 */
data class PaymentMethodInfo(
    val type: PaymentMethodType,
    val accountNumber: String,
    val accountTitle: String,
    val enabled: Boolean = false,
    val allowShare: Boolean = false
)

enum class PaymentMethodType {
    BANK, EASYPAISA, JAZZCASH;

    val displayName: String
        get() = when (this) {
            BANK -> "Bank Account"
            EASYPAISA -> "EasyPaisa"
            JAZZCASH -> "JazzCash"
        }
}
