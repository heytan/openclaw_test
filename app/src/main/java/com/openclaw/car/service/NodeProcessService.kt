package com.openclaw.car.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.openclaw.car.OpenClawApp
import com.openclaw.car.R
import java.io.File

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
        stopGpsMonitor()
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

    private var locationManager: LocationManager? = null
    private val gpsFile = File("/data/local/tmp/gps.json")

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Prefer the car's own SomeIP GPS — but only if it's fresh.
            // When the car is indoors, SomeIPMatrixManager reports a stale cached
            // position for hours. If gps.json is older than 5 min, let
            // LocationManager (NETWORK_PROVIDER works indoors via wifi) overwrite.
            try {
                val existing = gpsFile.readText()
                if (existing.contains("\"provider\":\"someip\"") &&
                    System.currentTimeMillis() - gpsFile.lastModified() < 5 * 60 * 1000L
                ) return
            } catch (_: Exception) {}

            val lat = location.latitude.toString()
            val lng = location.longitude.toString()
            try {
                // Direct overwrite — /data/local/tmp is shell:shell drwxrwx--x so the
                // app (u10_a150) can't create/rename files there. We rely on
                // gps.json already existing with u10_a150 ownership (set up by
                // the boot script or install script). Atomic tmp+rename would be
                // nicer but the directory write permission makes it impossible.
                gpsFile.writeText("""{"ok":true,"lat":"$lat","lng":"$lng","provider":"${location.provider}"}""")
            } catch (e: Exception) {
                Log.w(OpenClawApp.TAG, "GPS write error: ${e.message}")
            }
        }

        override fun onLocationChanged(locations: MutableList<Location>) {
            locations.lastOrNull()?.let { onLocationChanged(it) }
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun startGpsMonitor() {
        // PRIMARY: in-app logcat poller. We have READ_LOGS granted, so we read
        // SomeIPMatrixManager:E directly without needing the gps-monitor.sh root
        // daemon (which has been flaky to spawn via su — setsid/nohup both
        // unreliable from app context on this device).
        startLogcatGpsPoller()

        // SECONDARY: Android LocationManager. Indoors both sources are stale,
        // but if the car has a network-based fix it'll surface here.
        startLocationFallback()
    }

    private val sendGpsRegex = Regex("""sendGps:([0-9a-f]+)\s+([0-9a-f]+)""")

    private fun startLogcatGpsPoller() {
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            Log.i(OpenClawApp.TAG, "GPS logcat poller started")
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val proc = Runtime.getRuntime().exec(
                        arrayOf("logcat", "-d", "-s", "SomeIPMatrixManager:E")
                    )
                    val output = proc.inputStream.bufferedReader().readText()
                    proc.waitFor()
                    val lastLine = output.lines().lastOrNull { it.contains("sendGps:") }
                    val m = lastLine?.let { sendGpsRegex.find(it) }
                    if (m != null) {
                        val lng = hexToAscii(m.groupValues[1])
                        val lat = hexToAscii(m.groupValues[2])
                        if (lat.isNotEmpty() && lng.isNotEmpty()) {
                            gpsFile.writeText("""{"ok":true,"lat":"$lat","lng":"$lng","provider":"someip"}""")
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.w(OpenClawApp.TAG, "GPS poller error: ${e.message}")
                }
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            Log.i(OpenClawApp.TAG, "GPS logcat poller exiting")
        }.apply { isDaemon = true; name = "gps-logcat-poller" }.start()
    }

    private fun hexToAscii(hex: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < hex.length) {
            val c = hex.substring(i, i + 2).toIntOrNull(16) ?: break
            if (c > 0) sb.append(c.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun startLocationFallback() {
        try {
            locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = locationManager!!.getProviders(true)
            Log.i(OpenClawApp.TAG, "GPS providers: $providers")

            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (providers.contains(provider)) {
                    locationManager!!.getLastKnownLocation(provider)?.let {
                        locationListener.onLocationChanged(it)
                    }
                    locationManager!!.requestLocationUpdates(provider, 3000L, 0f, locationListener, Looper.getMainLooper())
                    Log.i(OpenClawApp.TAG, "GPS listener registered for $provider")
                }
            }
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "LocationManager error: ${e.message}")
        }
    }

    private fun stopGpsMonitor() {
        locationManager?.removeUpdates(locationListener)
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
