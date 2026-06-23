package com.openclaw.car.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.text.method.ScrollingMovementMethod
import com.openclaw.car.MainActivity
import com.openclaw.car.OpenClawApp
import com.openclaw.car.R
import com.openclaw.car.agenui.CardSnapshotBus
import com.openclaw.car.audio.AudioRecorder
import com.openclaw.car.audio.BydAsrMonitor
import com.openclaw.car.audio.FillerAudioPlayer
import com.openclaw.car.audio.ResponseWatchdog
import com.openclaw.car.audio.StreamingTtsPlayer
import com.openclaw.car.audio.TtsAudioPlayer
import com.openclaw.car.fragment.AGenUIFragment
import com.openclaw.car.util.PreferenceHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedList
import java.util.concurrent.TimeUnit

class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "${OpenClawApp.TAG}.Bubble"
        private const val AUTO_COLLAPSE_MS = 5000L
        private const val MAX_MESSAGES = 5
        private const val GATEWAY_URL = "http://127.0.0.1:18801/v1/chat/completions"
        private const val GATEWAY_TOKEN = "fe3936a8d8dafeec8efb6d801863eb00c4c08298555a4817"
        private const val ASR_URL = "http://172.20.10.5:8090/v1/audio/transcriptions"
        private const val TTS_URL = "http://172.20.10.5:8091/v1/audio/speech"
        private const val A2UI_DEBOUNCE_MS = 400L
        private val A2UI_DEBOUNCE_TOKEN = Any()

        var instance: FloatingBubbleService? = null
            private set
    }

    private var currentTurn: ConversationTurn? = null

    private val gatewayClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private lateinit var windowManager: WindowManager
    private var collapsedView: View? = null
    private var expandedView: View? = null
    private var isExpanded = false
    private var lastPosX = 0
    private var lastPosY = 0
    /** Expanded bubble's WindowManager params — needed so drag can move the interactive card with it. */
    private var expandedParams: WindowManager.LayoutParams? = null
    /** Offset from the expanded bubble's top-left to the card slot's screen pos, captured at launch.
     *  Lets the drag handler reposition the InteractiveCardActivity card without re-reading layout. */
    private var cardSlotOffsetX = 0
    private var cardSlotOffsetY = 0
    private var unreadCount = 0
    private val messages = LinkedList<ChatMessage>()
    private val handler = Handler(Looper.getMainLooper())
    private var pptRecorder: AudioRecorder? = null
    private var asrMonitor: BydAsrMonitor? = null
    private val ttsPlayer = TtsAudioPlayer()
    private val fillerPlayer = FillerAudioPlayer()
    private val responseWatchdog = ResponseWatchdog(fillerPlayer)
    private val streamingTtsPlayer = StreamingTtsPlayer(TTS_URL, gatewayClient)

    private val collapseRunnable = Runnable { collapse() }
    private var recordingAnimator: ValueAnimator? = null
    private var isRecording = false
    private val normalBgColor = 0xE61E293B.toInt()
    private val recordingBgColor = 0xE63B82F6.toInt()

    private var latestSnapshot: Bitmap? = null
    private var displayedSnapshot: Bitmap? = null
    private val snapshotListener: (Bitmap) -> Unit = { bmp ->
        handler.post {
            val old = latestSnapshot
            latestSnapshot = bmp
            old?.recycle()
            refreshSnapshotUi()
        }
    }

    // 新一轮对话开始时调用：清掉上一轮卡片快照，避免悬浮栏里"上一卡 + 这一文本"错位
    private fun clearSnapshotForNewTurn() {
        handler.post {
            val old = latestSnapshot
            latestSnapshot = null
            old?.recycle()
            refreshSnapshotUi()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Auto-grant overlay permission via appops (survives reinstalls but not reboots)
        try {
            Runtime.getRuntime().exec(arrayOf("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow")).waitFor()
        } catch (_: Exception) {}
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showCollapsed()
        GatewayClient.instance.setMessageListener { msg -> onNewMessage(msg) }
        GatewayClient.instance.setA2UIListener { msg -> onA2UIMessage(msg) }
        asrMonitor = BydAsrMonitor { text -> onBydAsrResult(text) }
        asrMonitor?.start()
        ttsPlayer.start()
        ttsPlayer.onPlaybackStarted = { handler.post { responseWatchdog.cancel() } }
        // File-based TTS 到达 → 打断 streaming TTS / filler。注意两件事：
        //   1. 必须同步执行（不能 handler.post）—playFile 紧接着会创建并 start 新
        //      MediaPlayer，post 会把打断延迟到新 MediaPlayer 已开播之后，反而把它停掉。
        //   2. 不能调 ttsPlayer.stopCurrentPlayback() — 那会停掉本次 playFile 刚启动的
        //      新 MediaPlayer。playFile 自身的 stopPlayback() 已经处理同类旧 MediaPlayer。
        ttsPlayer.onBeforePlayback = {
            streamingTtsPlayer.cancel()
            fillerPlayer.stop()
        }
        OpenClawApp.responseWatchdog = responseWatchdog
        CardSnapshotBus.subscribe(snapshotListener)
        Log.i(TAG, "Floating bubble created (filler pool available=${fillerPlayer.hasClips()})")
    }

    override fun onDestroy() {
        instance = null
        OpenClawApp.responseWatchdog = null
        CardSnapshotBus.unsubscribe(snapshotListener)
        latestSnapshot?.recycle()
        displayedSnapshot?.recycle()
        latestSnapshot = null
        displayedSnapshot = null
        asrMonitor?.stop()
        ttsPlayer.stop()
        streamingTtsPlayer.cancel()
        fillerPlayer.stop()
        handler.removeCallbacks(collapseRunnable)
        removeViews()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onNewMessage(msg: ChatMessage) {
        handler.post {
            Log.i(TAG, "onNewMessage: role=${msg.role}, text=${msg.text.take(50)}, currentTurn=$currentTurn")
            // 只有 assistant 消息才更新 AI 回复
            if (msg.role == "assistant") {
                currentTurn = currentTurn?.copy(aiResponse = msg.text)
            }
            Log.i(TAG, "onNewMessage: after update currentTurn=$currentTurn")

            messages.add(msg)
            if (messages.size > MAX_MESSAGES) messages.removeFirst()
            unreadCount++
            if (!isExpanded) {
                updateBadge()
                expand()
                handler.removeCallbacks(collapseRunnable)
                handler.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
            } else {
                updateExpandedContent()
            }
        }
    }

    private fun onA2UIMessage(msg: A2UIMessage) {
        handler.post {
            val fragment = AGenUIFragment.instance
            // MainActivity 不在前台时，fragment 即使存活，SurfaceManager 的 native 渲染也会
            // 被 activity 的 paused 状态拖累：cardView 没法正常布局，scheduleSnapshot 在
            // 800ms 后读到 width/height=0 静默失败，悬浮球永远收不到快照。
            // 因此 app 在后台时一律走 BackgroundCardRenderActivity 离屏渲染。
            val mainForeground = MainActivity.isForeground
            if (fragment != null && mainForeground) {
                val pending = msg.content
                handler.removeCallbacksAndMessages(A2UI_DEBOUNCE_TOKEN)
                handler.postDelayed({
                    if (AGenUIFragment.instance != null && MainActivity.isForeground) {
                        AGenUIFragment.instance?.receiveA2UI(pending)
                    } else {
                        // debounce 期间 app 切到后台 / fragment 被销毁，走兜底
                        startBackgroundRender(pending)
                    }
                    switchToA2UITab()
                    Log.i(TAG, "A2UI message routed (fragment, foreground), length=${pending.length}")
                }, A2UI_DEBOUNCE_MS)
            } else {
                // App 在后台 / AGenUIFragment 不存在：起透明 BackgroundCardRenderActivity
                // 离屏渲染 → 截图 → CardSnapshotBus → 悬浮球显示
                startBackgroundRender(msg.content)
                Log.i(TAG, "A2UI message routed via background activity, length=${msg.content.length}")
            }
        }
    }

    /**
     * 启动 BackgroundCardRenderActivity 离屏渲染 A2UI JSON。
     * 有 SYSTEM_ALERT_WINDOW 权限，可从后台启动 Activity。
     */
    private fun startBackgroundRender(json: String) {
        val intent = Intent(this, com.openclaw.car.agenui.BackgroundCardRenderActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(com.openclaw.car.agenui.BackgroundCardRenderActivity.EXTRA_A2UI_JSON, json)
        }
        startActivity(intent)
    }

    private fun onBydAsrResult(text: String) {
        handler.post {
            if (!isExpanded) {
                expand()
            }
            handler.removeCallbacks(collapseRunnable)
            // 新一轮输入：清掉上一轮的回复和卡片，避免悬浮栏里"上一回复/卡片"残留
            currentTurn = ConversationTurn(userInput = text, aiResponse = "")
            clearSnapshotForNewTurn()
            updateExpandedContent()
            handler.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
        }
        sendTextToGateway(text)
        responseWatchdog.arm()
    }

    private fun toggleApp() {
        val main = MainActivity.instance
        if (main != null && MainActivity.isForeground) {
            main.runOnUiThread {
                main.moveTaskToBack(true)
            }
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }

    private fun switchToA2UITab() {
        val main = MainActivity.instance
        if (main != null) {
            main.runOnUiThread {
                main.viewPager.currentItem = 4
            }
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }

    @SuppressLint("InflateParams")
    private fun showCollapsed() {
        removeViews()
        collapsedView = LayoutInflater.from(this).inflate(R.layout.floating_bubble_collapsed, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = getScreenWidth() - 60
            y = getScreenHeight() / 2
            lastPosX = x
            lastPosY = y
        }

        setupDrag(collapsedView!!, params)
        collapsedView!!.setOnClickListener { expand() }
        windowManager.addView(collapsedView, params)
    }

    @SuppressLint("InflateParams")
    private fun expand() {
        if (isExpanded) return
        isExpanded = true

        removeCollapsed()
        expandedView = LayoutInflater.from(this).inflate(R.layout.floating_bubble_expanded, null)

        // 计算并设置最大宽度（屏幕宽度的30%）
        val screenWidth = getScreenWidth()
        val maxBubbleWidth = (screenWidth * 0.5).toInt()

        val params = WindowManager.LayoutParams(
            maxBubbleWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - maxBubbleWidth
            y = lastPosY
        }

        // 设置AI回复的垂直滚动行为
        val tvAiResponse = expandedView!!.findViewById<TextView>(R.id.tv_ai_response)
        tvAiResponse.movementMethod = ScrollingMovementMethod()

        setupDrag(expandedView!!, params)
        expandedParams = params
        expandedView!!.findViewById<TextView>(R.id.btn_collapse).setOnClickListener {
            handler.removeCallbacks(collapseRunnable)
            collapse()
        }
        expandedView!!.findViewById<View>(R.id.btn_config).setOnClickListener {
            toggleApp()
        }

        // App icon click: toggle recording
        val btnAppIcon = expandedView!!.findViewById<ImageView>(R.id.btn_app_icon)
        btnAppIcon.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        updateExpandedContent()
        windowManager.addView(expandedView, params)
        unreadCount = 0
        refreshSnapshotUi()
    }

    /**
     * Launches [InteractiveCardActivity] positioned over the bubble's snapshot slot, then hides
     * the static snapshot ImageView (INVISIBLE, not GONE — keeps the slot's space so the live
     * card aligns and the bubble doesn't reflow). The live card renders behind the bubble overlay
     * and shows through the now-empty slot; touches pass through the non-clickable, FLAG_NOT_FOCUSABLE
     * overlay to the live card's buttons.
     */
    private fun collapse() {
        if (!isExpanded) return
        if (isRecording) stopRecording()
        isExpanded = false
        expandedParams = null
        cardSlotOffsetX = 0
        cardSlotOffsetY = 0
        removeExpanded()
        showCollapsed()
    }

    private fun updateExpandedContent() {
        val view = expandedView ?: return

        val tvUserInput = view.findViewById<TextView>(R.id.tv_user_input)
        val tvAiResponse = view.findViewById<TextView>(R.id.tv_ai_response)

        Log.i(TAG, "updateExpandedContent: isRecording=$isRecording, currentTurn=$currentTurn")

        when {
            isRecording -> {
                // 录音中：ASR 槽变状态条，AI 方框保留上次回复（关键：不动 tvAiResponse）
                tvUserInput.text = "• 正在录音…"
                tvUserInput.visibility = View.VISIBLE
                tvUserInput.isSelected = false
                tvAiResponse.visibility = View.VISIBLE
            }
            currentTurn != null -> {
                // 正常对话：显示 "你: ASR" 标签形式 + AI 方框内容
                val span = SpannableString("你: ${currentTurn!!.userInput}")
                span.setSpan(
                    ForegroundColorSpan(0x80FFFFFF.toInt()),
                    0, 3,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                tvUserInput.setText(span)
                tvUserInput.visibility = View.VISIBLE
                tvUserInput.isSelected = true

                val aiText = currentTurn!!.aiResponse
                if (aiText.isEmpty()) {
                    tvAiResponse.visibility = View.GONE
                } else {
                    tvAiResponse.text = aiText
                    tvAiResponse.visibility = View.VISIBLE
                    // 新回复到达时重置到顶部，避免长文本默认滚到底看不到开头
                    tvAiResponse.scrollTo(0, 0)
                }
            }
            else -> {
                // fallback：显示历史最后一条
                val last = messages.lastOrNull()
                if (last != null) {
                    tvUserInput.visibility = View.GONE
                    tvAiResponse.text = last.text
                    tvAiResponse.visibility = View.VISIBLE
                    tvAiResponse.scrollTo(0, 0)
                } else {
                    tvUserInput.text = "暂无消息"
                    tvUserInput.visibility = View.VISIBLE
                    tvUserInput.isSelected = false
                    tvAiResponse.visibility = View.GONE
                }
            }
        }
    }


    private fun refreshSnapshotUi() {
        val view = expandedView ?: return
        val card = view.findViewById<android.widget.FrameLayout>(R.id.snapshot_card) ?: return
        val iv = view.findViewById<ImageView>(R.id.snapshot_image) ?: return
        val raw = latestSnapshot
        if (raw == null) {
            card.visibility = View.GONE
            iv.setImageDrawable(null)
            displayedSnapshot?.recycle()
            displayedSnapshot = null
            return
        }

        // Bubble width is fixed at screenWidth * 0.5 (see expand()). adjustViewBounds
        // is unreliable for ImageView inside WindowManager — pre-scale the bitmap
        // ourselves so ImageView just displays it 1:1.
        val density = resources.displayMetrics.density
        val bubbleWidthPx = (resources.displayMetrics.widthPixels * 0.5).toInt()
        val targetWidth = bubbleWidthPx - (20 * density).toInt()  // root + card + image horizontal padding
        if (targetWidth <= 0 || raw.width <= 0) {
            iv.setImageBitmap(raw)
            card.visibility = View.VISIBLE
            return
        }
        val ratio = targetWidth.toFloat() / raw.width.toFloat()
        val targetHeight = (raw.height * ratio).toInt().coerceAtLeast(1)
        val scaled = if (targetWidth != raw.width || targetHeight != raw.height) {
            Bitmap.createScaledBitmap(raw, targetWidth, targetHeight, true)
        } else raw
        displayedSnapshot?.takeIf { it !== scaled && it !== raw }?.recycle()
        displayedSnapshot = scaled
        iv.setImageBitmap(scaled)
        card.visibility = View.VISIBLE
        Log.i(TAG, "Snapshot rendered: raw=${raw.width}x${raw.height} scaled=${scaled.width}x${scaled.height}")
    }

    private fun startRecording() {
        val view = expandedView ?: return
        handler.removeCallbacks(collapseRunnable)

        // 用户开始说话 → 立刻打断当前正在播的 TTS / filler（否则麦克风和音频同时活跃，
        // 用户听不到自己说话的反馈，且新 ASR 回来后 TTS 会重叠）。音乐不掐。
        stopTtsAndFiller()

        pptRecorder = AudioRecorder()
        val started = pptRecorder!!.start()
        if (!started) {
            pptRecorder = null
            Toast.makeText(this, "录音启动失败，麦克风被占用", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        val icon = view.findViewById<ImageView>(R.id.btn_app_icon)
        icon.setImageResource(R.drawable.ic_ppt_active)
        icon.setPadding(0, 0, 0, 0)

        // 由 updateExpandedContent 处理文字区：ASR 槽变"正在录音…"，AI 方框保留上次回复
        updateExpandedContent()
        view.findViewById<View>(R.id.btn_config).visibility = View.GONE
        view.findViewById<View>(R.id.btn_collapse).visibility = View.GONE
        view.findViewById<View>(R.id.divider).visibility = View.GONE

        startRecordingAnimation()
        Log.i(TAG, "PPT: recording started")
    }

    private fun stopRecording() {
        val view = expandedView ?: return
        val recorder = pptRecorder ?: return

        val (wav, durationMs) = recorder.stop()
        pptRecorder = null
        isRecording = false

        stopRecordingAnimation()

        val icon = view.findViewById<ImageView>(R.id.btn_app_icon)
        icon.setImageResource(R.mipmap.ic_launcher)
        icon.setPadding(0, 0, 0, 0)

        view.findViewById<View>(R.id.btn_config).visibility = View.VISIBLE
        view.findViewById<View>(R.id.btn_collapse).visibility = View.VISIBLE
        view.findViewById<View>(R.id.divider).visibility = View.VISIBLE

        updateExpandedContent()

        if (wav.isNotEmpty()) {
            sendAudioToGateway(wav, durationMs)
            responseWatchdog.arm()
        }
    }

    /**
     * 打断当前正在播的 TTS / filler。三种触发场景：
     *   1. 用户开始录音（startRecording）— 麦克风和音频不能同时活跃
     *   2. 新一轮 file-based TTS 到达（TtsAudioPlayer.onBeforePlayback）
     *   3. 新一轮 streaming TTS 触发（sendAudioToGateway 内 stream 分支）
     * 音乐不掐：用户听音乐是主内容，每次 PTT 都掐会很烦；标准 car UX 是 duck 而不是 stop。
     */
    private fun stopTtsAndFiller() {
        streamingTtsPlayer.cancel()
        ttsPlayer.stopCurrentPlayback()
        fillerPlayer.stop()
    }

    private fun startRecordingAnimation() {
        val view = expandedView ?: return
        val bg = GradientDrawable()
        bg.cornerRadius = 28f * resources.displayMetrics.density
        view.background = bg

        recordingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val color = blendArgb(normalBgColor, recordingBgColor, fraction)
                bg.setColor(color)
            }
            start()
        }
    }

    private fun stopRecordingAnimation() {
        recordingAnimator?.cancel()
        recordingAnimator = null
        expandedView?.setBackgroundResource(R.drawable.bubble_bg_bar)
    }

    private fun blendArgb(color1: Int, color2: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        val a = (Color.alpha(color1) * inverse + Color.alpha(color2) * ratio).toInt()
        val r = (Color.red(color1) * inverse + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * inverse + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * inverse + Color.blue(color2) * ratio).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun updateBadge() {
        val badge = collapsedView?.findViewById<TextView>(R.id.tv_badge) ?: return
        if (unreadCount > 0) {
            badge.visibility = View.VISIBLE
            badge.text = if (unreadCount > 9) "9+" else unreadCount.toString()
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun stripNonSpoken(text: String): String {
        // Remove thinking/reasoning blocks
        var cleaned = text.replace(Regex("<think[\\s\\S]*?</think\\s*>", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("<reasoning[\\s\\S]*?</reasoning\\s*>", RegexOption.IGNORE_CASE), "")
        // Remove A2UI JSON and markdown code blocks. JSON 闭合符号行（]}} / }) / ) 等）
        // 也一律丢弃：LLM 偶尔把 JSON 数组/对象的收尾拆到独立行，留下纯括号行很丑。
        var inCodeBlock = false
        val bracketOnly = Regex("^[\\s\\{\\}\\[\\]\\(\\),,;:]+$")
        return cleaned.lines()
            .filter { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("```")) {
                    inCodeBlock = !inCodeBlock
                    return@filter false
                }
                if (inCodeBlock) return@filter false
                if (bracketOnly.matches(trimmed)) return@filter false
                trimmed.isNotEmpty() &&
                !trimmed.startsWith("{") &&
                !trimmed.startsWith("[") &&
                !trimmed.startsWith("]") &&
                !trimmed.startsWith("}")
            }
            .joinToString("\n")
            .trim()
            // 兜底：剥掉末尾可能粘连在自然语言行上的括号尾巴，例如 "今天晴朗。}}"
            .replace(Regex("[\\}\\]\\)]+$"), "")
            .trim()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) isDragging = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(view, params)
                }
                MotionEvent.ACTION_UP -> {
                    lastPosX = params.x
                    lastPosY = params.y
                    if (!isDragging) view.performClick()
                }
            }
            true
        }
    }

    private fun removeViews() {
        removeCollapsed()
        removeExpanded()
    }

    private fun removeCollapsed() {
        collapsedView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            collapsedView = null
        }
    }

    private fun removeExpanded() {
        expandedView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            expandedView = null
        }
    }

    private fun getScreenWidth(): Int {
        return (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds.width()
    }

    private fun getScreenHeight(): Int {
        return (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds.height()
    }

    private fun sendAudioToGateway(audioData: ByteArray, durationMs: Long = 0) {
        Thread {
            try {
                // Step 1: ASR — send audio to FunASR
                val asrBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("model", "sensevoice")
                    .addFormDataPart("file", "audio.m4a",
                        audioData.toRequestBody("audio/mp4".toMediaType()))
                    .build()

                val asrRequest = Request.Builder()
                    .url(ASR_URL)
                    .post(asrBody)
                    .build()

                Log.i(TAG, "PPT: sending audio to ASR, ${audioData.size} bytes")
                val asrResponse = gatewayClient.newCall(asrRequest).execute()
                val asrBodyStr = asrResponse.body?.string() ?: ""
                Log.i(TAG, "PPT: ASR response ${asrResponse.code}: ${asrBodyStr.take(300)}")

                if (!asrResponse.isSuccessful) {
                    Log.e(TAG, "PPT: ASR failed")
                    return@Thread
                }

                var asrText = JSONObject(asrBodyStr).optString("text", "").trim()
                // 显示版本：永远去掉方言检测标记（用户视角是噪音，看不到也无所谓）
                val displayAsrText = asrText.replace(Regex("\\[系统检测到[^]]*]"), "").trim()
                // 短音频 (<1s) FireRedLID 不可靠，连送给 LLM 也一起去掉
                if (durationMs < 1000) {
                    asrText = displayAsrText
                }
                if (asrText.isEmpty()) {
                    Log.w(TAG, "PPT: ASR returned empty text")
                    return@Thread
                }
                Log.i(TAG, "PPT: ASR text: $asrText (display: $displayAsrText)")

                // 新一轮输入：清掉上一轮的回复和卡片，避免悬浮栏里"上一回复/卡片"残留
                currentTurn = ConversationTurn(userInput = displayAsrText, aiResponse = "")
                clearSnapshotForNewTurn()
                handler.post { updateExpandedContent() }

                // Step 2: Send recognized text to Gateway
                val message = JSONObject().apply {
                    put("role", "user")
                    put("content", asrText)
                }
                val body = JSONObject().apply {
                    put("model", "openclaw")
                    put("messages", JSONArray().put(message))
                }

                val request = Request.Builder()
                    .url(GATEWAY_URL)
                    .addHeader("Authorization", "Bearer $GATEWAY_TOKEN")
                    .addHeader("x-openclaw-session-key", "agent:main:ppt-voice")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                Log.i(TAG, "PPT: sending text to gateway: $asrText")
                val response = gatewayClient.newCall(request).execute()
                val respBody = response.body?.string() ?: ""
                Log.i(TAG, "PPT: gateway response ${response.code}: ${respBody.take(500)}")
                response.close()

                // Step 3: Extract reply text and synthesize TTS
                val replyText = try {
                    val json = JSONObject(respBody)
                    val raw = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    stripNonSpoken(raw)
                } catch (_: Exception) { "" }

                if (replyText.isEmpty()) {
                    Log.w(TAG, "PPT: no reply text for TTS")
                    return@Thread
                }

                Log.i(TAG, "PPT: synthesizing TTS: ${replyText.take(100)}")

                // 更新当前对话轮次的AI回复
                currentTurn = currentTurn?.copy(aiResponse = replyText)
                handler.post { updateExpandedContent() }

                if (PreferenceHelper.getStreamTtsEnabled(applicationContext)) {
                    // === Stream path (experimental) ===
                    Log.i(TAG, "PPT: using STREAM TTS path")
                    // 先打断可能在播的 file-based TTS / filler。streamingTtsPlayer.playStream
                    // 内部也会 cancel() 自身，这里补上跨播放器的中断。
                    stopTtsAndFiller()
                    streamingTtsPlayer.playStream(
                        text = replyText,
                        voice = "default",
                        onFirstChunk = { responseWatchdog.cancel() },
                        onDone = { ok ->
                            Log.i(TAG, "PPT: stream TTS done ok=$ok")
                            // No fallback retry — if stream fails, sync POST likely fails too.
                            // Watchdog still armed if no firstChunk, filler will play.
                        }
                    )
                } else {
                    // === Legacy path (default) ===
                    val ttsBody = JSONObject().apply {
                        put("model", "tts")
                        put("input", replyText)
                        put("voice", "default")
                    }
                    val ttsRequest = Request.Builder()
                        .url(TTS_URL)
                        .addHeader("Content-Type", "application/json")
                        .post(ttsBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    val ttsResponse = gatewayClient.newBuilder()
                        .readTimeout(120, TimeUnit.SECONDS)
                        .build()
                        .newCall(ttsRequest)
                        .execute()

                    if (!ttsResponse.isSuccessful) {
                        Log.e(TAG, "PPT: TTS failed: ${ttsResponse.code}")
                        return@Thread
                    }

                    val audioBytes = ttsResponse.body?.bytes() ?: ByteArray(0)
                    if (audioBytes.isEmpty()) {
                        Log.w(TAG, "PPT: TTS returned empty audio")
                        return@Thread
                    }

                    Log.i(TAG, "PPT: TTS audio received: ${audioBytes.size} bytes, playing...")
                    playTtsAudio(audioBytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "PPT: pipeline failed: ${e.message}")
            }
        }.start()
    }

    private var pptMediaPlayer: android.media.MediaPlayer? = null

    private fun playTtsAudio(data: ByteArray) {
        try {
            responseWatchdog.cancel()

            pptMediaPlayer?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }

            val tempFile = File(cacheDir, "ppt_tts_response.mp3")
            tempFile.writeBytes(data)

            val mp = android.media.MediaPlayer()
            mp.setDataSource(tempFile.absolutePath)
            mp.setOnCompletionListener {
                Log.i(TAG, "PPT: TTS playback complete")
                it.release()
                if (pptMediaPlayer == mp) pptMediaPlayer = null
                tempFile.delete()
            }
            mp.setOnErrorListener { mp2, what, extra ->
                Log.e(TAG, "PPT: TTS playback error: what=$what extra=$extra")
                mp2.release()
                if (pptMediaPlayer == mp2) pptMediaPlayer = null
                tempFile.delete()
                true
            }
            mp.prepare()
            mp.start()
            pptMediaPlayer = mp
        } catch (e: Exception) {
            Log.e(TAG, "PPT: playTtsAudio failed: ${e.message}")
        }
    }

    private fun sendTextToGateway(text: String) {
        Thread {
            try {
                val message = JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                }
                val body = JSONObject().apply {
                    put("model", "openclaw")
                    put("messages", JSONArray().put(message))
                }

                val request = Request.Builder()
                    .url(GATEWAY_URL)
                    .addHeader("Authorization", "Bearer $GATEWAY_TOKEN")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                Log.i(TAG, "BYD ASR → Gateway: $text")
                val response = gatewayClient.newCall(request).execute()
                val respBody = response.body?.string() ?: ""
                Log.i(TAG, "Gateway response ${response.code}: ${respBody.take(300)}")
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "BYD ASR → Gateway failed: ${e.message}")
            }
        }.start()
    }
}
