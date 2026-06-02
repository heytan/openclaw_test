package com.openclaw.car.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.caragent.START" -> NodeProcessService.start(context)
            "com.caragent.STOP" -> NodeProcessService.stop(context)
        }
    }
}
