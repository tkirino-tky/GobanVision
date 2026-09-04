package com.github.tkirino.gobanreader.utility

import android.content.Context
import android.content.SharedPreferences

object PreferencesManager {
    private const val PREF_NAME = "goban_reader_prefs"
    private const val KEY_EMAIL = "saved_email_address"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getSavedEmail(context: Context): String {
        return getPrefs(context).getString(KEY_EMAIL, "") ?: ""
    }

    fun saveEmail(context: Context, email: String) {
        getPrefs(context).edit().putString(KEY_EMAIL, email.trim()).apply()
    }
}