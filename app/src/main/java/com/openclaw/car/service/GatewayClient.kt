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

data class A2UIMessage(
    val role: String,
    val content: String,
    val source: String = ""
)

data class ConversationTurn(
    val userInput: String = "",
    val aiResponse: String = ""
)

class GatewayClient {

    companion object {
        private const val TAG = "${OpenClawApp.TAG}.GatewayClient"
        private const val SESSIONS_DIR = "/data/local/tmp/openclaw-home/.openclaw/agents/main/sessions"
        private const val POLL_INTERVAL = 2000L

        val instance = GatewayClient()
    }

    private var messageListener: ((ChatMessage) -> Unit)? = null
    private var a2uiListener: ((A2UIMessage) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastLineCounts = mutableMapOf<String, Int>()
    private var running = false

    fun setMessageListener(listener: (ChatMessage) -> Unit) {
        messageListener = listener
    }

    fun setA2UIListener(listener: (A2UIMessage) -> Unit) {
        a2uiListener = listener
    }

    fun connect() {
        if (running) return
        // Snapshot current file sizes so we skip existing history
        val dir = File(SESSIONS_DIR)
        if (dir.exists()) {
            dir.listFiles { f ->
                f.name.endsWith(".jsonl") && !f.name.contains("trajectory") && !f.name.contains("checkpoint") && !f.name.contains("path")
            }?.forEach { file ->
                lastLineCounts[file.absolutePath] = file.readLines().size
            }
        }
        running = true
        handler.post(pollRunnable)
        Log.i(TAG, "Session file monitor started, skipped existing history")
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
                    if (!parseLine(lines[i])) {
                        // Parse failed (incomplete JSON from streaming) — don't advance past this line
                        lastLineCounts[path] = i
                        return
                    }
                }
            }
            lastLineCounts[path] = lines.size
        }

        // Clean up old session entries
        val currentPaths = sessionFiles.map { it.absolutePath }.toSet()
        lastLineCounts.keys.removeAll { it !in currentPaths }
    }

    /** Returns true if line was parsed (or skipped), false if JSON was incomplete */
    private fun parseLine(line: String): Boolean {
        val json: JSONObject
        try {
            json = JSONObject(line)
        } catch (_: Exception) {
            // Incomplete JSON — likely streaming in progress
            return false
        }

        try {
            val type = json.optString("type", "")
            if (type != "message") return true

            val message = json.optJSONObject("message") ?: return true
            val role = message.optString("role", "")
            val content = message.opt("content")
            val text = extractText(content)
            if (text.isEmpty()) return true

            when (role) {
                "user" -> {
                    val cleaned = cleanUserText(text)
                    if (cleaned.isNotEmpty()) {
                        notifyListener(ChatMessage("user", cleaned))
                    }
                }
                "assistant" -> {
                    if (isA2UIProtocol(text)) {
                        val a2uiJson = extractA2UILines(text)
                        if (a2uiJson.isNotEmpty()) {
                            notifyA2UI(A2UIMessage("assistant", a2uiJson))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return true
    }

    private fun isA2UIProtocol(text: String): Boolean {
        return text.contains("\"version\":\"v0.9\"")
    }

    private fun extractA2UILines(text: String): String {
        val results = mutableListOf<String>()
        val marker = "\"version\":\"v0.9\""
        var searchFrom = 0
        while (searchFrom < text.length) {
            val idx = text.indexOf(marker, searchFrom)
            if (idx < 0) break
            // Find the outermost { before the marker
            var start = text.lastIndexOf('{', idx)
            if (start < 0) { searchFrom = idx + marker.length; continue }
            // Find matching } across the entire text (multi-line)
            var depth = 0
            var foundEnd = -1
            for (i in start until text.length) {
                if (text[i] == '{') depth++
                else if (text[i] == '}') depth--
                if (depth == 0) { foundEnd = i; break }
            }
            if (foundEnd > start) {
                results.add(text.substring(start, foundEnd + 1))
                searchFrom = foundEnd + 1
            } else {
                searchFrom = idx + marker.length
            }
        }
        return results.joinToString("\n")
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

        // Strip dialect injection marker meant for LLM only
        cleaned = cleaned.replace(Regex("\\[系统检测到用户说的是[^]]*，请用[^]]*回复]\\s*"), "")

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

    private fun notifyA2UI(msg: A2UIMessage) {
        handler.post {
            a2uiListener?.invoke(msg)
        }
    }
}
