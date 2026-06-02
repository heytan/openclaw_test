package com.openclaw.car.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.openclaw.car.OpenClawApp
import com.openclaw.car.map.IProtocolAidlInterface
import com.openclaw.car.map.IProtocolCallback
import com.openclaw.car.map.ProtocolBaseModel
import com.openclaw.car.map.VoiceDeepSearchModel
import com.openclaw.car.map.ProtocolErrorModel
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MapProtocolManager(private val context: Context) {

    private var service: IProtocolAidlInterface? = null
    private var bound = false
    private val callbackQueue = LinkedBlockingQueue<String>()

    private val protocolCallback = object : IProtocolCallback.Stub() {
        override fun onFail(error: ProtocolErrorModel?) {
            val msg = "MapSDK error: code=${error?.err} msg=${error?.errorMessage} protocolID=${error?.protocolID}"
            Log.e(OpenClawApp.TAG, "[MapProto] onFail: $msg")
            callbackQueue.offer("""{"ok":false,"error":${JSONObject.quote(msg)}}""")
        }

        override fun onJSONResult(result: String?) {
            Log.i(OpenClawApp.TAG, "[MapProto] onJSONResult FULL: $result")
            callbackQueue.offer("""{"ok":true,"result":${JSONObject.quote(result ?: "")}}""")
        }

        override fun onSuccess(result: String?) {
            Log.i(OpenClawApp.TAG, "[MapProto] onSuccess FULL: $result")
            callbackQueue.offer("""{"ok":true,"result":${JSONObject.quote(result ?: "")}}""")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IProtocolAidlInterface.asInterface(binder)
            bound = true
            reconnectAttempts = 0
            callbackQueue.clear()
            Log.i(OpenClawApp.TAG, "[MapProto] Service connected: $name")
            try {
                service?.registCallBack(protocolCallback)
                Log.i(OpenClawApp.TAG, "[MapProto] Callback registered")
            } catch (e: Exception) {
                Log.e(OpenClawApp.TAG, "[MapProto] registCallBack failed: ${e.message}")
            }
            try {
                service?.setICompatibleIDVersion(2)
                Log.i(OpenClawApp.TAG, "[MapProto] setICompatibleIDVersion(2)")
            } catch (e: Exception) {
                Log.e(OpenClawApp.TAG, "[MapProto] setICompatibleIDVersion failed: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Log.w(OpenClawApp.TAG, "[MapProto] Service disconnected: $name")
            scheduleReconnect()
        }
    }

    fun bind(): Boolean {
        if (bound && service != null) return true
        val intent = Intent("action.com.autosdk.protocol.ProtocolService").apply {
            setPackage("com.byd.launchermap")
        }
        return try {
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(OpenClawApp.TAG, "[MapProto] bindService result: $ok")
            ok
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "[MapProto] bindService failed: ${e.message}")
            false
        }
    }

    fun unbind() {
        if (bound) {
            try {
                context.unbindService(connection)
            } catch (_: Exception) {}
            bound = false
            service = null
        }
    }

    fun isBound(): Boolean = bound && service != null

    private var reconnectAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun scheduleReconnect() {
        if (reconnectAttempts >= 5) {
            Log.e(OpenClawApp.TAG, "[MapProto] Max reconnect attempts reached")
            return
        }
        reconnectAttempts++
        val delay = (3000L * reconnectAttempts).coerceAtMost(15000)
        Log.i(OpenClawApp.TAG, "[MapProto] Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        mainHandler.postDelayed({
            try {
                val intent = Intent("action.com.autosdk.protocol.ProtocolService").apply {
                    setPackage("com.byd.launchermap")
                }
                val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                Log.i(OpenClawApp.TAG, "[MapProto] Reconnect bind result: $ok")
            } catch (e: Exception) {
                Log.e(OpenClawApp.TAG, "[MapProto] Reconnect failed: ${e.message}")
                scheduleReconnect()
            }
        }, delay)
    }

    private fun ensureBound(): Boolean {
        if (bound && service != null) return true
        Log.i(OpenClawApp.TAG, "[MapProto] Not bound, attempting bind")
        reconnectAttempts = 0
        return bind()
    }

    /** Re-register callback with map SDK. Call when app returns to foreground. */
    fun refreshCallback() {
        if (!bound || service == null) {
            Log.w(OpenClawApp.TAG, "[MapProto] refreshCallback: not bound, rebinding")
            reconnectAttempts = 0
            bind()
            return
        }
        reRegisterCallback()
    }

    fun getNaviStateSync(): String {
        val svc = service ?: return """{"ok":false,"error":"map service not bound"}"""
        return try {
            val navi = svc.getNaviState()
            val foreground = svc.isForegroundState()
            """{"ok":true,"navi":$navi,"foreground":$foreground}"""
        } catch (e: RemoteException) {
            """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    fun getMapStateSync(type: Int): String {
        val svc = service ?: return """{"ok":false,"error":"map service not bound"}"""
        return try {
            val state = svc.getMapState(type)
            """{"ok":true,"state":${JSONObject.quote(state ?: "")}}"""
        } catch (e: RemoteException) {
            """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    private fun waitForNextCallback(timeoutSec: Long): String {
        return callbackQueue.poll(timeoutSec, TimeUnit.SECONDS)
            ?: """{"ok":false,"error":"timeout"}"""
    }

    private fun reRegisterCallback() {
        val svc = service ?: return
        try {
            svc.registCallBack(protocolCallback)
            Log.i(OpenClawApp.TAG, "[MapProto] re-registered callback")
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "[MapProto] re-register failed: ${e.message}")
        }
    }

    fun keywordSearch(keyword: String, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        reRegisterCallback()
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_KEYWORD_SEARCH, keyword).apply {
                isNavi = true
                isMainCab = true
            }
            Log.i(OpenClawApp.TAG, "[MapProto] keywordSearch: $keyword")
            svc.setProtocolModelData(model)

            Thread {
                val searchResp = waitForNextCallback(15)
                callback(searchResp)
            }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun selectSearchResult(index: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        reRegisterCallback()
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_SEARCH_RESULT_SELECT).apply {
                operaType = index
            }
            Log.i(OpenClawApp.TAG, "[MapProto] selectResult: $index")
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(30)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun searchAndSelect(keyword: String, index: Int, finalCallback: (String) -> Unit) {
        if (!ensureBound()) {
            finalCallback("""{"ok":false,"error":"map service not bound"}""")
            return
        }
        val svc = service ?: run { finalCallback("""{"ok":false,"error":"map service not bound"}"""); return }
        reRegisterCallback()
        callbackQueue.clear()

        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_KEYWORD_SEARCH, keyword).apply {
                isNavi = true
                isMainCab = true
            }
            Log.i(OpenClawApp.TAG, "[MapProto] searchAndSelect: $keyword idx=$index")
            svc.setProtocolModelData(model)
        } catch (e: RemoteException) {
            finalCallback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
            return
        }

        Thread {
            val searchResp = waitForNextCallback(15)
            val searchJson = try { JSONObject(searchResp) } catch (_: Exception) { null }
            if (searchJson?.optBoolean("ok") != true) {
                finalCallback(searchResp)
                return@Thread
            }

            // Small delay to let map render results before selecting
            Thread.sleep(500)

            val svc2 = service ?: run { finalCallback("""{"ok":false,"error":"map service lost"}"""); return@Thread }
            reRegisterCallback()
            try {
                val selModel = ProtocolBaseModel(ProtocolBaseModel.ID_SEARCH_RESULT_SELECT).apply {
                    operaType = index
                }
                Log.i(OpenClawApp.TAG, "[MapProto] autoSelect: $index")
                svc2.setProtocolModelData(selModel)
            } catch (e: RemoteException) {
                finalCallback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
                return@Thread
            }

            val selectResp = waitForNextCallback(15)
            finalCallback("""{"ok":true,"search":$searchResp,"select":$selectResp}""")
        }.start()
    }

    fun goHome(callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_GOTO_HOME_COMPANY).apply {
                actionType = ProtocolBaseModel.ACTION_NAVI_HOME
            }
            Log.i(OpenClawApp.TAG, "[MapProto] goHome")
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(30)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun goOffice(callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_GOTO_HOME_COMPANY).apply {
                actionType = ProtocolBaseModel.ACTION_NAVI_COMPANY
            }
            Log.i(OpenClawApp.TAG, "[MapProto] goOffice")
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(30)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun cancelNavigation(callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_NAVI_VIEW_OPERA).apply {
                actionType = 0
            }
            Log.i(OpenClawApp.TAG, "[MapProto] cancelNavi")
            svc.setProtocolModelData(model)
            callback("""{"ok":true,"result":"cancel sent"}""")
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun naviToPoi(poiName: String, lat: String, lng: String, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        Thread {
            try {
                val model = ProtocolBaseModel(ProtocolBaseModel.ID_NAVI_TO_POI).apply {
                    destPoiName = poiName
                    destLatitude = lat
                    destLongitude = lng
                }
                Log.i(OpenClawApp.TAG, "[MapProto] naviToPoi: $poiName ($lng,$lat)")
                svc.setProtocolModelData(model)
                callback("""{"ok":true,"result":"naviToPoi sent"}""")
            } catch (e: RemoteException) {
                callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
            }
        }.start()
    }

    fun naviToPoiAsync(poiName: String, lat: String, lng: String, preference: Int = 0): String {
        val svc = service ?: return """{"ok":false,"error":"map service not bound"}"""
        Thread {
            try {
                val model = ProtocolBaseModel(ProtocolBaseModel.ID_NAVI_TO_POI).apply {
                    destPoiName = poiName
                    destLatitude = lat
                    destLongitude = lng
                    if (preference > 0) actionType = preference
                }
                Log.i(OpenClawApp.TAG, "[MapProto] naviToPoiAsync: $poiName ($lng,$lat) pref=$preference")
                svc.setProtocolModelData(model)
                Log.i(OpenClawApp.TAG, "[MapProto] naviToPoiAsync: setProtocolModelData returned")
            } catch (e: RemoteException) {
                Log.e(OpenClawApp.TAG, "[MapProto] naviToPoiAsync failed: ${e.message}")
            }
        }.start()
        return """{"ok":true,"result":"naviToPoi sent","poiName":"$poiName","lat":"$lat","lng":"$lng"}"""
    }

    fun naviToPoiViaPass(poiName: String, lat: String, lng: String,
                          passPoiName: String, passLat: String, passLng: String,
                          preference: Int = 0): String {
        val svc = service ?: return """{"ok":false,"error":"map service not bound"}"""
        Thread {
            try {
                val model = ProtocolBaseModel(ProtocolBaseModel.ID_NAVI_TO_POI_VIA_PASS).apply {
                    destPoiName = poiName
                    destLatitude = lat
                    destLongitude = lng
                    this.passPoiName = passPoiName
                    passLatitude = passLat
                    passLongitude = passLng
                    isWaypoint = true
                    if (preference > 0) actionType = preference
                }
                Log.i(OpenClawApp.TAG, "[MapProto] naviViaPass: via=$passPoiName($passLng,$passLat) → $poiName($lng,$lat) pref=$preference")
                svc.setProtocolModelData(model)
            } catch (e: RemoteException) {
                Log.e(OpenClawApp.TAG, "[MapProto] naviViaPass failed: ${e.message}")
            }
        }.start()
        return """{"ok":true,"result":"naviViaPass sent","dest":"$poiName","via":"$passPoiName"}"""
    }

    /**
     * Add a waypoint by canceling current nav and re-routing with naviViaPass (31005).
     * Only supports one waypoint due to SDK limitation.
     * Requires the original destination to be passed in (caller must store it).
     */
    fun addViaPoi(poiName: String, lat: String, lng: String,
                  targetPoiName: String, targetLat: String, targetLng: String,
                  callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        Thread {
            try {
                // Step 1: cancel current nav
                val cancelModel = ProtocolBaseModel(ProtocolBaseModel.ID_NAVI_VIEW_OPERA).apply {
                    actionType = 0
                }
                Log.i(OpenClawApp.TAG, "[MapProto] addViaPoi: cancel current nav")
                svc.setProtocolModelData(cancelModel)
                Thread.sleep(1500)

                // Step 2: re-route with waypoint via naviViaPass (31005)
                val viaModel = ProtocolBaseModel(ProtocolBaseModel.ID_NAVI_TO_POI_VIA_PASS).apply {
                    destPoiName = targetPoiName
                    destLatitude = targetLat
                    destLongitude = targetLng
                    passPoiName = poiName
                    passLatitude = lat
                    passLongitude = lng
                    isWaypoint = true
                }
                Log.i(OpenClawApp.TAG, "[MapProto] addViaPoi: naviViaPass via=$poiName → dest=$targetPoiName")
                svc.setProtocolModelData(viaModel)
                callback("""{"ok":true,"result":"addViaPoi sent","dest":"$targetPoiName","via":"$poiName"}""")
            } catch (e: Exception) {
                callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
            }
        }.start()
    }

    fun delViaPass(index: Int = -1): String {
        val svc = service ?: return """{"ok":false,"error":"map service not bound"}"""
        reRegisterCallback()
        Thread {
            try {
                val model = ProtocolBaseModel(ProtocolBaseModel.ID_DEL_VIA_PASS).apply {
                    actionType = index  // -1=delete all, 1/2/3=delete Nth waypoint
                }
                Log.i(OpenClawApp.TAG, "[MapProto] delViaPass: index=$index")
                svc.setProtocolModelData(model)
            } catch (e: RemoteException) {
                Log.e(OpenClawApp.TAG, "[MapProto] delViaPass failed: ${e.message}")
            }
        }.start()
        return """{"ok":true,"result":"delViaPass sent","index":$index}"""
    }

    fun dismiss(callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_DISMISS)
            Log.i(OpenClawApp.TAG, "[MapProto] dismiss")
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(30)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun aroundSearch(keyword: String, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        reRegisterCallback()
        callbackQueue.clear()
        try {
            // Try AroundSearchMode: type=1 (around search), key=keyword
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_AROUND_SEARCH, keyword).apply {
                searchQueryType = 1  // around search type
                isNavi = true
                isMainCab = true
                actionType = 1  // type from AroundSearchMode
            }
            Log.i(OpenClawApp.TAG, "[MapProto] aroundSearch: $keyword queryType=1 actionType=1")
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(20)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun aroundSearchAtPoi(poiName: String, lat: String, lng: String, keyword: String, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        reRegisterCallback()
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_AROUND_SEARCH_POI, keyword).apply {
                destPoiName = poiName
                destLatitude = lat
                destLongitude = lng
                isNavi = true
                isMainCab = true
            }
            Log.i(OpenClawApp.TAG, "[MapProto] aroundSearchAtPoi: $keyword around $poiName($lng,$lat)")
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(20)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun poiAroundSearch(poiName: String, lat: String, lng: String, keyword: String): String {
        val svc = service ?: return """{"ok":false,"error":"map service not bound"}"""
        reRegisterCallback()
        callbackQueue.clear()
        Thread {
            try {
                // Use protocol-based aroundSearchAtPoi (31006) instead of broken AIDL transaction
                val model = ProtocolBaseModel(ProtocolBaseModel.ID_AROUND_SEARCH_POI, keyword).apply {
                    destPoiName = poiName
                    destLatitude = lat
                    destLongitude = lng
                    isNavi = true
                    isMainCab = true
                }
                Log.i(OpenClawApp.TAG, "[MapProto] poiAroundSearch: $keyword around $poiName($lng,$lat)")
                svc.setProtocolModelData(model)
            } catch (e: RemoteException) {
                Log.e(OpenClawApp.TAG, "[MapProto] poiAroundSearch failed: ${e.message}")
            }
        }.start()
        return """{"ok":true,"result":"poiAroundSearch requested"}"""
    }

    fun requestMyLocation(): String {
        val svc = service ?: return """{"ok":false,"error":"map service not bound"}"""
        reRegisterCallback()
        callbackQueue.clear()
        Thread {
            try {
                val model = ProtocolBaseModel(ProtocolBaseModel.ID_MY_LOCATION)
                Log.i(OpenClawApp.TAG, "[MapProto] requestMyLocation")
                svc.setProtocolModelData(model)
                val resp = waitForNextCallback(10)
                Log.i(OpenClawApp.TAG, "[MapProto] myLocation response: $resp")
            } catch (e: RemoteException) {
                Log.e(OpenClawApp.TAG, "[MapProto] requestMyLocation failed: ${e.message}")
            }
        }.start()
        return """{"ok":true,"result":"myLocation requested"}"""
    }

    fun requestTrafficInfo(type: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_TRAFFIC_INFO).apply {
                actionType = type
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(30)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun searchAndNavigate(keyword: String, index: Int, finalCallback: (String) -> Unit) {
        if (!ensureBound()) {
            finalCallback("""{"ok":false,"error":"map service not bound"}""")
            return
        }
        val svc = service ?: run { finalCallback("""{"ok":false,"error":"map service not bound"}"""); return }
        reRegisterCallback()
        callbackQueue.clear()

        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_KEYWORD_SEARCH, keyword).apply {
                isNavi = true
                isMainCab = true
            }
            Log.i(OpenClawApp.TAG, "[MapProto] searchAndNavigate: $keyword")
            svc.setProtocolModelData(model)
        } catch (e: RemoteException) {
            finalCallback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
            return
        }

        Thread {
            val searchResp = waitForNextCallback(15)
            val searchJson = try { JSONObject(searchResp) } catch (_: Exception) { null }
            if (searchJson?.optBoolean("ok") != true) {
                finalCallback(searchResp)
                return@Thread
            }

            val svc2 = service ?: run { finalCallback("""{"ok":false,"error":"map service lost after search"}"""); return@Thread }
            try {
                val selModel = ProtocolBaseModel(ProtocolBaseModel.ID_SEARCH_RESULT_SELECT).apply {
                    operaType = index
                }
                Log.i(OpenClawApp.TAG, "[MapProto] autoSelect: $index")
                svc2.setProtocolModelData(selModel)
            } catch (e: RemoteException) {
                finalCallback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
                return@Thread
            }

            val selectResp = waitForNextCallback(15)
            finalCallback("""{"ok":true,"search":$searchResp,"select":$selectResp}""")
        }.start()
    }

    // --- New methods from SDK docs ---

    fun selectRoute(actionType: Int, opera: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            // Uses ID_SEARCH_RESULT_SELECT with operaType as route index
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_SEARCH_RESULT_SELECT).apply {
                this.actionType = actionType
                operaType = opera
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun naviOpera(actionType: Int, operaType: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_NAVI_OPERA).apply {
                this.actionType = actionType
                this.operaType = operaType
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun setHomeAddress(poiName: String, lat: String, lng: String, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_GOTO_HOME_COMPANY).apply {
                actionType = ProtocolBaseModel.ACTION_SET_HOME
                destPoiName = poiName
                destLatitude = lat
                destLongitude = lng
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun setCompanyAddress(poiName: String, lat: String, lng: String, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_GOTO_HOME_COMPANY).apply {
                actionType = ProtocolBaseModel.ACTION_SET_COMPANY
                destPoiName = poiName
                destLatitude = lat
                destLongitude = lng
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun alongTheWaySearch(actionType: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        reRegisterCallback()
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_ALONG_WAY_SEARCH).apply {
                this.actionType = actionType
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(30)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun alongTheWaySearchByKeyword(keyword: String, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        reRegisterCallback()
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_ALONG_WAY_SEARCH, keyword)
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(30)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun requestFrontTrafficInfo(type: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            // Reuse ID_TRAFFIC_INFO with specific type
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_TRAFFIC_INFO).apply {
                actionType = type
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun closeMap(type: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_CLOSE_MAP).apply {
                actionType = type
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun backToMap(type: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_BACK_TO_MAP).apply {
                actionType = type
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun mapOpera(actionType: Int, operaType: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_MAP_OPERA).apply {
                this.actionType = actionType
                this.operaType = operaType
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun volumeOpera(actionType: Int, operaType: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_VOLUME).apply {
                this.actionType = actionType
                this.operaType = operaType
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun pageJump(type: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_PAGE_JUMP).apply {
                actionType = type
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    fun addFavourite(type: Int, callback: (String) -> Unit) {
        val svc = service ?: run { callback("""{"ok":false,"error":"map service not bound"}"""); return }
        callbackQueue.clear()
        try {
            val model = ProtocolBaseModel(ProtocolBaseModel.ID_ADD_FAVOURITE).apply {
                actionType = type
            }
            svc.setProtocolModelData(model)
            Thread { callback(waitForNextCallback(15)) }.start()
        } catch (e: RemoteException) {
            callback("""{"ok":false,"error":${JSONObject.quote(e.message)}}""")
        }
    }

    companion object {
        private var instance: MapProtocolManager? = null

        fun getInstance(): MapProtocolManager? = instance

        fun init(context: Context): MapProtocolManager {
            val mgr = MapProtocolManager(context)
            instance = mgr
            return mgr
        }
    }
}
