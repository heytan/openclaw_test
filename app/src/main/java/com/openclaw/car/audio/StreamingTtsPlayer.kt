package com.openclaw.car.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Streams PCM audio from the adapter `/v1/audio/speech` endpoint and plays it
 * chunk-by-chunk via AudioTrack in MODE_STREAM.
 *
 * [endpointUrl] must be the FULL adapter URL including path
 * (e.g. "http://172.20.10.5:8091/v1/audio/speech") — no extra path is appended.
 *
 * Thread model: each playStream() spawns a worker thread; cancel() signals it via
 * Call.cancel() (OkHttp throws IOException on next read).
 */
class StreamingTtsPlayer(
    private val endpointUrl: String,
    private val client: OkHttpClient
) {

    companion object {
        private const val TAG = "StreamingTtsPlayer"
        // VoxCPM2 outputs 48kHz natively. vllm-omni stream PCM has no header —
        // vllm-omni/.../serving_speech.py:1490 defaults to 24000 but gets overridden
        // by model sr at runtime. 48000 is the verified value (matches legacy wav).
        private const val SAMPLE_RATE = 48000
        private const val READ_BUFFER_BYTES = 8192
        private const val MIN_BUFFER_CHUNKS = 2
        // Adapter chunks are ~15360B = 160ms @ 48kHz mono s16le.
        private const val CHUNK_SIZE_BYTES = 15360
        // Fade ramps to avoid clicks at stream start/end.
        private const val FADE_IN_MS = 10
        private const val FADE_OUT_MS = 30
    }

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var currentCall: okhttp3.Call? = null
    @Volatile private var currentThread: Thread? = null

    /**
     * Start streaming TTS for [text]. Returns immediately; audio plays on a worker thread.
     *
     * - [onFirstChunk] fires once on the first PCM write (use to cancel ResponseWatchdog).
     * - [onDone] fires with `true` on normal completion, NOT called on cancel() (caller-initiated).
     *   Called with `false` on adapter/HTTP error before any chunk arrived.
     *
     * If a previous stream is in flight, it is cancelled before starting this one.
     */
    @Synchronized
    fun playStream(
        text: String,
        voice: String,
        onFirstChunk: () -> Unit,
        onDone: (ok: Boolean) -> Unit
    ) {
        cancel()
        val thread = Thread({
            runStream(text, voice, onFirstChunk, onDone)
        }, "StreamingTtsPlayer").also { currentThread = it }
        thread.start()
    }

    /**
     * Cancel any in-flight stream. Safe to call repeatedly; no-op if nothing playing.
     * Does NOT call onDone — caller-initiated cancel is silent.
     */
    @Synchronized
    fun cancel() {
        currentCall?.cancel()
        currentThread?.interrupt()
    }

    private fun runStream(
        text: String,
        voice: String,
        onFirstChunk: () -> Unit,
        onDone: (ok: Boolean) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        var audioTrack: AudioTrack? = null
        var firstChunkSent = false
        var anyChunkReceived = false

        try {
            // 1. Build POST request body
            val body = JSONObject().apply {
                put("input", text)
                put("voice", voice)
                put("response_format", "pcm")
                put("stream", true)
            }
            val request = Request.Builder()
                .url(endpointUrl)
                .post(body.toString().toRequestBody(jsonType))
                .build()

            Log.i(TAG, "Connecting (text=${text.length} chars)")

            // 2. Execute (blocking) — OkHttp auto-decodes chunked transfer
            val call = client.newCall(request)
            currentCall = call
            val response = call.execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code} — aborting")
                response.close()
                onDone(false)
                return
            }

            // 3. Initialize AudioTrack (before first chunk so write() can start immediately)
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf, CHUNK_SIZE_BYTES * MIN_BUFFER_CHUNKS)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            Log.i(TAG, "AudioTrack ready (buffer=${bufferSize}B)")

            // 4. Stream loop: read PCM chunk → write to AudioTrack
            val source = response.body?.byteStream() ?: run {
                Log.e(TAG, "Empty response body")
                response.close()
                onDone(false)
                return
            }
            val readBuf = ByteArray(READ_BUFFER_BYTES)
            // Pending buffer holds bytes not yet written. s16le requires 2-byte alignment,
            // so we round down to even on each write and carry any odd byte to next iter.
            // Without this, a single odd-length read shifts every subsequent sample by
            // 1 byte → persistent distortion (sounds like continuous static/clicking).
            val pending = ByteArray(READ_BUFFER_BYTES + 2)
            var pendingLen = 0
            var totalBytes = 0
            // Pre-buffer: accumulate at least CHUNK_SIZE_BYTES before play() to avoid underrun pops.
            val preBufferTarget = CHUNK_SIZE_BYTES
            var preBuffered = 0
            var started = false
            // Track last sample value (s16le) for tail fade-out.
            var lastSample: Short = 0
            // Fade-in length in bytes (16-bit mono = 2 bytes/sample)
            val fadeInBytes = SAMPLE_RATE * FADE_IN_MS / 1000 * 2
            var fadeApplied = false
            while (true) {
                val n = source.read(readBuf)
                if (n <= 0) break
                anyChunkReceived = true
                if (!firstChunkSent) {
                    firstChunkSent = true
                    val elapsed = System.currentTimeMillis() - t0
                    Log.i(TAG, "First chunk: ${n}B at ${elapsed}ms")
                    onFirstChunk()
                }
                // Append to pending buffer.
                System.arraycopy(readBuf, 0, pending, pendingLen, n)
                pendingLen += n
                // Round down to even (sample-aligned) for write.
                val writeLen = pendingLen and 0x7FFFFFFE
                if (writeLen == 0) continue
                // Apply fade-in once on the first sample-aligned write.
                if (!fadeApplied) {
                    applyFadeIn(pending, writeLen, fadeInBytes)
                    fadeApplied = true
                }
                // Track last written sample for tail fade-out.
                val lo = pending[writeLen - 2].toInt() and 0xFF
                val hi = pending[writeLen - 1].toInt() and 0xFF
                lastSample = ((hi shl 8) or lo).toShort()
                audioTrack.write(pending, 0, writeLen, AudioTrack.WRITE_BLOCKING)
                totalBytes += writeLen
                // Carry odd byte (if any) to the front of pending.
                val carry = pendingLen - writeLen
                if (carry > 0) {
                    pending[0] = pending[writeLen]
                }
                pendingLen = carry
                if (!started) {
                    preBuffered += writeLen
                    if (preBuffered >= preBufferTarget) {
                        audioTrack.play()
                        started = true
                        Log.i(TAG, "Playback started after pre-buffering ${preBuffered}B")
                    }
                }
            }
            // Edge case: stream shorter than pre-buffer target — start now
            if (!started && totalBytes > 0) {
                audioTrack.play()
                Log.i(TAG, "Playback started (short stream, ${totalBytes}B)")
            }
            // Tail fade-out: ramp last sample value down to 0 to avoid end-of-stream click.
            if (totalBytes > 0) {
                writeFadeOutTail(audioTrack, lastSample, FADE_OUT_MS)
            }

            // 5. Normal completion
            val elapsed = System.currentTimeMillis() - t0
            val audioSec = totalBytes / (SAMPLE_RATE * 2.0)
            Log.i(TAG, "Stream done: ${totalBytes}B (${String.format("%.2f", audioSec)}s audio), ${elapsed}ms total")
            response.close()
            onDone(true)

        } catch (e: IOException) {
            // Call.cancel() OR network error
            Log.i(TAG, "Stream cancelled/errored: ${e.javaClass.simpleName}: ${e.message}")
            // If we already started playing, treat as partial-success (don't trigger onDone
            // fallback). If never got a chunk, signal failure so caller can recover.
            if (anyChunkReceived) {
                // Already notified watchdog.cancel via onFirstChunk — don't re-arm
            } else {
                onDone(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected: ${e.javaClass.simpleName}: ${e.message}")
            if (!anyChunkReceived) onDone(false)
        } finally {
            try { audioTrack?.stop() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            currentCall = null
            currentThread = null
        }
    }

    /**
     * Linear fade-in applied to the first [fadeBytes] of a PCM s16le mono buffer.
     * Sample amplitude ramps from 0 → original over the window.
     */
    private fun applyFadeIn(buffer: ByteArray, length: Int, fadeBytes: Int) {
        val bytesToFade = minOf(fadeBytes, length) and 0x7FFFFFFE // force even (sample-aligned)
        var i = 0
        while (i < bytesToFade) {
            val ratio = (i + 2).toFloat() / bytesToFade
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val raw = ((hi shl 8) or lo)
            val s = raw.toShort().toInt()
            val faded = (s * ratio).toInt()
            buffer[i] = (faded and 0xFF).toByte()
            buffer[i + 1] = ((faded shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /**
     * Synthesize a short fade-out tail starting from [fromSample] down to 0 and write it.
     * Prevents click when the last written sample is non-zero at end-of-stream.
     */
    private fun writeFadeOutTail(audioTrack: AudioTrack, fromSample: Short, durationMs: Int) {
        val samples = SAMPLE_RATE * durationMs / 1000
        if (samples <= 0) return
        val buffer = ByteArray(samples * 2)
        val start = fromSample.toInt()
        var i = 0
        while (i < samples) {
            val ratio = 1f - (i.toFloat() / samples)
            val v = (start * ratio).toInt()
            buffer[i * 2] = (v and 0xFF).toByte()
            buffer[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            i++
        }
        try {
            audioTrack.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
        } catch (_: Exception) {}
    }
}
