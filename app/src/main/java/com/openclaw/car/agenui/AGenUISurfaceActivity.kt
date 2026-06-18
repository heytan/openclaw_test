package com.openclaw.car.agenui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageButton
import com.amap.agenui.render.surface.ISurfaceManagerListener
import com.amap.agenui.render.surface.Surface
import com.amap.agenui.render.surface.SurfaceManager
import com.amap.agenui.render.surface.SurfaceSize
import com.openclaw.car.OpenClawApp
import com.openclaw.car.R

class AGenUISurfaceActivity : Activity() {

    companion object {
        const val TAG = "${OpenClawApp.TAG}.AGenUI"
        const val EXTRA_A2UI_JSON = "a2ui_json"
        var pendingA2UIData: String? = null
        var instance: AGenUISurfaceActivity? = null
            private set
    }

    private var surfaceManager: SurfaceManager? = null
    private var container: FrameLayout? = null
    var currentSurface: Surface? = null
        private set
    @Volatile
    private var cachedSurfaceSize: SurfaceSize? = null

    private val surfaceListener = object : ISurfaceManagerListener {
        override fun onCreateSurface(surface: Surface) {
            currentSurface = surface
            val view = surface.container
            if (view != null) {
                runOnUiThread {
                    container?.removeAllViews()
                    container?.addView(view)
                    Log.i(TAG, "Surface view added: ${surface.surfaceId}")
                }
            }
        }

        override fun onDeleteSurface(surface: Surface) {
            if (currentSurface == surface) {
                runOnUiThread {
                    container?.removeAllViews()
                    currentSurface = null
                }
            }
        }

        override fun onError(surface: Surface?, code: Int, message: String?) {
            Log.e(TAG, "AGenUI error: code=$code msg=$message surface=${surface?.surfaceId}")
        }

        override fun surfaceSize(surfaceId: String): SurfaceSize? {
            return cachedSurfaceSize
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agenui_surface)
        instance = this

        container = findViewById(R.id.agenui_container)
        findViewById<ImageButton>(R.id.btn_close).setOnClickListener { finish() }

        container?.post {
            val w = container?.width ?: 0
            val h = container?.height ?: 0
            cachedSurfaceSize = SurfaceSize(w.toFloat(), 0f)
            Log.i(TAG, "Container size: ${w}x${h}")
        }

        try {
            surfaceManager = SurfaceManager(this).also {
                it.addListener(surfaceListener)
            }
            Log.i(TAG, "SurfaceManager created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SurfaceManager", e)
            finish()
            return
        }

        val initialData = intent.getStringExtra(EXTRA_A2UI_JSON) ?: pendingA2UIData
        pendingA2UIData = null
        if (initialData != null) {
            container?.post { receiveA2UI(initialData) }
        }
    }

    fun receiveA2UI(json: String) {
        val mgr = surfaceManager ?: return
        try {
            mgr.beginTextStream()
            for (line in json.lines()) {
                if (line.isNotBlank()) {
                    mgr.receiveTextChunk(line)
                }
            }
            mgr.endTextStream()
            Log.i(TAG, "A2UI stream complete, ${json.lines().size} lines")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to receive A2UI data", e)
        }
    }

    override fun onDestroy() {
        surfaceManager?.removeListener(surfaceListener)
        surfaceManager?.destroy()
        surfaceManager = null
        container?.removeAllViews()
        container = null
        instance = null
        super.onDestroy()
    }
}
