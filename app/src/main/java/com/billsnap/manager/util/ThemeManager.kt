package com.billsnap.manager.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Manages Day/Night theme persistence and application.
 *
 * Call [applyTheme] in Application.onCreate() before super.onCreate() to restore
 * the persisted theme on startup without flicker.
 *
 * Call [toggleTheme] from Settings to switch themes — the caller must recreate()
 * the Activity after this call.
 */
object ThemeManager {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_THEME = "app_theme"

    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    /**
     * Reads persisted theme and applies it via AppCompatDelegate.
     * Must be called before Activity.setContentView() for instant effect.
     */
    fun applyTheme(context: Context) {
        when (getTheme(context)) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    /**
     * Returns the persisted theme key. Defaults to dark (existing behavior).
     */
    fun getTheme(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
    }

    /**
     * Persists the selected theme. Caller must recreate() the Activity.
     */
    fun setTheme(context: Context, theme: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme)
            .apply()
        applyTheme(context)
    }

    /**
     * Returns true if current theme is dark/night mode.
     */
    fun isDarkMode(context: Context): Boolean {
        return getTheme(context) == THEME_DARK
    }
}
