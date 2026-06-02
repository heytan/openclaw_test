package com.openclaw.car.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.openclaw.car.OpenClawApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMessage(
    val role: String,
    val text: String,
    val source: String = ""
)

class GatewayClient {

    companion object {
        private const val TAG = "${OpenClawApp.TAG}.GatewayClient"
        private const val SESSIONS_DIR = "/data/local/tmp/openclaw-home/.openclaw/agents/main/sessions"
        private const val POLL_INTERVAL = 2000L

        val instance = GatewayClient()
    }

    private var messageListener: ((ChatMessage) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastLineCounts = mutableMapOf<String, Int>()
    private var running = false

    fun setMessageListener(listener: (ChatMessage) -> Unit) {
        messageListener = listener
    }

    fun connect() {
        if (running) return
        running = true
        handler.post(pollRunnable)
        Log.i(TAG, "Session file monitor started")
    }

    fun disconnect() {
        running = false
        handler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                pollSessions()
            } catch (e: Exception) {
                Log.w(TAG, "Poll error: ${e.message}")
            }
            handler.postDelayed(this, POLL_INTERVAL)
        }
    }

    private fun pollSessions() {
        val dir = File(SESSIONS_DIR)
        if (!dir.exists()) return

        val sessionFiles = dir.listFiles { f ->
            f.name.endsWith(".jsonl") && !f.name.contains("trajectory") && !f.name.contains("checkpoint") && !f.name.contains("path")
        } ?: return

        for (file in sessionFiles) {
            val path = file.absolutePath
            val lines = file.readLines()
            val prevCount = lastLineCounts[path] ?: 0

            if (lines.size > prevCount) {
                for (i in prevCount until lines.size) {
                    parseLine(lines[i])
                }
            }
            lastLineCounts[path] = lines.size
        }

        // Clean up old session entries
        val currentPaths = sessionFiles.map { it.absolutePath }.toSet()
        lastLineCounts.keys.removeAll { it !in currentPaths }
    }

    private fun parseLine(line: String) {
        try {
            val json = JSONObject(line)
            val type = json.optString("type", "")
            if (type != "message") return

            val message = json.optJSONObject("message") ?: return
            val role = message.optString("role", "")
            val content = message.opt("content")
            val text = extractText(content)
            if (text.isEmpty()) return

            // Filter: only show user messages
            if (role == "user") {
                val cleaned = cleanUserText(text)
                if (cleaned.isNotEmpty()) {
                    notifyListener(ChatMessage("user", cleaned))
                }
            }
        } catch (_: Exception) {}
    }

    private fun extractText(content: Any?): String {
        return when (content) {
            is String -> content
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i)
                    if (item?.optString("type") == "text") {
                        sb.append(item.optString("text", "")).append("\n")
                    }
                }
                sb.toString().trim()
            }
            else -> ""
        }
    }

    private fun cleanUserText(text: String): String {
        // Strip timestamp prefix like [Fri 2026-05-29 15:48 GMT+8]
        var cleaned = text.replace(Regex("^\\[.*?\\]\\s*"), "")

        // Feishu voice messages embed metadata before the actual text:
        // "Conversation info (untrusted metadata):\n```json{...}```\n\nSender (untrusted metadata):\n```json{...}```\n\n[message_id: ...]\nSenderName: actual text"
        if (cleaned.contains("Conversation info")) {
            // Take the last line after "SenderName: " pattern
            val lines = cleaned.lines().filter { it.isNotBlank() }
            val lastLine = lines.lastOrNull() ?: ""
            // Pattern: "Name: actual message"
            val colonIdx = lastLine.indexOf(':')
            if (colonIdx > 0) {
                cleaned = lastLine.substring(colonIdx + 1).trim()
            } else {
                cleaned = lastLine
            }
        }

        return cleaned.trim()
    }

    private fun notifyListener(msg: ChatMessage) {
        handler.post {
            messageListener?.invoke(msg)
        }
    }
}
