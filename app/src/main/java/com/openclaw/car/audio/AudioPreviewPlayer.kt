package com.openclaw.car.audio

import android.content.Context
import android.media.MediaPlayer
import java.io.IOException

object AudioPreviewPlayer {

    private var mediaPlayer: MediaPlayer? = null

    private val SAMPLE_FILES = arrayOf(
        "voice_samples/活泼女声.wav",
        "voice_samples/明亮女声.wav",
        "voice_samples/磁性男声.wav"
    )

    fun playSample(context: Context, voiceIndex: Int) {
        stop()
        if (voiceIndex !in SAMPLE_FILES.indices) return

        val mp = MediaPlayer()
        try {
            val afd = context.assets.openFd(SAMPLE_FILES[voiceIndex])
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { mp2, _, _ -> mp2.release(); true }
            mp.prepare()
            mp.start()
            mediaPlayer = mp
        } catch (e: IOException) {
            e.printStackTrace()
            mp.release()
        }
    }

    fun stop() {
        mediaPlayer?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
    }
}
