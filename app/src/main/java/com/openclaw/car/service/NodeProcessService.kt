package com.openclaw.car.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.openclaw.car.OpenClawApp
import com.openclaw.car.R

class NodeProcessService : Service() {

    private lateinit var manager: NodeProcessManager
    private val watchdog = Handler(Looper.getMainLooper())
    private val watchdogTask = object : Runnable {
        override fun run() {
            manager.restartDead()
            watchdog.postDelayed(this, WATCHDOG_INTERVAL)
        }
    }

    override fun onCreate() {
        manager = NodeProcessManager(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw")
            .setContentText("服务运行中")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        Thread {
            UiHttpServer.start()
            manager.startAll()
            startGpsMonitor()
        }.start()
        watchdog.postDelayed(watchdogTask, WATCHDOG_INTERVAL)

        // Start floating bubble + gateway WebSocket after gateway is ready
        Handler(Looper.getMainLooper()).postDelayed({
            startService(Intent(this, FloatingBubbleService::class.java))
            GatewayClient.instance.connect()
        }, 5000)

        Log.i(OpenClawApp.TAG, "NodeProcessService started")
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        watchdog.removeCallbacks(watchdogTask)
        manager.stopAll()
        GatewayClient.instance.disconnect()
        stopService(Intent(this, FloatingBubbleService::class.java))
        Log.i(OpenClawApp.TAG, "NodeProcessService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "OpenClaw Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun getProcessManager(): NodeProcessManager = manager

    private fun startGpsMonitor() {
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -f gps-monitor 2>/dev/null")).waitFor()
            Thread.sleep(500)

            // Try su first (needs Magisk auto-grant configured)
            val suTest = Runtime.getRuntime().exec(arrayOf("timeout", "2", "su", "-c", "id"))
            val suOk = suTest.inputStream.bufferedReader().readText().contains("uid=0")
            suTest.waitFor()

            if (suOk) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "nohup /system/bin/sh /data/local/tmp/gps-monitor.sh >/dev/null 2>&1 &"))
                Log.i(OpenClawApp.TAG, "GPS monitor started with root")
            } else {
                // Fallback: start as app user (won't have logcat access on multi-user Android)
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "nohup /system/bin/sh /data/local/tmp/gps-monitor.sh >/dev/null 2>&1 &"))
                Log.w(OpenClawApp.TAG, "GPS monitor started as app user (may not read logcat)")
            }
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "Failed to start GPS monitor: ${e.message}")
        }
    }

    companion object {
        private const val CHANNEL_ID = "openclaw"
        private const val NOTIFICATION_ID = 1
        private const val WATCHDOG_INTERVAL = 30_000L

        fun start(ctx: Context) {
            val intent = Intent(ctx, NodeProcessService::class.java)
            ctx.startForegroundService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NodeProcessService::class.java))
        }

        private var instance: NodeProcessService? = null

        fun restartGateway(ctx: Context) {
            val svc = instance
            if (svc != null) {
                Log.i(OpenClawApp.TAG, "Restarting gateway via API request")
                svc.manager.stopAll()
                Thread.sleep(2000)
                svc.manager.startAll()
            } else {
                Log.w(OpenClawApp.TAG, "NodeProcessService not running, doing full restart")
                stop(ctx)
                Thread.sleep(1000)
                start(ctx)
            }
        }
    }
}
