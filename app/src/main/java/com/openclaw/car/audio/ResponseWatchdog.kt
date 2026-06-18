package com.openclaw.car.audio

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.openclaw.car.OpenClawApp
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Arms a timeout when the user finishes a turn (speech recognized or PPT released).
 * If no real TTS playback arrives within [timeoutMs], plays a filler clip to avoid
 * silence. Cancelled when real TTS playback starts (either direct playTtsAudio in
 * FloatingBubbleService, or via TtsAudioPlayer's outbound polling).
 */
class ResponseWatchdog(
    private val fillerPlayer: FillerAudioPlayer,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "${OpenClawApp.TAG}.Watchdog"
        private const val DEFAULT_TIMEOUT_MS = 4000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val armed = AtomicBoolean(false)
    // 音色切换期间设为 true：watchdog 触发时跳过 filler（旧音色与新音色不一致，
    // 过渡期播"请稍等"会突兀）。自动在 autoResetMs 后复位，或在 cancel() 时
    // （真实 TTS 已开始播放，说明新音色就位）复位。
    private val suppressed = AtomicBoolean(false)

    private val timeoutRunnable = Runnable {
        armed.set(false)
        if (suppressed.get()) {
            Log.i(TAG, "Watchdog fired after ${timeoutMs}ms — suppressed (voice switching)")
            return@Runnable
        }
        Log.i(TAG, "Watchdog fired after ${timeoutMs}ms with no TTS — playing filler")
        fillerPlayer.playRandom()
    }

    private val resetSuppressedRunnable = Runnable {
        if (suppressed.compareAndSet(true, false)) {
            Log.i(TAG, "Watchdog filler suppression auto-reset after timeout")
        }
    }

    /**
     * 抑制 filler 播放。value=true 时可传 autoResetMs > 0 让 suppression 在指定时间后
     * 自动复位，防止用户没继续对话时一直抑制。value=false 立即复位并取消挂起的 auto-reset。
     */
    fun setSuppressed(value: Boolean, autoResetMs: Long = 0L) {
        handler.removeCallbacks(resetSuppressedRunnable)
        suppressed.set(value)
        if (value && autoResetMs > 0L) {
            handler.postDelayed(resetSuppressedRunnable, autoResetMs)
            Log.d(TAG, "Watchdog filler suppressed=true, auto-reset in ${autoResetMs}ms")
        } else {
            Log.d(TAG, "Watchdog filler suppressed=$value")
        }
    }

    fun arm() {
        handler.removeCallbacks(timeoutRunnable)
        armed.set(true)
        handler.postDelayed(timeoutRunnable, timeoutMs)
        Log.d(TAG, "Watchdog armed (${timeoutMs}ms)")
    }

    /**
     * Cancel any pending watchdog AND stop any filler currently playing.
     * Call this when real TTS playback begins so the filler doesn't bleed into it.
     * Also clears suppression: 真 TTS 到了说明新音色已就位，下次可以正常播 filler。
     */
    fun cancel() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(resetSuppressedRunnable)
        if (armed.getAndSet(false)) {
            Log.d(TAG, "Watchdog cancelled")
        }
        if (suppressed.compareAndSet(true, false)) {
            Log.i(TAG, "Watchdog filler suppression cleared (real TTS arrived)")
        }
        fillerPlayer.stop()
    }
}
