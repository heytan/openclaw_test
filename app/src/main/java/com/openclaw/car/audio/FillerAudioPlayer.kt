package com.openclaw.car.audio

import android.media.MediaPlayer
import android.util.Log
import com.openclaw.car.OpenClawApp
import java.io.File

class FillerAudioPlayer {

    companion object {
        private const val TAG = "${OpenClawApp.TAG}.Filler"
        const val FILLER_DIR = "/data/local/tmp/openclaw-home/.openclaw/media/filler"
        private const val DEFAULT_VOICE = "default"
        private val CLIP_EXT = setOf("ogg", "mp3")
    }

    private var mediaPlayer: MediaPlayer? = null

    fun playRandom(): Boolean {
        stop()
        val clips = listFillerClips()
        if (clips.isEmpty()) {
            Log.w(TAG, "No filler clips under $FILLER_DIR/$DEFAULT_VOICE/")
            return false
        }
        val clip = clips.random()
        Log.i(TAG, "Playing filler: ${clip.absolutePath} (pool size=${clips.size})")
        val mp = MediaPlayer()
        return try {
            mp.setDataSource(clip.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                if (mediaPlayer == mp) mediaPlayer = null
            }
            mp.setOnErrorListener { mp2, what, extra ->
                Log.e(TAG, "Filler playback error: what=$what extra=$extra")
                mp2.release()
                if (mediaPlayer == mp2) mediaPlayer = null
                true
            }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play filler: ${e.message}")
            mp.release()
            false
        }
    }

    fun stop() {
        mediaPlayer?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
    }

    fun hasClips(): Boolean = listFillerClips().isNotEmpty()

    /**
     * Clips under filler/default/ — the only pool we use.
     * Gateway voiceId never changes; PersonaFragment.selectVoice() swaps the adapter's
     * `default` ref_audio and FillerRegenerator overwrites this directory to match.
     */
    private fun listFillerClips(): List<File> {
        val dir = File(FILLER_DIR, DEFAULT_VOICE)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.lowercase() in CLIP_EXT }
            ?.toList() ?: emptyList()
    }
}
