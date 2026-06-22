package com.openclaw.car.agenui

import com.amap.agenui.render.surface.SurfaceSize
import com.openclaw.car.R
import org.json.JSONObject

/**
 * A2UI JSON 处理工具：图标白名单过滤、Image 缺失尺寸补丁、天气卡主题适配。
 * AGenUIFragment 与 BackgroundCardRenderActivity 共用同一份逻辑，避免两处漂移。
 */
object AGenUIHelpers {

    data class WeatherTheme(
        val cardBg: String,
        val headerBg: String,
        val accentColor: String,
        val sectionBg: String
    )

    data class WeatherCardResult(
        val themedJson: String,
        val condition: String
    )

    // Whitelist from StyleHelper.getIconResourceId in AGenUI-Client-Android-release.aar.
    // Any Icon name not in this list falls back to ic_circle_question_mark in the SDK.
    val KNOWN_ICON_NAMES = setOf(
        "accountcircle", "add", "arrowback", "arrowforward", "attachfile",
        "calendartoday", "call", "camera", "check", "close", "delete",
        "download", "edit", "event", "error", "favorite", "favoriteoff",
        "folder", "help", "home", "info", "locationon", "lock", "lockopen",
        "mail", "menu", "morevert", "morehoriz", "notificationsoff",
        "notifications", "payment", "person", "phone", "photo", "print",
        "refresh", "search", "send", "settings", "share", "shoppingcart",
        "star", "starhalf", "staroff", "upload", "visibility",
        "visibilityoff", "warning",
        // media transport (SDK supports these but they were missing here, causing
        // patchUnknownIcons to hide every icon on the music card)
        "play", "pause", "skipprevious", "skipnext", "rewind", "fastforward",
        "volumeup", "volumedown", "volumemute"
    )

    fun parseJsonObjects(text: String): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '{') {
                var depth = 0
                var foundEnd = -1
                for (j in i until text.length) {
                    if (text[j] == '{') depth++
                    else if (text[j] == '}') depth--
                    if (depth == 0) { foundEnd = j; break }
                }
                if (foundEnd > i) {
                    try {
                        results.add(JSONObject(text.substring(i, foundEnd + 1)))
                    } catch (_: Exception) {}
                    i = foundEnd + 1
                    continue
                }
            }
            i++
        }
        return results
    }

    // AGenUI SDK's ImageMeasurer returns null size when styles.width/height are missing,
    // and shouldReportAsyncImageSize() is hardcoded false in the AAR — so the bitmap
    // loads but the ImageView stays at 0×0. Inject explicit px dimensions for any Image
    // component that doesn't declare them. Default 220px is what the my-coffee template
    // prescribes for the QR code.
    fun patchImageDimensions(json: String): String {
        val objects = parseJsonObjects(json)
        if (objects.isEmpty()) return json
        var mutated = false
        for (obj in objects) {
            val update = obj.optJSONObject("updateComponents") ?: continue
            val components = update.optJSONArray("components") ?: continue
            for (i in 0 until components.length()) {
                val comp = components.optJSONObject(i) ?: continue
                if (comp.optString("component") != "Image") continue
                val url = comp.optString("url")
                if (url.isEmpty()) continue
                val styles = comp.optJSONObject("styles") ?: JSONObject().also {
                    comp.put("styles", it)
                }
                if (!styles.has("width") || !styles.has("height")) {
                    if (!styles.has("width")) styles.put("width", "220px")
                    if (!styles.has("height")) styles.put("height", "220px")
                    mutated = true
                }
            }
        }
        if (!mutated) return json
        return objects.joinToString("\n") { it.toString() }
    }

    // AGenUI SDK's StyleHelper.getIconResourceId(name) falls back to ic_circle_question_mark
    // for any name not in its hardcoded whitelist (case-sensitive, lowercase, no separators).
    // LLM often emits names like "coffee", "cup", "weather", "bell", "CreditCard" etc that
    // aren't in the whitelist, producing a stray "?" in the top-right of the card. Hide
    // any Icon component whose name isn't recognized by setting styles.display="none".
    fun patchUnknownIcons(json: String): String {
        val objects = parseJsonObjects(json)
        if (objects.isEmpty()) return json
        var mutated = false
        for (obj in objects) {
            val update = obj.optJSONObject("updateComponents") ?: continue
            val components = update.optJSONArray("components") ?: continue
            for (i in 0 until components.length()) {
                val comp = components.optJSONObject(i) ?: continue
                if (comp.optString("component") != "Icon") continue
                val rawName = comp.opt("name")
                val name = when (rawName) {
                    is String -> rawName.trim().lowercase()
                    is JSONObject -> rawName.optString("path", "").let { return@let "__path__" }
                    else -> ""
                }
                if (name.isEmpty() || name == "__path__") continue
                if (name in KNOWN_ICON_NAMES) continue
                val styles = comp.optJSONObject("styles") ?: JSONObject().also {
                    comp.put("styles", it)
                }
                if (styles.optString("display", "") == "none") continue
                styles.put("display", "none")
                mutated = true
            }
        }
        if (!mutated) return json
        return objects.joinToString("\n") { it.toString() }
    }

    fun processWeatherCard(json: String): WeatherCardResult? {
        var isWeather = false
        var condition = ""

        val jsonObjects = parseJsonObjects(json)
        for (obj in jsonObjects) {
            try {
                val createSurface = obj.optJSONObject("createSurface")
                if (createSurface != null && createSurface.optString("surfaceId", "")
                        .contains("weather", ignoreCase = true)) {
                    isWeather = true
                }
                val updateComponents = obj.optJSONObject("updateComponents")
                if (updateComponents != null) {
                    val sid = updateComponents.optString("surfaceId", "")
                    if (sid.contains("weather", ignoreCase = true)) {
                        isWeather = true
                        val components = updateComponents.optJSONArray("components") ?: continue
                        for (i in 0 until components.length()) {
                            val comp = components.getJSONObject(i)
                            val text = comp.optString("text", "")
                            val id = comp.optString("id", "")
                            val c = classifyWeather(text)
                            if (c != null) {
                                if (id == "condition" || condition.isEmpty()) {
                                    condition = c
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (!isWeather || condition.isEmpty()) return null
        val theme = getWeatherTheme(condition)
        return WeatherCardResult(applyWeatherTheme(json, theme), condition)
    }

    fun classifyWeather(text: String): String? = when {
        "雷阵雨" in text -> "thunderstorm"
        "暴雨" in text -> "heavy_rain"
        "大雪" in text -> "heavy_snow"
        "大雨" in text -> "heavy_rain"
        "中雨" in text -> "moderate_rain"
        "小雨" in text -> "light_rain"
        "中雪" in text -> "snow"
        "小雪" in text -> "snow"
        "雨夹雪" in text -> "sleet"
        "阵雪" in text -> "snow"
        "阵雨" in text -> "light_rain"
        "雪" in text -> "snow"
        "雨" in text -> "rain"
        "霾" in text -> "haze"
        "雾" in text -> "fog"
        "阴" in text -> "overcast"
        "多云" in text -> "cloudy"
        "晴" in text -> "sunny"
        "⛈" in text -> "thunderstorm"
        "🌩" in text -> "thunderstorm"
        "🌧" in text -> "rain"
        "🌨" in text -> "snow"
        "☀" in text -> "sunny"
        "⛅" in text -> "cloudy"
        "☁" in text -> "overcast"
        "🌫" in text -> "fog"
        else -> null
    }

    fun getWeatherTheme(condition: String): WeatherTheme = when (condition) {
        "sunny" -> WeatherTheme("#E3F2FD", "#BBDEFB", "#1976D2", "#E8F0FE")
        "cloudy" -> WeatherTheme("#ECEFF1", "#CFD8DC", "#546E7A", "#F5F5F5")
        "overcast" -> WeatherTheme("#ECEFF1", "#CFD8DC", "#455A64", "#F0F0F0")
        "light_rain", "moderate_rain" -> WeatherTheme("#E1F5FE", "#B3E5FC", "#0288D1", "#E8F4FD")
        "heavy_rain", "rain" -> WeatherTheme("#BBDEFB", "#90CAF9", "#0277BD", "#E3F2FD")
        "thunderstorm" -> WeatherTheme("#D1C4E9", "#B39DDB", "#512DA8", "#EDE7F6")
        "snow", "sleet", "heavy_snow" -> WeatherTheme("#E0F7FA", "#B2EBF2", "#00838F", "#E8F5F9")
        "fog", "haze" -> WeatherTheme("#F5F5F5", "#E0E0E0", "#757575", "#FAFAFA")
        else -> WeatherTheme("#E3F2FD", "#BBDEFB", "#1976D2", "#E8F0FE")
    }

    fun applyWeatherTheme(json: String, theme: WeatherTheme): String {
        val jsonObjects = parseJsonObjects(json)
        return jsonObjects.joinToString("\n") { obj ->
            try {
                val updateComponents = obj.optJSONObject("updateComponents")
                if (updateComponents != null) {
                    val components = updateComponents.optJSONArray("components")
                    if (components != null) {
                        for (i in 0 until components.length()) {
                            transformWeatherComponent(components.getJSONObject(i), theme)
                        }
                    }
                }
                obj.toString()
            } catch (_: Exception) { obj.toString() }
        }
    }

    fun getWeatherIconRes(condition: String): Int = when (condition) {
        "sunny" -> R.drawable.ic_weather_sunny
        "cloudy", "overcast" -> R.drawable.ic_weather_cloudy
        "light_rain", "moderate_rain", "heavy_rain", "rain" -> R.drawable.ic_weather_rain
        "thunderstorm" -> R.drawable.ic_weather_thunderstorm
        "snow", "sleet", "heavy_snow" -> R.drawable.ic_weather_snow
        "fog", "haze" -> R.drawable.ic_weather_fog
        else -> R.drawable.ic_weather_sunny
    }

    private fun transformWeatherComponent(comp: JSONObject, theme: WeatherTheme) {
        val id = comp.optString("id", "")
        val component = comp.optString("component", "")

        when {
            id == "main" -> comp.put("backgroundColor", theme.cardBg)
            id == "header" || id == "header_icon" -> comp.put("backgroundColor", theme.headerBg)
            component == "Icon" -> comp.put("color", theme.accentColor)
            component == "Divider" -> comp.put("color", "#10000000")
        }

        val bg = comp.optString("backgroundColor", "")
        if (bg.isNotEmpty() && id != "main" && id != "header" && id != "header_icon"
            && component != "Text" && component != "Icon") {
            comp.put("backgroundColor", theme.sectionBg)
        }
    }

    /**
     * 默认 surface size：宽度按设备宽度的 40% 估算（接近 AGenUI tab 里卡片的实际宽度），
     * 高度用屏幕全高让内容自然展开。
     */
    fun defaultSurfaceSize(displayWidthPx: Int, displayHeightPx: Int): SurfaceSize {
        val w = (displayWidthPx * 0.4).toFloat()
        return SurfaceSize(w, displayHeightPx.toFloat())
    }
}
