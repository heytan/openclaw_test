package com.openclaw.car.service

import android.content.Context
import android.util.Log
import com.openclaw.car.OpenClawApp
import com.openclaw.car.music.BydMusicController
import org.json.JSONObject

class MusicController(private val context: Context) {

    companion object {
        private const val TAG = "${OpenClawApp.TAG}.Music"

        @Volatile
        private var instance: MusicController? = null

        fun init(context: Context): MusicController {
            val ctrl = MusicController(context)
            instance = ctrl
            return ctrl
        }

        fun getInstance(): MusicController? = instance
    }

    fun play(): String = transport("play") { BydMusicController.play(context) }
    fun pause(): String = transport("pause") { BydMusicController.pause(context) }
    fun next(): String = transport("next") { BydMusicController.next(context) }
    fun previous(): String = transport("previous") { BydMusicController.previous(context) }

    private inline fun transport(name: String, block: () -> Unit): String =
        try {
            block()
            Log.i(TAG, "$name ok")
            """{"ok":true}"""
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }

    fun search(
        song: String? = null,
        artist: String? = null,
        source: String? = null,
        autoPlay: Boolean = true
    ): String {
        val s = song?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        if (s.isBlank() && a.isBlank()) {
            return """{"ok":false,"error":"must provide song or artist"}"""
        }
        val src = source?.trim()?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let {
            BydMusicController.MediaSource.of(it)
        }
        return try {
            BydMusicController.searchPlay(
                context,
                song = s.ifBlank { null },
                artist = a.ifBlank { null },
                source = src,
                autoPlay = autoPlay
            )
            val songPart = if (s.isNotBlank()) """"song":${JSONObject.quote(s)},""" else ""
            val artistPart = if (a.isNotBlank()) """"artist":${JSONObject.quote(a)},""" else ""
            """{"ok":true,${songPart}${artistPart}"source":${src?.value ?: "auto"}}"""
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    fun volume(direction: String, level: Int? = null): String {
        return try {
            when (direction) {
                "up" -> BydMusicController.volumeUp(context)
                "down" -> BydMusicController.volumeDown(context)
                "set" -> {
                    if (level == null) return """{"ok":false,"error":"level required for set"}"""
                    BydMusicController.setVolume(context, level)
                }
                else -> return """{"ok":false,"error":"direction must be up/down/set"}"""
            }
            """{"ok":true,"direction":${JSONObject.quote(direction)}${if (level != null) ""","level":$level""" else ""}}"""
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }

    fun getState(): String {
        return try {
            val file = java.io.File("/data/local/tmp/music-state.json")
            if (!file.exists()) {
                return """{"ok":false,"error":"music state unavailable, run music-monitor.sh"}"""
            }
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            """{"ok":false,"error":${JSONObject.quote(e.message)}}"""
        }
    }
}
