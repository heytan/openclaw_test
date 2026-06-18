package com.openclaw.car.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject

/**
 * Controls BYD mediacenter playback via AUTOVOICE broadcasts.
 *
 * Two broadcast actions:
 *  - AUTOVOICE_COMMON_CONTROL: transport (play/pause/next/previous), no result.
 *  - AUTOVOICE_COMMON_OPERATION: state-changing ops (searchMusic/collect/like), result via
 *    AUTOVOICE_COMMON_RESULT broadcast back to com.byd.autovoice package.
 *
 * Volume uses standard Android AudioService — no BYD-specific IPC needed.
 *
 * Verified working on car device LZBYDUMNB6RW7X5P, 2026-06-17.
 */
object BydMusicController {

    private const val TAG = "BydMusicController"

    private const val PKG = "com.byd.mediacenter"
    private const val RECEIVER = "com.byd.mediacenter.voicecontrol.VoiceControlReceiver"

    private const val ACTION_CONTROL = "com.byd.action.AUTOVOICE_COMMON_CONTROL"
    private const val ACTION_OPERATION = "com.byd.action.AUTOVOICE_COMMON_OPERATION"

    private const val EXTRA_CONTROL_METHOD = "EXTRA_COMMON_CONTROL_METHOD"
    private const val EXTRA_CONTROL_PARAM = "EXTRA_COMMON_CONTROL_PARAM"
    private const val EXTRA_OPERATION_METHOD = "EXTRA_COMMON_OPERATION_METHOD"
    private const val EXTRA_OPERATION_PARAM = "EXTRA_COMMON_OPERATION_PARAM"

    enum class MediaSource(val value: Int) {
        LOCAL(0), USB(1), SD(2), BT_MUSIC(3),
        KUWO(4), KUGOU(5), XIMALAYA(6), YUNTING(7),
        KARAOKE(8), NETEASE(9), QQ(10), LOCAL_MEDIA(11), ONLINE_MUSIC(12);

        companion object {
            fun of(v: Int?): MediaSource? = values().firstOrNull { it.value == v }
        }
    }

    /**
     * Search and queue a song. After this, mediacenter typically loads the song in PAUSED state —
     * call [play] (or set autoPlay=true) to actually start playback.
     *
     * Either [song] or [artist] must be non-blank. Both can be given for tighter match.
     * - song only: matches by title
     * - artist only: mediacenter picks a representative/popular track from that artist
     * - both: narrows to that specific song
     *
     * @param song   song name, optional if artist given
     * @param artist artist name, optional if song given
     * @param source optional media source hint (e.g. MediaSource.QQ). If null, uses current active.
     */
    fun searchPlay(
        context: Context,
        song: String? = null,
        artist: String? = null,
        source: MediaSource? = null,
        autoPlay: Boolean = true
    ) {
        val s = song?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        require(s.isNotBlank() || a.isNotBlank()) { "must provide song or artist" }
        val json = JSONObject().apply {
            if (s.isNotBlank()) put("song", s)
            if (a.isNotBlank()) put("artist", a)
            source?.let { put("mediaSource", it.value) }
        }
        sendOperation(context, "searchMusic", json)
        Log.i(TAG, "searchPlay: song='${s.ifBlank { "_" }}' artist='${a.ifBlank { "_" }}' source=${source ?: "auto"}")
        if (autoPlay) {
            // searchMusic lands in PAUSED; nudge to PLAYING. Sending unconditionally is safe —
            // if already playing, mediacenter no-ops.
            play(context)
        }
    }

    fun play(context: Context) = sendControl(context, "play")
    fun pause(context: Context) = sendControl(context, "pause")
    fun next(context: Context) = sendControl(context, "next")
    fun previous(context: Context) = sendControl(context, "previous")

    fun volumeUp(context: Context, showUi: Boolean = true) =
        adjustVolume(context, AudioManager.ADJUST_RAISE, showUi)

    fun volumeDown(context: Context, showUi: Boolean = true) =
        adjustVolume(context, AudioManager.ADJUST_LOWER, showUi)

    fun setVolume(context: Context, index: Int, showUi: Boolean = true) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            index.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
            if (showUi) AudioManager.FLAG_SHOW_UI else 0
        )
        Log.i(TAG, "setVolume: $index")
    }

    private fun sendControl(context: Context, method: String, param: JSONObject? = null) {
        val intent = Intent(ACTION_CONTROL).apply {
            component = ComponentName(PKG, RECEIVER)
            putExtra(EXTRA_CONTROL_METHOD, method)
            putExtra(EXTRA_CONTROL_PARAM, param?.toString() ?: "{}")
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "control: $method ${param ?: "{}"}")
    }

    private fun sendOperation(context: Context, method: String, param: JSONObject? = null) {
        val intent = Intent(ACTION_OPERATION).apply {
            component = ComponentName(PKG, RECEIVER)
            putExtra(EXTRA_OPERATION_METHOD, method)
            putExtra(EXTRA_OPERATION_PARAM, param?.toString() ?: "{}")
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "operation: $method ${param ?: "{}"}")
    }

    private fun adjustVolume(context: Context, direction: Int, showUi: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustVolume(
            direction,
            if (showUi) AudioManager.FLAG_SHOW_UI else 0
        )
        Log.d(TAG, "adjustVolume: ${if (direction == AudioManager.ADJUST_RAISE) "up" else "down"}")
    }
}
