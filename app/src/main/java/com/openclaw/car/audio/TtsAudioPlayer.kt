package com.openclaw.car.audio

import android.media.MediaPlayer
import android.util.Log
import com.openclaw.car.OpenClawApp
import java.io.File

class TtsAudioPlayer {

    companion object {
        private const val TAG = "${OpenClawApp.TAG}.TtsPlayer"
        private const val OUTBOUND_DIR = "/data/local/tmp/openclaw-home/.openclaw/media/outbound"
        private const val POLL_MS = 500L
    }

    private var thread: Thread? = null
    private var running = false
    private var mediaPlayer: MediaPlayer? = null
    private var lastPlayedTimestamp = 0L
    private var enabled = true

    /** Fired when a real TTS file starts playing (used to cancel ResponseWatchdog). */
    var onPlaybackStarted: (() -> Unit)? = null

    /**
     * Fired before a new file begins playing — used by FloatingBubbleService to cancel
     * any in-flight streaming TTS / filler so the two voice sources don't overlap.
     * Invoked on the monitor thread; callers should marshal back to main if needed.
     */
    var onBeforePlayback: (() -> Unit)? = null

    fun start() {
        if (running) return
        running = true
        // Start from current latest file
        lastPlayedTimestamp = getLatestTimestamp()
        thread = Thread({ monitorLoop() }, "TtsAudioPlayer").apply { start() }
        Log.i(TAG, "TTS audio player started, lastTs=$lastPlayedTimestamp")
    }

    fun stop() {
        running = false
        stopPlayback()
        thread?.interrupt()
        thread = null
        Log.i(TAG, "TTS audio player stopped")
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) stopPlayback()
    }

    /**
     * Stop only the current in-flight playback (keep monitor running). Used by
     * FloatingBubbleService when recording or a new stream starts and we need
     * to silence whatever is currently playing without tearing down the poller.
     */
    fun stopCurrentPlayback() {
        stopPlayback()
    }

    private fun monitorLoop() {
        while (running) {
            try {
                val dir = File(OUTBOUND_DIR)
                if (!dir.exists()) {
                    Thread.sleep(POLL_MS)
                    continue
                }

                val files = dir.listFiles { f ->
                    f.name.startsWith("voice-") &&
                    (f.name.endsWith(".ogg") || f.name.endsWith(".mp3"))
                } ?: emptyArray()

                for (file in files.sortedBy { it.name }) {
                    val ts = extractTimestamp(file.name)
                    if (ts > lastPlayedTimestamp) {
                        lastPlayedTimestamp = ts
                        if (enabled) {
                            playFile(file)
                        }
                    }
                }

                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Monitor error: ${e.message}")
            }
        }
    }

    private fun playFile(file: File) {
        // 通知外部先打断跨播放器的 TTS / filler；本类的 stopPlayback 处理同类的旧 MediaPlayer。
        try { onBeforePlayback?.invoke() } catch (_: Exception) {}
        stopPlayback()
        Log.i(TAG, "Playing: ${file.name} (${file.length()} bytes)")
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                Log.i(TAG, "Playback complete: ${file.name}")
                it.release()
                if (mediaPlayer == mp) mediaPlayer = null
            }
            mp.setOnErrorListener { mp2, what, extra ->
                Log.e(TAG, "Playback error: what=$what extra=$extra")
                mp2.release()
                if (mediaPlayer == mp2) mediaPlayer = null
                true
            }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
            onPlaybackStarted?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play: ${e.message}")
            mp.release()
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
    }

    private fun getLatestTimestamp(): Long {
        val dir = File(OUTBOUND_DIR) ?: return 0L
        val files = dir.listFiles { f -> f.name.startsWith("voice-") } ?: return 0L
        return files.maxOfOrNull { extractTimestamp(it.name) } ?: 0L
    }

    private fun extractTimestamp(name: String): Long {
        // Format: voice-1781053151848---uuid.ogg
        val dash = name.indexOf('-')
        val separator = name.indexOf("---")
        if (dash < 0 || separator < 0) return 0L
        return try { name.substring(dash + 1, separator).toLong() } catch (_: Exception) { 0L }
    }
}
