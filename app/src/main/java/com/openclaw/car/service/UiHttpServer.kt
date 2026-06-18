package com.openclaw.car.service

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import com.openclaw.car.OpenClawApp
import com.openclaw.car.fragment.AGenUIFragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Locale

class UiHttpServer : Thread("ui-http-server") {

    private var serverSocket: ServerSocket? = null
    private val trackThread = android.os.HandlerThread("track-recorder").also { it.start() }
    private val trackHandler = android.os.Handler(trackThread.looper)
    private val trackInterval = 30000L // 30 seconds
    private val maxTrackPoints = 1200 // ~10 hours at 30s interval

    // Last navigation destination, used by addViaPoi to re-route with waypoint
    @Volatile private var lastDestPoiName: String = ""
    @Volatile private var lastDestLat: String = ""
    @Volatile private var lastDestLng: String = ""

    private val trackRunnable = object : Runnable {
        override fun run() {
            recordTrackPoint()
            trackHandler.postDelayed(this, trackInterval)
        }
    }

    fun startTrackRecording() {
        trackHandler.post(trackRunnable)
        Log.i(OpenClawApp.TAG, "[Track] Recording started, interval=${trackInterval}ms")
    }

    fun stopTrackRecording() {
        trackHandler.removeCallbacks(trackRunnable)
        Log.i(OpenClawApp.TAG, "[Track] Recording stopped")
    }

    private fun recordTrackPoint() {
        try {
            val loc = handleLocation()
            val json = JSONObject(loc)
            if (!json.optBoolean("ok")) return
            val lat = json.optDouble("lat", 0.0)
            val lng = json.optDouble("lng", 0.0)
            if (lat == 0.0 || lng == 0.0) return

            val trackFile = File(OpenClawApp.instance?.filesDir, "nav-track.json")
            val point = JSONObject().apply {
                put("lat", lat)
                put("lng", lng)
                put("time", System.currentTimeMillis())
            }

            val points = readTrackPoints()
            // Skip if same position as last point (parked)
            if (points.isNotEmpty()) {
                val last = points.last()
                if (last.optDouble("lat") == lat && last.optDouble("lng") == lng) return
            }
            points.add(point)
            if (points.size > maxTrackPoints) {
                writeTrackPoints(points.takeLast(maxTrackPoints))
            } else {
                writeTrackPoints(points)
            }
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "[Track] record failed: ${e.message}")
        }
    }

    private fun readTrackPoints(): MutableList<JSONObject> {
        try {
            val trackFile = File(OpenClawApp.instance?.filesDir, "nav-track.json")
            if (!trackFile.exists()) return mutableListOf()
            val arr = JSONArray(trackFile.readText())
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
            return list
        } catch (e: Exception) {
            return mutableListOf()
        }
    }

    private fun writeTrackPoints(points: List<JSONObject>) {
        try {
            val trackFile = File(OpenClawApp.instance?.filesDir, "nav-track.json")
            val arr = JSONArray()
            for (p in points) arr.put(p)
            trackFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "[Track] write failed: ${e.message}")
        }
    }

    override fun run() {
        try {
            serverSocket = ServerSocket(PORT)
            Log.i(OpenClawApp.TAG, "[UiHttp] Server started on port $PORT")
            while (!isInterrupted) {
                val socket = serverSocket?.accept() ?: break
                socket.tcpNoDelay = true
                socket.setSoLinger(true, 5)
                Thread {
                    try {
                        handleRequest(socket.getInputStream(), socket.getOutputStream())
                    } catch (e: Exception) {
                        Log.e(OpenClawApp.TAG, "[UiHttp] handle error: ${e.message}")
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "[UiHttp] Failed: ${e.message}")
        }
    }

    private fun handleRequest(input: java.io.InputStream, output: OutputStream) {
        // Read all request bytes
        val reqBytes = readAllBytes(input, 65536)
        if (reqBytes.isEmpty()) return
        val reqStr = String(reqBytes, Charsets.UTF_8)

        // Parse request line
        val lineEnd = reqStr.indexOf("\r\n")
        if (lineEnd < 0) return
        val requestLine = reqStr.substring(0, lineEnd)
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].split("?")[0]

        // Find header block end
        val headerEnd = reqStr.indexOf("\r\n\r\n")
        if (headerEnd < 0) return
        val headerBlock = reqStr.substring(lineEnd + 2, headerEnd)

        // Parse headers
        var contentLength = 0
        var isChunked = false
        for (line in headerBlock.split("\r\n")) {
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (key.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: 0
                }
                if (key.equals("Transfer-Encoding", ignoreCase = true) && value.contains("chunked", ignoreCase = true)) {
                    isChunked = true
                }
            }
        }

        // Calculate byte offset of body (after \r\n\r\n)
        val headerStr = reqStr.substring(0, headerEnd + 4)
        val bodyOffset = headerStr.toByteArray(Charsets.UTF_8).size

        // Extract body
        val body: String
        if (isChunked && bodyOffset < reqBytes.size) {
            // Parse chunked encoding from raw bytes
            body = decodeChunked(reqBytes, bodyOffset)
        } else if (contentLength > 0 && bodyOffset < reqBytes.size) {
            val len = minOf(contentLength, reqBytes.size - bodyOffset)
            body = String(reqBytes, bodyOffset, len, Charsets.UTF_8)
        } else {
            body = if (bodyOffset < reqBytes.size) String(reqBytes, bodyOffset, reqBytes.size - bodyOffset, Charsets.UTF_8) else ""
        }

        Log.i(OpenClawApp.TAG, "[UiHttp] $method $path cl=$contentLength chunked=$isChunked body='${body.take(100)}'")

        val response = when {
            path == "/health" -> handleHealth()
            path == "/location" -> handleLocation()
            path == "/gateway/restart" && method == "POST" -> handleGatewayRestart()
            path == "/command" && method == "POST" -> handleCommand(body)
            path == "/launch" && method == "POST" -> handleLaunch(body)
            path == "/keyevent" && method == "POST" -> handleKeyevent(body)
            path == "/browse" && method == "POST" -> handleBrowse(body)
            path == "/tap" && method == "POST" -> handleTap(body)
            path.startsWith("/map/") && method == "POST" -> handleMap(path, body)
            path.startsWith("/music/") && method == "POST" -> handleMusic(path, body)
            path == "/agenui/test" && method == "POST" -> handleAGenUITest()
            path == "/agenui/diag" -> handleAGenUIDiag()
            path == "/agenui/render" && method == "POST" -> handleAGenUIRender(body)
            else -> """{"ok":false,"error":"not found: $path"}"""
        }

        Log.i(OpenClawApp.TAG, "[UiHttp] response: ${response.take(100)}")
        val bytes = response.toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Connection: close\r\n".toByteArray())
        output.write("\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun handleLaunch(body: String): String {
        try {
            val json = JSONObject(body)
            val pkg = json.optString("package", "")
            val activity = json.optString("activity", "")
            val forceStop = json.optBoolean("forceStop", false)
            if (pkg.isEmpty()) return """{"ok":false,"error":"missing package"}"""

            val ctx = OpenClawApp.instance
                ?: return """{"ok":false,"error":"no app context"}"""

            if (forceStop) {
                try {
                    ctx.createPackageContext(pkg, 0)
                    Runtime.getRuntime().exec(arrayOf("am", "force-stop", pkg)).waitFor()
                    Thread.sleep(500)
                } catch (_: Exception) {}
            }

            val intent = Intent(Intent.ACTION_MAIN).apply {
                if (activity.isNotEmpty()) {
                    component = android.content.ComponentName(pkg, activity)
                } else {
                    setPackage(pkg)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            return """{"ok":true,"result":"launched $pkg"}"""
        } catch (e: Exception) {
            return """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    private fun handleKeyevent(body: String): String {
        try {
            val json = JSONObject(body)
            val keycode = json.optInt("keycode", -1)
            if (keycode < 0) return """{"ok":false,"error":"missing keycode"}"""

            Runtime.getRuntime().exec(arrayOf("input", "keyevent", keycode.toString()))
            return """{"ok":true,"result":"keyevent $keycode"}"""
        } catch (e: Exception) {
            return """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    private fun handleTap(body: String): String {
        try {
            val json = JSONObject(body)
            val x = json.optInt("x", -1)
            val y = json.optInt("y", -1)
            if (x < 0 || y < 0) return """{"ok":false,"error":"missing x/y"}"""

            Runtime.getRuntime().exec(arrayOf("input", "tap", x.toString(), y.toString()))
            return """{"ok":true,"result":"tap $x $y"}"""
        } catch (e: Exception) {
            return """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    private fun handleBrowse(body: String): String {
        try {
            val json = JSONObject(body)
            val url = json.optString("url", "")
            if (url.isEmpty()) return """{"ok":false,"error":"missing url"}"""

            val ctx = OpenClawApp.instance
                ?: return """{"ok":false,"error":"no app context"}"""

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            return """{"ok":true,"result":"opened $url"}"""
        } catch (e: Exception) {
            return """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    private fun readAllBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        var offset = 0
        val deadline = System.currentTimeMillis() + 5000
        while (offset < maxBytes && System.currentTimeMillis() < deadline) {
            val available = input.available()
            if (available > 0) {
                val toRead = minOf(available, maxBytes - offset)
                val n = input.read(buf, offset, toRead)
                if (n < 0) break
                offset += n
            } else if (offset > 0) {
                Thread.sleep(10)
                if (input.available() == 0) break
            } else {
                val n = input.read(buf, offset, maxBytes - offset)
                if (n < 0) break
                offset += n
            }
        }
        return if (offset > 0) buf.copyOf(offset) else ByteArray(0)
    }

    private fun decodeChunked(data: ByteArray, startOffset: Int): String {
        // Chunked format: <hex-size>\r\n<data>\r\n ... 0\r\n\r\n
        val result = java.io.ByteArrayOutputStream()
        var pos = startOffset
        while (pos < data.size) {
            // Find end of chunk size line
            var lineEnd = pos
            while (lineEnd < data.size - 1 && !(data[lineEnd] == '\r'.code.toByte() && data[lineEnd + 1] == '\n'.code.toByte())) {
                lineEnd++
            }
            if (lineEnd >= data.size - 1) break
            val sizeLine = String(data, pos, lineEnd - pos, Charsets.US_ASCII).split(";")[0].trim()
            val chunkSize = sizeLine.toIntOrNull(16) ?: 0
            if (chunkSize == 0) break
            val dataStart = lineEnd + 2
            val dataEnd = dataStart + chunkSize
            if (dataEnd > data.size) break
            result.write(data, dataStart, chunkSize)
            pos = dataEnd + 2 // skip \r\n after data
        }
        return result.toString("UTF-8")
    }

    private fun handleLocation(): String {
        // Strategy 1: Read GPS from file written by background monitor script
        try {
            val file = java.io.File("/data/local/tmp/gps.json")
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < 15000) {
                val json = file.readText()
                val obj = JSONObject(json)
                if (obj.optBoolean("ok") && obj.has("lat") && obj.optString("lat").isNotEmpty()) {
                    return json
                }
            }
        } catch (_: Exception) {}

        // Strategy 2: Read GPS directly from logcat (requires READ_LOGS permission)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "SomeIPMatrixManager:E"))
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()
            val lines = output.lines().filter { it.contains("sendGps:") }
            val lastLine = lines.lastOrNull()
            if (lastLine != null) {
                val m = Regex("""sendGps:([0-9a-f]+)\s+([0-9a-f]+)""").find(lastLine)
                if (m != null) {
                    val lng = hexToAscii(m.groupValues[1])
                    val lat = hexToAscii(m.groupValues[2])
                    if (lat.isNotEmpty() && lng.isNotEmpty()) {
                        return """{"ok":true,"lat":"$lat","lng":"$lng","provider":"someip_logcat"}"""
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "[location] logcat read failed: ${e.message}")
        }

        // Strategy 3: Android LocationManager
        val ctx = OpenClawApp.instance ?: return """{"ok":false,"error":"no GPS data available"}"""
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var location: Location? = null
            for (provider in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                try {
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                        if (location == null || loc.accuracy < location.accuracy) {
                            location = loc
                        }
                    }
                } catch (_: SecurityException) {}
            }
            if (location != null) {
                return """{"ok":true,"lat":${location.latitude},"lng":${location.longitude},"accuracy":${location.accuracy},"provider":"${location.provider}","time":${location.time}}"""
            }
        } catch (_: Exception) {}

        return """{"ok":false,"error":"no GPS data available"}"""
    }

    private fun hexToAscii(hex: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < hex.length) {
            val code = hex.substring(i, i + 2).toInt(16)
            if (code > 0) sb.append(code.toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun handleHealth(): String {
        val running = UiAutomationService.getInstance() != null
        return """{"ok":true,"a11y":$running}"""
    }

    private fun handleGatewayRestart(): String {
        Thread {
            NodeProcessService.restartGateway(OpenClawApp.instance!!)
        }.start()
        return """{"ok":true,"message":"gateway restarting"}"""
    }

    private fun handleMap(path: String, body: String): String {
        val mgr = MapProtocolManager.getInstance()
            ?: return """{"ok":false,"error":"MapProtocolManager not initialized"}"""

        return when {
            path == "/map/search" -> {
                val json = JSONObject(body)
                val keyword = json.optString("keyword", "")
                if (keyword.isEmpty()) return """{"ok":false,"error":"missing keyword"}"""
                waitForCallback { cb -> mgr.keywordSearch(keyword, cb) }
            }
            path == "/map/select" -> {
                val json = JSONObject(body)
                val index = json.optInt("index", 1)
                val keyword = json.optString("keyword", "")
                if (keyword.isNotEmpty()) {
                    waitForCallback(45) { cb -> mgr.searchAndSelect(keyword, index, cb) }
                } else {
                    waitForCallback { cb -> mgr.selectSearchResult(index, cb) }
                }
            }
            path == "/map/home" -> {
                waitForCallback { cb -> mgr.goHome(cb) }
            }
            path == "/map/office" -> {
                waitForCallback { cb -> mgr.goOffice(cb) }
            }
            path == "/map/cancel" -> {
                waitForCallback { cb -> mgr.cancelNavigation(cb) }
            }
            path == "/map/dismiss" -> {
                waitForCallback { cb -> mgr.dismiss(cb) }
            }
            path == "/map/status" -> {
                val json = JSONObject(body)
                val type = json.optInt("type", 1)
                mgr.getMapStateSync(type)
            }
            path == "/map/naviState" -> {
                mgr.getNaviStateSync()
            }
            path == "/map/naviToPoi" -> {
                val json = JSONObject(body)
                var poiName = json.optString("poiName", "")
                var lat = json.optString("lat", "")
                var lng = json.optString("lng", "")
                val pref = json.optInt("preference", 0)
                if (poiName.isEmpty()) return """{"ok":false,"error":"missing poiName"}"""
                // Resolve alias if no coordinates provided
                if (lat.isEmpty() || lng.isEmpty()) {
                    val resolved = resolveAlias(poiName)
                    if (resolved != null) {
                        lat = resolved.optString("lat")
                        lng = resolved.optString("lng")
                        poiName = resolved.optString("poiName", poiName)
                    }
                }
                if (lat.isEmpty() || lng.isEmpty()) return """{"ok":false,"error":"missing lat/lng and no alias found for '$poiName'"}"""
                saveNavDestination(poiName, lat, lng)
                lastDestPoiName = poiName; lastDestLat = lat; lastDestLng = lng
                mgr.naviToPoiAsync(poiName, lat, lng, pref)
            }
            path == "/map/naviViaPass" -> {
                val json = JSONObject(body)
                val poiName = json.optString("poiName", "")
                val lat = json.optString("lat", "")
                val lng = json.optString("lng", "")
                val passName = json.optString("passPoiName", "")
                val passLat = json.optString("passLat", "")
                val passLng = json.optString("passLng", "")
                val pref = json.optInt("preference", 0)
                if (poiName.isEmpty() || passName.isEmpty()) return """{"ok":false,"error":"missing poiName or passPoiName"}"""
                saveNavDestination(poiName, lat, lng)
                lastDestPoiName = poiName; lastDestLat = lat; lastDestLng = lng
                mgr.naviToPoiViaPass(poiName, lat, lng, passName, passLat, passLng, pref)
            }
            path == "/map/addViaPoi" -> {
                val json = JSONObject(body)
                val poiName = json.optString("poiName", "")
                val lat = json.optString("lat", "")
                val lng = json.optString("lng", "")
                if (poiName.isEmpty()) return """{"ok":false,"error":"missing poiName"}"""
                val dest = lastDestPoiName
                val dLat = lastDestLat
                val dLng = lastDestLng
                if (dest.isEmpty()) {
                    // No destination set yet, treat the via point as destination (naviViaPass mode)
                    Log.i(OpenClawApp.TAG, "[MapServer] addViaPoi: no dest set, treating as direct nav to $poiName")
                    val pref = json.optInt("preference", 0)
                    mgr.naviToPoiAsync(poiName, lat, lng, pref)
                    return """{"ok":true,"result":"navigating to via point as destination"}"""
                }
                waitForCallback { cb -> mgr.addViaPoi(poiName, lat, lng, dest, dLat, dLng, cb) }
            }
            path == "/map/delViaPass" -> {
                val json = JSONObject(body)
                val index = json.optInt("index", -1)
                mgr.delViaPass(index)
            }
            path == "/map/trafficInfo" -> {
                val json = JSONObject(body)
                val type = json.optInt("type", 0)
                waitForCallback { cb -> mgr.requestTrafficInfo(type, cb) }
            }
            path == "/map/searchPoi" -> {
                val json = JSONObject(body)
                val keyword = json.optString("keyword", "")
                if (keyword.isEmpty()) return """{"ok":false,"error":"missing keyword"}"""
                searchWithPoiNames(mgr, keyword)
            }
            path == "/map/navi" -> {
                val json = JSONObject(body)
                val keyword = json.optString("keyword", "")
                val index = json.optInt("index", 1)
                if (keyword.isEmpty()) return """{"ok":false,"error":"missing keyword"}"""
                waitForCallback(45) { cb -> mgr.searchAndNavigate(keyword, index, cb) }
            }
            path == "/map/aroundSearch" -> {
                val json = JSONObject(body)
                val keyword = json.optString("keyword", "")
                if (keyword.isEmpty()) return """{"ok":false,"error":"missing keyword"}"""
                waitForCallback { cb -> mgr.aroundSearch(keyword, cb) }
            }
            path == "/map/aroundSearchAtPoi" -> {
                val json = JSONObject(body)
                val keyword = json.optString("keyword", "")
                val poiName = json.optString("poiName", "")
                val lat = json.optString("lat", "")
                val lng = json.optString("lng", "")
                if (keyword.isEmpty()) return """{"ok":false,"error":"missing keyword"}"""
                if (lat.isEmpty() || lng.isEmpty()) return """{"ok":false,"error":"missing lat/lng"}"""
                waitForCallback { cb -> mgr.aroundSearchAtPoi(poiName, lat, lng, keyword, cb) }
            }
            path == "/map/poiAroundSearch" -> {
                val json = JSONObject(body)
                val keyword = json.optString("keyword", "")
                val poiName = json.optString("poiName", "")
                val lat = json.optString("lat", "")
                val lng = json.optString("lng", "")
                if (keyword.isEmpty()) return """{"ok":false,"error":"missing keyword"}"""
                mgr.poiAroundSearch(poiName, lat, lng, keyword)
            }
            path == "/map/myLocation" -> {
                mgr.requestMyLocation()
            }
            path == "/map/navHistory" -> {
                handleNavHistory(body)
            }
            path == "/map/alias" -> {
                handleAlias(body)
            }
            path == "/map/selectRoute" -> {
                val json = JSONObject(body)
                val actionType = json.optInt("actionType", 1)
                val opera = json.optInt("opera", 0)
                waitForCallback { cb -> mgr.selectRoute(actionType, opera, cb) }
            }
            path == "/map/naviOpera" -> {
                val json = JSONObject(body)
                val actionType = json.optInt("actionType", 0)
                val operaType = json.optInt("operaType", 0)
                waitForCallback { cb -> mgr.naviOpera(actionType, operaType, cb) }
            }
            path == "/map/setHome" -> {
                val json = JSONObject(body)
                val poiName = json.optString("poiName", "")
                val lat = json.optString("lat", "")
                val lng = json.optString("lng", "")
                if (poiName.isEmpty() || lat.isEmpty() || lng.isEmpty())
                    return """{"ok":false,"error":"missing poiName/lat/lng"}"""
                waitForCallback { cb -> mgr.setHomeAddress(poiName, lat, lng, cb) }
            }
            path == "/map/setCompany" -> {
                val json = JSONObject(body)
                val poiName = json.optString("poiName", "")
                val lat = json.optString("lat", "")
                val lng = json.optString("lng", "")
                if (poiName.isEmpty() || lat.isEmpty() || lng.isEmpty())
                    return """{"ok":false,"error":"missing poiName/lat/lng"}"""
                waitForCallback { cb -> mgr.setCompanyAddress(poiName, lat, lng, cb) }
            }
            path == "/map/alongTheWaySearch" -> {
                val json = JSONObject(body)
                val actionType = json.optInt("actionType", 0)
                waitForCallback(30) { cb -> mgr.alongTheWaySearch(actionType, cb) }
            }
            path == "/map/alongTheWaySearchByKeyword" -> {
                val json = JSONObject(body)
                val keyword = json.optString("keyword", "")
                waitForCallback(30) { cb -> mgr.alongTheWaySearchByKeyword(keyword, cb) }
            }
            path == "/map/frontTrafficInfo" -> {
                val json = JSONObject(body)
                val type = json.optInt("type", 0)
                waitForCallback { cb -> mgr.requestFrontTrafficInfo(type, cb) }
            }
            path == "/map/closeMap" -> {
                val json = JSONObject(body)
                val type = json.optInt("type", 0)
                waitForCallback { cb -> mgr.closeMap(type, cb) }
            }
            path == "/map/backToMap" -> {
                val json = JSONObject(body)
                val type = json.optInt("type", 0)
                waitForCallback { cb -> mgr.backToMap(type, cb) }
            }
            path == "/map/mapOpera" -> {
                val json = JSONObject(body)
                val actionType = json.optInt("actionType", 0)
                val operaType = json.optInt("operaType", 0)
                waitForCallback { cb -> mgr.mapOpera(actionType, operaType, cb) }
            }
            path == "/map/volumeOpera" -> {
                val json = JSONObject(body)
                val actionType = json.optInt("actionType", 0)
                val operaType = json.optInt("operaType", 0)
                waitForCallback { cb -> mgr.volumeOpera(actionType, operaType, cb) }
            }
            path == "/map/pageJump" -> {
                val json = JSONObject(body)
                val type = json.optInt("type", 0)
                waitForCallback { cb -> mgr.pageJump(type, cb) }
            }
            path == "/map/addFavourite" -> {
                val json = JSONObject(body)
                val type = json.optInt("type", 0)
                waitForCallback { cb -> mgr.addFavourite(type, cb) }
            }
            path == "/map/track" -> {
                handleTrack(body)
            }
            else -> """{"ok":false,"error":"unknown map endpoint: $path"}"""
        }
    }

    private fun searchWithPoiNames(mgr: MapProtocolManager, keyword: String): String {
        // Step 1: trigger search via AIDL
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference("""{"ok":false,"error":"timeout"}""")
        mgr.keywordSearch(keyword) { resp ->
            result.set(resp)
            latch.countDown()
        }
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        val searchResp = result.get()
        val searchJson = try { JSONObject(searchResp) } catch (_: Exception) { null }
        if (searchJson?.optBoolean("ok") != true) return searchResp

        // Step 2: wait for map to render results, then read screen via accessibility
        Thread.sleep(2500)
        val accService = UiAutomationService.getInstance()
        if (accService == null) {
            Log.w(OpenClawApp.TAG, "[searchPoi] UiAutomationService not available")
            return searchResp
        }

        val root = accService.getRootInActiveWindow()
        if (root == null) {
            Log.w(OpenClawApp.TAG, "[searchPoi] no active window")
            return searchResp
        }

        try {
            val allTexts = mutableListOf<String>()
            collectAllTexts(root, allTexts, 0)
            Log.i(OpenClawApp.TAG, "[searchPoi] screen texts: ${allTexts.take(30)}")

            val poiNames = extractPoiNames(allTexts)
            Log.i(OpenClawApp.TAG, "[searchPoi] extracted ${poiNames.size} POIs: $poiNames")
            if (poiNames.isNotEmpty()) {
                val namesJson = poiNames.take(10).joinToString(",") { JSONObject.quote(it) }
                return """{"ok":true,"count":${poiNames.size},"poi_list":[$namesJson]}"""
            }
        } finally {
            root.recycle()
        }
        return searchResp
    }

    private fun extractPoiNames(texts: List<String>): List<String> {
        val uiSkipWords = setOf("请选择终点", "下拉刷新", "上拉加载更多", "搜索", "取消", "确认", "开始导航",
            "到这去", "收藏", "分享", "更多", "公里", "米", "元/升", "充电", "停车", "美食",
            "回家", "去公司", "分钟", "全 屏", "视 角", "极 简", "设 置", "查找目的地",
            "小吃快餐", "快餐", "中餐", "西餐", "火锅", "烧烤", "面包甜点", "饮品", "便利店",
            "宠物友好", "口感丰富", "本地人推荐", "人气推荐", "距离最近", "评分最高")
        val pois = mutableListOf<String>()
        for (text in texts) {
            val t = text.trim()
            if (t.length < 3 || t.length > 50) continue
            if (t in uiSkipWords) continue
            if (t.matches(Regex("^\\d+(\\.\\d+)?$"))) continue
            if (t.matches(Regex("^[0-9#]+$"))) continue
            if (t.matches(Regex("^[\\d.]+$"))) continue
            if (t.startsWith("¥")) continue // price
            if (t.contains("/人")) continue // price per person
            if (t.contains("/升")) continue // gas price
            // Skip addresses (contain 号 and look like addresses)
            if (t.contains("号") && (t.contains("路") || t.contains("道") || t.contains("街") || t.contains("楼"))) continue
            if (t.contains("段") && t.contains("路")) continue
            pois.add(t)
        }
        return pois
    }

    private fun collectAllTexts(node: android.view.accessibility.AccessibilityNodeInfo, texts: MutableList<String>, depth: Int) {
        if (depth > 15) return
        val t = node.text
        if (t != null && t.isNotEmpty()) {
            texts.add(t.toString())
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTexts(child, texts, depth + 1)
            child.recycle()
        }
    }

    private fun waitForCallback(timeoutSec: Long = 35, action: ((String) -> Unit) -> Unit): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference("""{"ok":false,"error":"timeout"}""")
        action { response ->
            result.set(response)
            latch.countDown()
        }
        latch.await(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        return result.get()
    }

    private fun handleCommand(body: String): String {
        try {
            val json = JSONObject(body)
            val action = json.optString("action", "")
            val text = if (json.has("text") && !json.isNull("text")) json.getString("text") else null
            val target = if (json.has("target") && !json.isNull("target")) json.getString("target") else null

            Log.i(OpenClawApp.TAG, "[UiHttp] action=$action text=$text target=$target")

            if (action.isEmpty()) return """{"ok":false,"error":"missing action"}"""

            val service = UiAutomationService.getInstance()
                ?: return """{"ok":false,"error":"AccessibilityService not running"}"""

            val r = service.executeCommand(action, text, target)
            return if (r.startsWith("OK:")) {
                """{"ok":true,"result":${JSONObject.quote(r)}}"""
            } else {
                """{"ok":false,"error":${JSONObject.quote(r)}}"""
            }
        } catch (e: Exception) {
            return """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    // --- Navigation History ---

    private val navHistoryFile: File
        get() = File(OpenClawApp.instance?.filesDir, "nav-history.json")
    private val MAX_NAV_HISTORY = 50
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun readNavHistory(): MutableList<JSONObject> {
        try {
            if (!navHistoryFile.exists()) return mutableListOf()
            val text = navHistoryFile.readText()
            val arr = JSONArray(text)
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                list.add(arr.getJSONObject(i))
            }
            return list
        } catch (e: Exception) {
            Log.w(OpenClawApp.TAG, "[NavHistory] read failed: ${e.message}")
            return mutableListOf()
        }
    }

    private fun writeNavHistory(records: List<JSONObject>) {
        try {
            val arr = JSONArray()
            for (r in records) arr.put(r)
            navHistoryFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "[NavHistory] write failed: ${e.message}")
        }
    }

    private fun saveNavDestination(poiName: String, lat: String, lng: String) {
        try {
            val records = readNavHistory()
            // Dedup: remove existing entry with same poiName+lat+lng
            val key = "$poiName|$lat|$lng"
            records.removeAll {
                "${it.optString("poiName")}|${it.optString("lat")}|${it.optString("lng")}" == key
            }
            // Add new entry at head
            val entry = JSONObject().apply {
                put("poiName", poiName)
                put("lat", lat)
                put("lng", lng)
                put("time", dateFormat.format(System.currentTimeMillis()))
                put("favorite", false)
            }
            records.add(0, entry)
            // Keep max records
            if (records.size > MAX_NAV_HISTORY) {
                writeNavHistory(records.take(MAX_NAV_HISTORY))
            } else {
                writeNavHistory(records)
            }
            Log.i(OpenClawApp.TAG, "[NavHistory] saved: $poiName")
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "[NavHistory] save failed: ${e.message}")
        }
    }

    private fun handleNavHistory(body: String): String {
        try {
            val json = JSONObject(body)
            val action = json.optString("action", "")

            return when {
                action.isEmpty() -> {
                    val records = readNavHistory().take(10)
                    val arr = JSONArray()
                    for (r in records) arr.put(r)
                    """{"ok":true,"history":$arr}"""
                }
                action == "favorites" -> {
                    val records = readNavHistory().filter { it.optBoolean("favorite") }
                    val arr = JSONArray()
                    for (r in records) arr.put(r)
                    """{"ok":true,"history":$arr}"""
                }
                action == "favorite" -> {
                    val poiName = json.optString("poiName", "")
                    val lat = json.optString("lat", "")
                    val lng = json.optString("lng", "")
                    if (poiName.isEmpty()) return """{"ok":false,"error":"missing poiName"}"""
                    val records = readNavHistory()
                    var found = false
                    for (r in records) {
                        if (r.optString("poiName") == poiName &&
                            r.optString("lat") == lat && r.optString("lng") == lng) {
                            r.put("favorite", true)
                            found = true
                        }
                    }
                    if (!found) {
                        // Add as favorite even if not in history
                        records.add(0, JSONObject().apply {
                            put("poiName", poiName)
                            put("lat", lat)
                            put("lng", lng)
                            put("time", dateFormat.format(System.currentTimeMillis()))
                            put("favorite", true)
                        })
                    }
                    writeNavHistory(records)
                    """{"ok":true}"""
                }
                action == "unfavorite" -> {
                    val index = json.optInt("index", -1)
                    if (index < 0) return """{"ok":false,"error":"missing index"}"""
                    val records = readNavHistory()
                    if (index < records.size) {
                        records[index].put("favorite", false)
                        writeNavHistory(records)
                    }
                    """{"ok":true}"""
                }
                action == "clear" -> {
                    writeNavHistory(emptyList())
                    """{"ok":true}"""
                }
                else -> """{"ok":false,"error":"unknown action: $action"}"""
            }
        } catch (e: Exception) {
            return """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    // --- Location Aliases ---

    private val aliasFile: File
        get() = File(OpenClawApp.instance?.filesDir, "nav-aliases.json")

    private fun readAliases(): JSONObject {
        try {
            if (!aliasFile.exists()) return JSONObject()
            return JSONObject(aliasFile.readText())
        } catch (e: Exception) {
            Log.w(OpenClawApp.TAG, "[Alias] read failed: ${e.message}")
            return JSONObject()
        }
    }

    private fun writeAliases(aliases: JSONObject) {
        try {
            aliasFile.writeText(aliases.toString())
        } catch (e: Exception) {
            Log.e(OpenClawApp.TAG, "[Alias] write failed: ${e.message}")
        }
    }

    /** Resolve alias to coordinates. Returns null if not an alias. */
    private fun resolveAlias(name: String): JSONObject? {
        val aliases = readAliases()
        if (!aliases.has(name)) return null
        val entry = aliases.getJSONObject(name)
        return entry
    }

    private fun handleAlias(body: String): String {
        try {
            val json = JSONObject(body)
            val action = json.optString("action", "list")

            return when (action) {
                "list" -> {
                    val aliases = readAliases()
                    val arr = JSONArray()
                    val keys = aliases.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val entry = aliases.getJSONObject(key)
                        arr.put(JSONObject().apply {
                            put("alias", key)
                            put("poiName", entry.optString("poiName"))
                            put("lat", entry.optString("lat"))
                            put("lng", entry.optString("lng"))
                        })
                    }
                    """{"ok":true,"aliases":$arr}"""
                }
                "set" -> {
                    val alias = json.optString("alias", "")
                    val poiName = json.optString("poiName", "")
                    val lat = json.optString("lat", "")
                    val lng = json.optString("lng", "")
                    if (alias.isEmpty() || lat.isEmpty() || lng.isEmpty())
                        return """{"ok":false,"error":"missing alias/lat/lng"}"""
                    val aliases = readAliases()
                    aliases.put(alias, JSONObject().apply {
                        put("poiName", if (poiName.isNotEmpty()) poiName else alias)
                        put("lat", lat)
                        put("lng", lng)
                    })
                    writeAliases(aliases)
                    Log.i(OpenClawApp.TAG, "[Alias] set: $alias → $poiName")
                    """{"ok":true}"""
                }
                "delete" -> {
                    val alias = json.optString("alias", "")
                    if (alias.isEmpty()) return """{"ok":false,"error":"missing alias"}"""
                    val aliases = readAliases()
                    aliases.remove(alias)
                    writeAliases(aliases)
                    """{"ok":true}"""
                }
                "get" -> {
                    val alias = json.optString("alias", "")
                    if (alias.isEmpty()) return """{"ok":false,"error":"missing alias"}"""
                    val entry = resolveAlias(alias)
                    if (entry == null) return """{"ok":false,"error":"alias not found: $alias"}"""
                    """{"ok":true,"alias":"$alias","poiName":"${entry.optString("poiName")}","lat":"${entry.optString("lat")}","lng":"${entry.optString("lng")}"}"""
                }
                else -> """{"ok":false,"error":"unknown action: $action"}"""
            }
        } catch (e: Exception) {
            return """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    // --- Track Recording ---

    private fun handleTrack(body: String): String {
        try {
            val json = JSONObject(body)
            val action = json.optString("action", "recent")

            return when (action) {
                "recent" -> {
                    val minutes = json.optInt("minutes", 30)
                    val points = readTrackPoints()
                    val cutoff = System.currentTimeMillis() - minutes * 60 * 1000L
                    val recent = points.filter { it.optLong("time") > cutoff }
                    val arr = JSONArray()
                    for (p in recent) arr.put(p)
                    """{"ok":true,"count":${recent.size},"minutes":$minutes,"track":$arr}"""
                }
                "clear" -> {
                    writeTrackPoints(emptyList())
                    """{"ok":true}"""
                }
                else -> """{"ok":false,"error":"unknown action: $action"}"""
            }
        } catch (e: Exception) {
            return """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    companion object {
        const val PORT = 18802
        private var instance: UiHttpServer? = null

        fun start() {
            if (instance?.isAlive == true) return
            instance?.interrupt()
            instance = UiHttpServer()
            instance!!.start()
            // TODO: track recording temporarily disabled, re-enable when needed
            // instance!!.startTrackRecording()
        }
    }

    private fun handleMusic(path: String, body: String): String {
        val ctrl = MusicController.getInstance()
            ?: return """{"ok":false,"error":"MusicController not initialized"}"""

        return when (path) {
            "/music/play" -> ctrl.play()
            "/music/pause", "/music/stop" -> ctrl.pause()
            "/music/next" -> ctrl.next()
            "/music/previous" -> ctrl.previous()
            "/music/state" -> ctrl.getState()
            "/music/search" -> {
                val json = JSONObject(body)
                val song = json.optString("song", json.optString("keyword", ""))
                val artist = json.optString("artist", "")
                val source = json.optString("source", "")
                val autoPlay = json.optBoolean("autoPlay", true)
                if (song.isEmpty() && artist.isEmpty()) {
                    """{"ok":false,"error":"must provide song or artist"}"""
                } else {
                    ctrl.search(
                        song = song.ifBlank { null },
                        artist = artist.ifBlank { null },
                        source = source.ifBlank { null },
                        autoPlay = autoPlay
                    )
                }
            }
            "/music/volume" -> {
                val json = JSONObject(body)
                val direction = json.optString("direction", "")
                val level = if (json.has("level")) json.getInt("level") else null
                if (direction.isEmpty()) """{"ok":false,"error":"missing direction (up/down/set)"}"""
                else ctrl.volume(direction, level)
            }
            else -> """{"ok":false,"error":"unknown music endpoint: $path"}"""
        }
    }

    private fun handleAGenUITest(): String {
        val activity = AGenUIFragment.instance ?: return """{"ok":false,"error":"no activity"}"""
        val sid = "test_${System.currentTimeMillis()}"
        val testJson = """
            {"version":"v0.9","createSurface":{"surfaceId":"$sid","catalogId":"https://a2ui.org/specification/v0_9/standard_catalog.json","theme":{},"sendDataModel":false,"animated":false}}
            {"version":"v0.9","updateComponents":{"surfaceId":"$sid","components":[{"id":"root","component":"Column","children":["t1"]},{"id":"t1","component":"Text","text":"Hello AGenUI!"}]}}
        """.trimIndent()
        activity.receiveA2UI(testJson)
        return """{"ok":true,"surfaceId":"$sid"}"""
    }

    private fun handleAGenUIDiag(): String {
        val fragment = AGenUIFragment.instance ?: return """{"ok":false,"error":"no fragment"}"""
        val count = fragment.cardCount
        val surface = fragment.currentSurface
        if (surface == null) {
            return """{"ok":true,"cardCount":$count,"surface":null}"""
        }
        val tree = surface.componentTree
        val components = tree.keys.joinToString(",")
        val root = surface.rootComponent
        val rootId = root?.id ?: "null"
        val rootView = root?.view
        val rootViewInfo = rootView?.let { "${it.javaClass.simpleName} ${it.width}x${it.height} children=${(it as? android.view.ViewGroup)?.childCount ?: 0}" } ?: "null"
        val container = surface.container
        val containerInfo = "${container.width}x${container.height} children=${container.childCount}"
        return """{"ok":true,"cardCount":$count,"surfaceId":"${surface.surfaceId}","components":"$components","rootId":"$rootId","rootView":"$rootViewInfo","container":"$containerInfo"}"""
    }

    private fun handleAGenUIRender(body: String): String {
        val activity = AGenUIFragment.instance ?: return """{"ok":false,"error":"no activity"}"""
        try {
            val json = JSONObject(body)
            val a2uiData = json.optString("data", "")
            if (a2uiData.isEmpty()) return """{"ok":false,"error":"missing data field"}"""
            activity.receiveA2UI(a2uiData)
            return """{"ok":true}"""
        } catch (e: Exception) {
            return """{"ok":false,"error":"${e.message}"}"""
        }
    }
}
