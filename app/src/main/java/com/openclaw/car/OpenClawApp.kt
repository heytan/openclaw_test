package com.openclaw.car

import android.app.Application
import android.util.Log
import com.openclaw.car.service.MapProtocolManager

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
        initMapProtocol()
    }

    private fun initMapProtocol() {
        val mgr = MapProtocolManager.init(this)
        val bound = mgr.bind()
        Log.i(TAG, "MapProtocolManager bind result: $bound")
    }

    companion object {
        const val TAG = "CarAgent"
        var instance: OpenClawApp? = null
            private set
    }
}
