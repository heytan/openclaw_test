package com.openclaw.car.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object AudioPreviewPlayer {

    private const val TAG = "AudioPreview"
    private const val TTS_URL = "http://172.20.10.5:8091/v1/audio/speech"

    private var mediaPlayer: MediaPlayer? = null
    private val ttsClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val SAMPLE_FILES = arrayOf(
        "voice_samples/活泼女声.wav",
        "voice_samples/明亮女声.wav",
        "voice_samples/磁性男声.wav",
        "voice_samples/小何.wav",
        "voice_samples/王力宏.wav",
        "voice_samples/高圆圆.wav"
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

    fun playCloneSample(context: Context) {
        stop()
        Thread {
            try {
                val body = JSONObject().apply {
                    put("model", "tts")
                    put("input", "你好，这是我的声音")
                    put("voice", "default")
                }
                val request = Request.Builder()
                    .url(TTS_URL)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = ttsClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Clone preview TTS failed: ${response.code}")
                    return@Thread
                }

                val audioBytes = response.body?.bytes() ?: return@Thread
                if (audioBytes.isEmpty()) return@Thread

                val tempFile = File(context.cacheDir, "clone_preview.mp3")
                tempFile.writeBytes(audioBytes)

                val mp = MediaPlayer()
                mp.setDataSource(tempFile.absolutePath)
                mp.setOnCompletionListener {
                    it.release()
                    if (mediaPlayer == mp) mediaPlayer = null
                    tempFile.delete()
                }
                mp.setOnErrorListener { mp2, _, _ ->
                    mp2.release()
                    if (mediaPlayer == mp2) mediaPlayer = null
                    tempFile.delete()
                    true
                }
                mp.prepare()
                mp.start()
                mediaPlayer = mp
                Log.i(TAG, "Clone preview playing: ${audioBytes.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Clone preview failed: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        mediaPlayer?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
    }
}
