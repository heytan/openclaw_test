package com.openclaw.car.audio

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.openclaw.car.OpenClawApp
import java.io.File

class AudioRecorder {

    companion object {
        private const val TAG = "${OpenClawApp.TAG}.AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val BIT_RATE = 128000
    }

    private var recorder: MediaRecorder? = null
    @Volatile
    private var recording = false
    private var tempFile: File? = null
    private var startTimeMs: Long = 0

    fun start(): Boolean {
        if (recording) return true

        val ctx = OpenClawApp.instance ?: return false

        try {
            tempFile = File(ctx.cacheDir, "ppt_recording.m4a")
            tempFile?.delete()

            @Suppress("DEPRECATION")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setOutputFile(tempFile!!.absolutePath)
                prepare()
                start()
            }

            recording = true
            startTimeMs = System.currentTimeMillis()
            Log.i(TAG, "Recording started: MediaRecorder MIC ${SAMPLE_RATE}Hz AAC")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            cleanup()
            return false
        }
    }

    fun stop(): Pair<ByteArray, Long> {
        val durationMs = System.currentTimeMillis() - startTimeMs
        recording = false
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder.stop() error: ${e.message}")
        }
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null

        val file = tempFile
        val data = if (file != null && file.exists() && file.length() > 0) {
            val bytes = file.readBytes()
            Log.i(TAG, "Recording stopped: ${bytes.size} bytes M4A")
            bytes
        } else {
            Log.w(TAG, "No recording data available")
            ByteArray(0)
        }

        tempFile?.delete()
        tempFile = null
        return Pair(data, durationMs)
    }

    fun isRecording(): Boolean = recording

    private fun cleanup() {
        recording = false
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        tempFile?.delete()
        tempFile = null
    }
}
