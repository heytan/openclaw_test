package com.openclaw.car.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.openclaw.car.OpenClawApp
import com.openclaw.car.R
import java.util.LinkedList

class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "${OpenClawApp.TAG}.Bubble"
        private const val AUTO_COLLAPSE_MS = 5000L
        private const val MAX_MESSAGES = 5

        var instance: FloatingBubbleService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private var collapsedView: View? = null
    private var expandedView: View? = null
    private var isExpanded = false
    private var lastPosX = 0
    private var lastPosY = 0
    private var unreadCount = 0
    private val messages = LinkedList<ChatMessage>()
    private val handler = Handler(Looper.getMainLooper())

    private val collapseRunnable = Runnable { collapse() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showCollapsed()
        GatewayClient.instance.setMessageListener { msg -> onNewMessage(msg) }
        Log.i(TAG, "Floating bubble created")
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(collapseRunnable)
        removeViews()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onNewMessage(msg: ChatMessage) {
        handler.post {
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = getScreenWidth() - 60
            y = lastPosY
        }

        setupDrag(expandedView!!, params)
        expandedView!!.findViewById<TextView>(R.id.btn_collapse).setOnClickListener {
            handler.removeCallbacks(collapseRunnable)
            collapse()
        }
        expandedView!!.findViewById<View>(R.id.btn_config).setOnClickListener {
            val intent = Intent(this@FloatingBubbleService, Class.forName("com.openclaw.car.MainActivity"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        updateExpandedContent()
        windowManager.addView(expandedView, params)
        unreadCount = 0
    }

    private fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        removeExpanded()
        showCollapsed()
    }

    private fun updateExpandedContent() {
        val view = expandedView ?: return
        val tvMessage = view.findViewById<TextView>(R.id.tv_message)
        val last = messages.lastOrNull()
        tvMessage.text = last?.text ?: "暂无消息"
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
}
