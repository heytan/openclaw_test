package com.openclaw.car.service

import android.content.Context
import android.util.Log
import com.openclaw.car.OpenClawApp
import com.openclaw.car.util.PreferenceHelper

object VoiceAssistantManager {

    private const val TAG = "${OpenClawApp.TAG}.VoiceAssistant"
    private const val BYD_VOICE_PKG = "com.byd.autovoice"

    fun disable(context: Context): Boolean {
        val result = try {
            val process = Runtime.getRuntime().exec(arrayOf("am", "force-stop", BYD_VOICE_PKG))
            process.waitFor()
            Log.i(TAG, "BYD AutoVoice force-stopped, exit=${process.exitValue()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BYD AutoVoice: ${e.message}")
            false
        }
        PreferenceHelper.saveBydVoiceDisabled(context, true)
        return result
    }

    fun enable(context: Context): Boolean {
        val result = try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "am", "start", "-n", "$BYD_VOICE_PKG/.ui.MainActivity"
            ))
            process.waitFor()
            Log.i(TAG, "BYD AutoVoice restarted, exit=${process.exitValue()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart BYD AutoVoice: ${e.message}")
            false
        }
        PreferenceHelper.saveBydVoiceDisabled(context, false)
        return result
    }

    fun isDisabled(context: Context): Boolean {
        return PreferenceHelper.getBydVoiceDisabled(context)
    }

    fun toggle(context: Context): Boolean {
        val currentlyDisabled = isDisabled(context)
        return if (currentlyDisabled) {
            enable(context)
        } else {
            disable(context)
        }
    }
}
