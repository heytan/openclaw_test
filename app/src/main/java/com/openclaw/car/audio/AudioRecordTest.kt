package com.openclaw.car.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.openclaw.car.OpenClawApp

object AudioRecordTest {

    private const val TAG = "${OpenClawApp.TAG}.AudioTest"

    fun runAll() {
        val sources = listOf(
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "MIC" to MediaRecorder.AudioSource.MIC,
            "DEFAULT" to MediaRecorder.AudioSource.DEFAULT,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )

        val sampleRates = listOf(16000, 48000)

        for ((name, source) in sources) {
            for (rate in sampleRates) {
                testSource(name, source, rate)
            }
        }
    }

    private fun testSource(name: String, source: Int, sampleRate: Int) {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (bufferSize <= 0) {
            Log.w(TAG, "$name@$sampleRate: invalid buffer size=$bufferSize")
            return
        }

        try {
            val recorder = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize * 2)
            val state = recorder.state

            if (state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "$name@$sampleRate: NOT initialized (state=$state)")
                recorder.release()
                return
            }

            recorder.startRecording()
            val recordingState = recorder.recordingState
            Thread.sleep(500)

            val buf = ShortArray(bufferSize / 2)
            val read = recorder.read(buf, 0, buf.size)

            var sum = 0L
            for (s in buf) sum += Math.abs(s.toInt())
            val avgAmp = if (read > 0) sum / read else 0

            Log.i(TAG, "$name@$sampleRate: OK read=${read}shorts avgAmplitude=$avgAmp recState=$recordingState")
            recorder.stop()
            recorder.release()
        } catch (e: Exception) {
            Log.e(TAG, "$name@$sampleRate: ERROR ${e.message}")
        }
    }
}
