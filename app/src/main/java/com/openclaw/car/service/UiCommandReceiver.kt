package com.openclaw.car.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openclaw.car.OpenClawApp
import java.io.File

class UiCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if ("com.caragent.UI_CMD" != intent.action) return

        val action = intent.getStringExtra("action")
        val text = intent.getStringExtra("text")
        val target = intent.getStringExtra("target")

        Log.e(OpenClawApp.TAG, "UI_CMD: action=$action text=$text target=$target")

        val service = UiAutomationService.getInstance()
        if (service == null) {
            Log.e(OpenClawApp.TAG, "UI_CMD failed: AccessibilityService not running")
            lastResult = "ERROR: AccessibilityService not running"
            writeResult(lastResult)
            return
        }

        val result = service.executeCommand(action, text, target)
        Log.e(OpenClawApp.TAG, "UI_CMD result: $result")
        lastResult = result
        writeResult(result)
    }

    private fun writeResult(result: String) {
        try {
            val f = File(RESULT_FILE)
            f.writeText(result)
            f.setReadable(true, false)
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "writeResult failed: ${e.message}")
        }
    }

    companion object {
        @Volatile
        var lastResult: String = ""
            private set
        private const val RESULT_FILE = "/data/local/tmp/caragent_result.txt"
    }
}
