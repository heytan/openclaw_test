package com.openclaw.car

import android.app.Application
import android.util.Log
import com.amap.agenui.AGenUI
import com.openclaw.car.agenui.PicassoImageLoader
import com.openclaw.car.service.MapProtocolManager
import com.openclaw.car.service.MusicController

class OpenClawApp : Application() {

    private val lifecycleCallback = object : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: android.app.Activity) {
            MapProtocolManager.getInstance()?.refreshCallback()
        }
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityStarted(activity: android.app.Activity) {}
        override fun onActivityPaused(activity: android.app.Activity) {}
        override fun onActivityStopped(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(lifecycleCallback)
        Log.i(TAG, "OpenClawApp created")
        initAGenUI()
        initMapProtocol()
        initMusicController()
    }

    private fun initAGenUI() {
        AGenUI.getInstance().initialize(this)
        AGenUI.getInstance().registerImageLoader(PicassoImageLoader())
        AGenUI.getInstance().setDayNightMode("light")
        Log.i(TAG, "AGenUI initialized, version: ${AGenUI.getVersion()}")
    }

    private fun initMapProtocol() {
        val mgr = MapProtocolManager.init(this)
        val bound = mgr.bind()
        Log.i(TAG, "MapProtocolManager bind result: $bound")
    }

    private fun initMusicController() {
        MusicController.init(this)
        Log.i(TAG, "MusicController initialized")
    }

    companion object {
        const val TAG = "CarAgent"
        var instance: OpenClawApp? = null
            private set
        // 由 FloatingBubbleService 在 onCreate 注册，PersonaFragment 等非 Service 组件
        // 通过这个静态引用访问 ResponseWatchdog（音色切换时抑制 filler）。
        @Volatile
        var responseWatchdog: com.openclaw.car.audio.ResponseWatchdog? = null
    }
}
