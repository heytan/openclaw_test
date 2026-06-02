package com.openclaw.car.network

import android.content.Context
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket

data class SystemStatus(
    val gatewayRunning: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val ttsReachable: Boolean = false,
    val nodeStatus: String = ""
)

object StatusChecker {

    private const val GATEWAY_HOST = "127.0.0.1"
    private const val GATEWAY_PORT = 18801
    private const val TTS_HOST = "172.20.10.5"
    private const val TTS_PORT = 8091
    private const val TTS_TIMEOUT = 3000
    private const val GATEWAY_TIMEOUT = 2000

    fun check(context: Context): SystemStatus {
        return SystemStatus(
            gatewayRunning = checkPort(GATEWAY_HOST, GATEWAY_PORT, GATEWAY_TIMEOUT),
            accessibilityEnabled = checkAccessibility(context),
            ttsReachable = checkPort(TTS_HOST, TTS_PORT, TTS_TIMEOUT)
        )
    }

    private fun checkPort(host: String, port: Int, timeout: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeout)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun checkAccessibility(context: Context): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains("com.openclaw.car/com.openclaw.car.service.UiAutomationService")
        } catch (_: Exception) {
            false
        }
    }
}
