package com.openclaw.car.audio

import android.util.Log
import com.openclaw.car.OpenClawApp
import java.io.File
import java.io.RandomAccessFile

class BydAsrMonitor(
    private val onAsrResult: (String) -> Unit
) {

    companion object {
        private const val TAG = "${OpenClawApp.TAG}.BydAsr"
        private const val LOG_FILE = "/data/local/tmp/openclaw-home/asr_logcat.txt"
        private const val PID_FILE = "/data/local/tmp/asr_logcat.pid"
        private const val SCRIPT = "/data/local/tmp/asr_monitor.sh"
    }

    private var thread: Thread? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        // Launch background logcat via su script (detached from Java Process)
        startLogcatDaemon()
        thread = Thread({ monitorLoop() }, "BydAsrMonitor").apply { start() }
        Log.i(TAG, "ASR monitor started")
    }

    fun stop() {
        running = false
        stopLogcatDaemon()
        thread?.interrupt()
        thread = null
        Log.i(TAG, "ASR monitor stopped")
    }

    private fun startLogcatDaemon() {
        try {
            // Fire and forget — su stdout pipe issues can cause waitFor to block
            Runtime.getRuntime().exec(arrayOf("su", "-c", "sh $SCRIPT"))
            Log.i(TAG, "Background logcat daemon launch requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start logcat daemon: ${e.message}")
        }
    }

    private fun stopLogcatDaemon() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "kill $(cat $PID_FILE) 2>/dev/null; rm -f $PID_FILE"))
        } catch (_: Exception) {}
    }

    private fun monitorLoop() {
        val logFile = File(LOG_FILE)
        // Wait for file to exist
        var waited = 0
        while (running && !logFile.exists() && waited < 50) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
            waited++
        }
        if (!logFile.exists()) {
            Log.e(TAG, "Log file never appeared: $LOG_FILE")
            return
        }

        Log.i(TAG, "Tailing $LOG_FILE")
        var filePos = logFile.length() // Start from current end
        val raf = RandomAccessFile(logFile, "r")
        raf.seek(filePos)

        while (running) {
            try {
                val len = raf.length()
                if (len > filePos) {
                    raf.seek(filePos)
                    var line = raf.readLine()
                    while (line != null) {
                        val decoded = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                        if (decoded.contains("rawText") || decoded.contains("AsrResult")) {
                            val text = extractAsrText(decoded)
                            if (text != null) {
                                Log.i(TAG, "ASR intercepted: $text")
                                onAsrResult(text)
                            }
                        }
                        filePos = raf.filePointer
                        line = raf.readLine()
                    }
                } else if (len < filePos) {
                    filePos = 0L
                    raf.seek(0)
                }
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Tail error: ${e.message}")
            }
        }
        try { raf.close() } catch (_: Exception) {}
    }

    private fun extractAsrText(line: String?): String? {
        if (line == null) return null
        val rawTextMatch = Regex(""""rawText":"([^"]+)"""").find(line)
        if (rawTextMatch != null) {
            val text = rawTextMatch.groupValues[1]
            if (text.isNotEmpty()) return text
        }
        val normalTextMatch = Regex(""""normal_text":"([^"]+)"""").find(line)
        if (normalTextMatch != null) {
            return normalTextMatch.groupValues[1]
        }
        return null
    }
}
