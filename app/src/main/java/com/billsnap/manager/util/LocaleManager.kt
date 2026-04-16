package com.billsnap.manager.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.billsnap.manager.R
import java.util.Locale

/**
 * Manages runtime language switching between English and Urdu.
 *
 * Usage:
 *  - Call [wrapContext] in every Activity's attachBaseContext()
 *  - Call [setLanguage] to change the locale, then recreate the Activity
 *  - Call [getLanguage] to read the persisted language code ("en" or "ur")
 *
 * The locale preference is stored in SharedPreferences ("app_prefs", key "app_language").
 * Database, cloud sync, and Firestore payloads remain language-agnostic — only UI strings change.
 */
object LocaleManager {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_LANGUAGE = "app_language"

    const val ENGLISH = "en"
    const val URDU = "ur"

    /** Cached Nastaliq typeface — loaded once, reused across the app. */
    private var nastaliqTypeface: Typeface? = null

    /**
     * Returns the persisted language code. Defaults to English.
     */
    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, ENGLISH) ?: ENGLISH
    }

    /**
     * Persists the selected language. Caller is responsible for calling Activity.recreate()
     * only when the language has actually changed, to avoid unnecessary restarts.
     */
    fun setLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
    }

    /**
     * Wraps a base context with the persisted locale configuration.
     * Call this from Activity.attachBaseContext().
     */
    fun wrapContext(baseContext: Context): Context {
        val lang = getLanguage(baseContext)
        return updateContextLocale(baseContext, lang)
    }

    /**
     * Creates a locale-wrapped context for a specific language.
     * Useful in Workers where we need a localized context without an Activity.
     */
    fun getLocalizedContext(context: Context): Context {
        val lang = getLanguage(context)
        return updateContextLocale(context, lang)
    }

    /**
     * Returns true if the current locale is RTL (Urdu).
     */
    fun isRtl(context: Context): Boolean {
        return getLanguage(context) == URDU
    }

    /**
     * Returns the Nastaliq Typeface if current language is Urdu, null otherwise.
     * The typeface is cached after the first load for performance.
     * Use this for dynamically created TextViews, Toasts, Dialogs, etc.
     */
    fun getNastaliqTypeface(context: Context): Typeface? {
        if (getLanguage(context) != URDU) return null
        if (nastaliqTypeface == null) {
            nastaliqTypeface = ResourcesCompat.getFont(context, R.font.noto_nastaliq_urdu)
        }
        return nastaliqTypeface
    }

    private fun updateContextLocale(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        // Force the uiMode in the new Configuration to match our ThemeManager preference.
        // Otherwise, createConfigurationContext may revert to the system default dark/light mode 
        // and ignore AppCompatDelegate's settings.
        val currentUiMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()
        val newNightMode = if (ThemeManager.isDarkMode(context)) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        config.uiMode = currentUiMode or newNightMode

        val configContext = context.createConfigurationContext(config)
        
        return object : android.content.ContextWrapper(configContext) {
            override fun getApplicationInfo(): android.content.pm.ApplicationInfo {
                return context.applicationInfo
            }
            override fun getTheme(): android.content.res.Resources.Theme {
                return context.theme
            }
        }
    }
}

