package com.openclaw.car.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight state persistence using SharedPreferences.
 * Remembers the user's last-selected persona and voice across app restarts.
 */
object PreferenceHelper {

    private const val PREFS_NAME = "openclaw_car_prefs"
    private const val KEY_LAST_PERSONA_INDEX = "last_persona_index"
    private const val KEY_LAST_VOICE_INDEX = "last_voice_index"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveLastPersona(context: Context, index: Int) {
        getPrefs(context).edit().putInt(KEY_LAST_PERSONA_INDEX, index).apply()
    }

    fun getLastPersona(context: Context): Int {
        return getPrefs(context).getInt(KEY_LAST_PERSONA_INDEX, 0)
    }

    fun saveLastVoice(context: Context, index: Int) {
        getPrefs(context).edit().putInt(KEY_LAST_VOICE_INDEX, index).apply()
    }

    fun getLastVoice(context: Context): Int {
        return getPrefs(context).getInt(KEY_LAST_VOICE_INDEX, 0)
    }
}
