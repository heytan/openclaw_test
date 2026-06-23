package com.openclaw.car.agenui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.amap.agenui.render.surface.Surface
import com.openclaw.car.music.BydMusicController
import org.json.JSONObject
import java.io.File

/**
 * Reusable music-card logic shared by [com.openclaw.car.fragment.AGenUIFragment] (A2UI tab)
 * and [com.openclaw.car.agenui.InteractiveCardActivity] (floating bubble). Each host supplies
 * a [surfaceLookup] that returns the live music Surface (or null); this object owns the action
 * routing, the play/pause glyph flip, and the 1Hz song/artist/glyph sync.
 *
 * Extracted verbatim from AGenUIFragment so behaviour is identical across both hosts.
 */
class MusicCardController(private val tag: String = TAG) {

    private val handler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null
    private var lastPlayClickAt = 0L
    private var lastSong: String? = null
    private var lastArtist: String? = null

    /** Routes an A2UI action event envelope to the matching BydMusicController call. */
    fun handleActionEvent(context: Context, event: String, surfaceLookup: () -> Surface?) {
        Log.i(tag, "Action event: $event")
        try {
            val json = JSONObject(event)
            // SDK envelope: {"action":{"name":...,"sourceComponentId":...},"version":"v0.9"}
            // The event name is nested under "action", NOT at the top level.
            val name = json.optJSONObject("action")?.optString("name") ?: json.optString("name")
            if (name.isEmpty()) {
                Log.w(tag, "handleActionEvent: no action name in event")
                return
            }
            when (name) {
                "music_prev" -> BydMusicController.previous(context)
                "music_next" -> BydMusicController.next(context)
                "music_play_pause" -> {
                    // One-shot state read (not polling). After toggling, flip the play icon
                    // for click-driven visual feedback (the card is otherwise static).
                    val nowPlaying = if (isMusicPlaying()) {
                        BydMusicController.pause(context); false
                    } else {
                        BydMusicController.play(context); true
                    }
                    lastPlayClickAt = SystemClock.elapsedRealtime()
                    surfaceLookup()?.let { updatePlayGlyph(it, nowPlaying) }
                }
                "music_vol_up" -> BydMusicController.volumeUp(context)
                "music_vol_down" -> BydMusicController.volumeDown(context)
                else -> Log.w(tag, "Unknown action event: $name")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle action event", e)
        }
    }

    /** Flips the music card's play icon: "pause" when playing, "play" when paused. */
    fun updatePlayGlyph(surface: Surface, playing: Boolean) {
        try {
            surface.updateComponent("play_icon", mapOf("name" to if (playing) "pause" else "play"))
        } catch (e: Exception) {
            Log.w(tag, "updatePlayGlyph failed: ${e.message}")
        }
    }

    fun isMusicPlaying(): Boolean {
        return try {
            val file = File("/data/local/tmp/music-state.json")
            if (!file.exists()) return false
            val state = JSONObject(file.readText(Charsets.UTF_8))
            state.optString("state") == "playing"
        } catch (e: Exception) {
            Log.w(tag, "isMusicPlaying failed: ${e.message}")
            false
        }
    }

    /**
     * Lightweight music-state sync (no progress bar / time / cover — those have no data source).
     * Polls music-state.json at 1Hz and pushes only song title, artist, and the play/pause glyph.
     * The glyph update is suppressed for a 3s grace window after a click so the instant click-driven
     * flip doesn't get fought back by stale monitor data (monitor polls dumpsys every 3s).
     */
    fun startSync(surfaceLookup: () -> Surface?) {
        stopSync()
        val runnable = object : Runnable {
            override fun run() {
                pushMusicState(surfaceLookup)
                handler.postDelayed(this, 1000)
            }
        }
        syncRunnable = runnable
        handler.post(runnable)
    }

    fun stopSync() {
        syncRunnable?.let { handler.removeCallbacks(it) }
        syncRunnable = null
    }

    private fun pushMusicState(surfaceLookup: () -> Surface?) {
        val surface = surfaceLookup() ?: return
        try {
            val file = File("/data/local/tmp/music-state.json")
            if (!file.exists()) return
            val state = JSONObject(file.readText(Charsets.UTF_8))
            val title = state.optString("title", "")
            val artist = state.optString("artist", "")
            val isPlaying = state.optString("state") == "playing"
            // Only push title/artist when they actually change (avoid spamming the surface).
            if (title.isNotEmpty() && title != lastSong) {
                surface.updateComponent("song", mapOf("text" to title))
                lastSong = title
            }
            if (artist.isNotEmpty() && artist != lastArtist) {
                surface.updateComponent("artist", mapOf("text" to artist))
                lastArtist = artist
            }
            // Glyph: skip during the post-click grace window to avoid flicker with the click flip.
            val sinceClick = SystemClock.elapsedRealtime() - lastPlayClickAt
            if (sinceClick > 3000) {
                updatePlayGlyph(surface, isPlaying)
            }
        } catch (e: Exception) {
            Log.w(tag, "pushMusicState failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MusicCard"
    }
}
