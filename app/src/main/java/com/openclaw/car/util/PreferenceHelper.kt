package com.openclaw.car.util

import android.content.Context
import android.content.SharedPreferences

object PreferenceHelper {

    private const val PREFS_NAME = "openclaw_car_prefs"
    private const val KEY_LAST_PERSONA_INDEX = "last_persona_index"
    private const val KEY_LAST_VOICE_INDEX = "last_voice_index"
    private const val KEY_LAST_DIALECT = "last_dialect"
    private const val KEY_CUSTOM_VOICE_TEXT = "custom_voice_text"
    private const val KEY_VOICE_MODE = "voice_mode" // "preset" or "custom"
    private const val KEY_VOICE_ENABLED = "voice_enabled"
    private const val KEY_BYD_VOICE_DISABLED = "byd_voice_disabled"
    private const val KEY_STREAM_TTS_ENABLED = "stream_tts_enabled"

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

    fun saveLastDialect(context: Context, dialect: String) {
        getPrefs(context).edit().putString(KEY_LAST_DIALECT, dialect).apply()
    }

    fun getLastDialect(context: Context): String {
        return getPrefs(context).getString(KEY_LAST_DIALECT, "") ?: ""
    }

    fun saveCustomVoiceText(context: Context, text: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_VOICE_TEXT, text).apply()
    }

    fun getCustomVoiceText(context: Context): String {
        return getPrefs(context).getString(KEY_CUSTOM_VOICE_TEXT, "") ?: ""
    }

    fun saveVoiceMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_VOICE_MODE, mode).apply()
    }

    fun getVoiceMode(context: Context): String {
        return getPrefs(context).getString(KEY_VOICE_MODE, "preset") ?: "preset"
    }

    fun saveVoiceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOICE_ENABLED, enabled).apply()
    }

    fun getVoiceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VOICE_ENABLED, true)
    }

    fun saveBydVoiceDisabled(context: Context, disabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BYD_VOICE_DISABLED, disabled).apply()
    }

    fun getBydVoiceDisabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BYD_VOICE_DISABLED, false)
    }

    fun saveStreamTtsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STREAM_TTS_ENABLED, enabled).apply()
    }

    fun getStreamTtsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STREAM_TTS_ENABLED, false)
    }
}
