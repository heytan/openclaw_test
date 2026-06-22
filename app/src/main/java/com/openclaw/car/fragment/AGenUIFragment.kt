package com.openclaw.car.fragment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import com.amap.agenui.render.surface.ISurfaceManagerListener
import com.amap.agenui.render.surface.Surface
import com.amap.agenui.render.surface.SurfaceManager
import com.amap.agenui.render.surface.SurfaceSize
import com.google.android.material.card.MaterialCardView
import com.openclaw.car.OpenClawApp
import com.openclaw.car.R
import com.openclaw.car.agenui.AGenUIHelpers
import com.openclaw.car.agenui.CardSnapshotBus
import com.openclaw.car.music.BydMusicController
import com.openclaw.car.widget.FlowLayout
import org.json.JSONObject
import java.io.File

class AGenUIFragment : Fragment() {

    companion object {
        const val TAG = "${OpenClawApp.TAG}.AGenUI"
        var pendingA2UIData: String? = null
        var instance: AGenUIFragment? = null
            private set
        val cardJsonHistory = mutableListOf<String>()
    }

    private data class CardEntry(
        val surfaceManager: SurfaceManager,
        var surface: Surface?,
        val cardView: MaterialCardView
    )

    private val cards = mutableListOf<CardEntry>()
    private var cardsContainer: FlowLayout? = null
    private var cardsScroll: ScrollView? = null

    @Volatile
    private var cachedSurfaceSize: SurfaceSize? = null

    val currentSurface: Surface?
        get() = cards.lastOrNull()?.surface

    val cardCount: Int
        get() = cards.size

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_agenui, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        instance = this

        cardsContainer = view.findViewById(R.id.cards_container)
        cardsScroll = view.findViewById(R.id.cards_scroll)

        cardsContainer?.post {
            val flow = cardsContainer ?: return@post
            flow.columns = 2
            flow.spacing = dpToPx(20)
            val childW = flow.getChildWidth()
            val surfaceH = resources.displayMetrics.heightPixels.toFloat()
            cachedSurfaceSize = SurfaceSize(childW.toFloat(), surfaceH)
            Log.i(TAG, "Card size: ${childW}px x ${surfaceH.toInt()}px, spacing=${flow.spacing}px")
        }

        val initialData = pendingA2UIData
        pendingA2UIData = null
        if (initialData != null) {
            cardsContainer?.post {
                for (json in cardJsonHistory.toList()) {
                    renderCard(json)
                }
                receiveA2UI(initialData)
            }
        } else if (cardJsonHistory.isNotEmpty()) {
            cardsContainer?.post {
                for (json in cardJsonHistory.toList()) {
                    renderCard(json)
                }
            }
        }
    }

    fun receiveA2UI(json: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cardJsonHistory.add(json)
            renderCard(json)
        } else {
            activity?.runOnUiThread {
                cardJsonHistory.add(json)
                renderCard(json)
            } ?: run { pendingA2UIData = json }
        }
    }

    private fun renderCard(json: String) {
        val container = cardsContainer ?: return
        val ctx = requireContext()
        Log.i(TAG, "renderCard: length=${json.length}, hasCreateSurface=${json.contains("createSurface")}, hasUpdateComponents=${json.contains("updateComponents")}")
        val weatherResult = AGenUIHelpers.processWeatherCard(json)
        val isWeather = weatherResult != null

        val cardView = MaterialCardView(ctx).apply {
            layoutParams = MarginLayoutParams(
                MarginLayoutParams.MATCH_PARENT,
                MarginLayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(20)
            }
            setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            radius = dpToPx(16f)
            cardElevation = dpToPx(2f)
            strokeColor = Color.TRANSPARENT
            strokeWidth = 0
        }

        val frame = FrameLayout(ctx).apply {
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }
        cardView.addView(frame)

        val surfaceContainer = FrameLayout(ctx)
        frame.addView(surfaceContainer)

        val deleteBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x33000000)
            }
            background = bg
            layoutParams = FrameLayout.LayoutParams(dpToPx(20), dpToPx(20)).apply {
                gravity = Gravity.END or Gravity.TOP
                topMargin = dpToPx(2)
                marginEnd = dpToPx(2)
            }
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            elevation = dpToPx(4f)
            setOnClickListener { removeCard(cardView) }
        }
        frame.addView(deleteBtn)

        if (isWeather) {
            val weatherIcon = ImageView(ctx).apply {
                setImageResource(AGenUIHelpers.getWeatherIconRes(weatherResult!!.condition))
                layoutParams = FrameLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                    gravity = Gravity.END or Gravity.TOP
                    topMargin = dpToPx(10)
                    marginEnd = dpToPx(10)
                }
                alpha = 0.85f
            }
            frame.addView(weatherIcon)
        }

        val surfaceManager: SurfaceManager
        try {
            surfaceManager = SurfaceManager(requireActivity())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SurfaceManager", e)
            return
        }

        val entry = CardEntry(surfaceManager, null, cardView)
        cards.add(entry)

        val listener = object : ISurfaceManagerListener {
            override fun onCreateSurface(surface: Surface) {
                entry.surface = surface
                val view = surface.container
                if (view != null) {
                    activity?.runOnUiThread {
                        surfaceContainer.removeAllViews()
                        surfaceContainer.addView(view)
                        Log.i(TAG, "Surface view added: ${surface.surfaceId}")
                        cardsScroll?.post { cardsScroll?.fullScroll(View.FOCUS_DOWN) }
                        scheduleSnapshot(cardView, deleteBtn, surface.surfaceId)
                        if (surface.surfaceId == "music") {
                            startMusicStateSync(entry)
                        }
                    }
                }
            }

            override fun onDeleteSurface(surface: Surface) {
                if (surface.surfaceId == "music") {
                    stopMusicStateSync()
                }
                if (entry.surface == surface) {
                    activity?.runOnUiThread {
                        surfaceContainer.removeAllViews()
                        entry.surface = null
                    }
                }
            }

            override fun onError(surface: Surface?, code: Int, message: String?) {
                Log.e(TAG, "AGenUI error: code=$code msg=$message surface=${surface?.surfaceId}")
            }

            override fun surfaceSize(surfaceId: String): SurfaceSize? {
                return cachedSurfaceSize
            }

            override fun onReceiveActionEvent(event: String) {
                handleActionEvent(event)
            }
        }

        surfaceManager.addListener(listener)
        container.addView(cardView, 0)

        try {
            val surfaceJson = AGenUIHelpers.patchUnknownIcons(AGenUIHelpers.patchImageDimensions(weatherResult?.themedJson ?: json))
            surfaceManager.beginTextStream()
            for (line in surfaceJson.lines()) {
                if (line.isNotBlank()) {
                    surfaceManager.receiveTextChunk(line)
                }
            }
            surfaceManager.endTextStream()
            Log.i(TAG, "A2UI stream complete, ${json.lines().size} lines, total cards=${cards.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to receive A2UI data", e)
        }
    }

    private fun removeCard(cardView: MaterialCardView) {
        val index = cards.indexOfFirst { it.cardView == cardView }
        if (index >= 0) {
            val entry = cards.removeAt(index)
            if (index < cardJsonHistory.size) cardJsonHistory.removeAt(index)
            try {
                entry.surfaceManager.destroy()
            } catch (_: Exception) {}
        }
        cardsContainer?.removeView(cardView)
        Log.i(TAG, "Card removed, remaining=${cards.size}")
    }

    override fun onDestroyView() {
        stopMusicStateSync()
        for (entry in cards) {
            try { entry.surfaceManager.destroy() } catch (_: Exception) {}
        }
        cards.clear()
        cardsContainer?.removeAllViews()
        cardsContainer = null
        cardsScroll = null
        instance = null
        // cardJsonHistory is kept so cards can be re-rendered when view is recreated
        super.onDestroyView()
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp,
            resources.displayMetrics
        )
    }

    private fun dpToPx(dp: Int): Int {
        return dpToPx(dp.toFloat()).toInt()
    }

    private fun handleActionEvent(event: String) {
        Log.i(TAG, "Action event: $event")
        try {
            val json = JSONObject(event)
            // SDK envelope: {"action":{"name":...,"sourceComponentId":...},"version":"v0.9"}
            // The event name is nested under "action", NOT at the top level.
            val name = json.optJSONObject("action")?.optString("name") ?: json.optString("name")
            if (name.isEmpty()) {
                Log.w(TAG, "handleActionEvent: no action name in event")
                return
            }
            val ctx = context ?: run {
                Log.w(TAG, "handleActionEvent: context is null")
                return
            }
            when (name) {
                "music_prev" -> BydMusicController.previous(ctx)
                "music_next" -> BydMusicController.next(ctx)
                "music_play_pause" -> {
                    // One-shot state read (not polling). After toggling, flip the button
                    // glyph ▶↔⏸ for click-driven visual feedback (the card is otherwise static).
                    val nowPlaying = if (isMusicPlaying()) {
                        BydMusicController.pause(ctx); false
                    } else {
                        BydMusicController.play(ctx); true
                    }
                    lastPlayClickAt = android.os.SystemClock.elapsedRealtime()
                    updateMusicPlayGlyph(nowPlaying)
                }
                "music_vol_up" -> BydMusicController.volumeUp(ctx)
                "music_vol_down" -> BydMusicController.volumeDown(ctx)
                else -> Log.w(TAG, "Unknown action event: $name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle action event", e)
        }
    }

    /** Flips the music card's play button glyph: ⏸ when playing, ▶ when paused. */
    private fun updateMusicPlayGlyph(playing: Boolean) {
        val entry = cards.lastOrNull { it.surface?.surfaceId == "music" } ?: return
        val surface = entry.surface ?: return
        try {
            surface.updateComponent("play_txt", mapOf("text" to if (playing) "⏸" else "▶"))
        } catch (e: Exception) {
            Log.w(TAG, "updateMusicPlayGlyph failed: ${e.message}")
        }
    }

    /**
     * Lightweight music-state sync (no progress bar / time / cover — those have no data source).
     * Polls music-state.json at 1Hz and pushes only song title, artist, and the play/pause glyph.
     * The glyph update is suppressed for a 3s grace window after a click so the instant click-driven
     * flip doesn't get fought back by stale monitor data (monitor polls dumpsys every 3s).
     */
    private val musicSyncHandler = Handler(Looper.getMainLooper())
    private var musicSyncRunnable: Runnable? = null
    private var lastPlayClickAt = 0L
    private var lastSong: String? = null
    private var lastArtist: String? = null

    private fun startMusicStateSync(entry: CardEntry) {
        stopMusicStateSync()
        val runnable = object : Runnable {
            override fun run() {
                pushMusicState(entry)
                musicSyncHandler.postDelayed(this, 1000)
            }
        }
        musicSyncRunnable = runnable
        musicSyncHandler.post(runnable)
    }

    private fun stopMusicStateSync() {
        musicSyncRunnable?.let { musicSyncHandler.removeCallbacks(it) }
        musicSyncRunnable = null
    }

    private fun pushMusicState(entry: CardEntry) {
        val surface = entry.surface ?: return
        try {
            val file = File("/data/local/tmp/music-state.json")
            if (!file.exists()) return
            val state = JSONObject(file.readText(Charsets.UTF_8))
            val title = state.optString("title", "")
            val artist = state.optString("artist", "")
            val isPlaying = state.optString("state") == "playing"
            // Only push title/artist when they actually change (avoid spamming the surface).
            if (title.isNotEmpty() && title != lastSong) {
                surface.updateComponent("song", mapOf("text" to title))
                lastSong = title
            }
            if (artist.isNotEmpty() && artist != lastArtist) {
                surface.updateComponent("artist", mapOf("text" to artist))
                lastArtist = artist
            }
            // Glyph: skip during the post-click grace window to avoid flicker with the click flip.
            val sinceClick = android.os.SystemClock.elapsedRealtime() - lastPlayClickAt
            if (sinceClick > 3000) {
                updateMusicPlayGlyph(isPlaying)
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushMusicState failed: ${e.message}")
        }
    }

    private fun isMusicPlaying(): Boolean {
        return try {
            val file = File("/data/local/tmp/music-state.json")
            if (!file.exists()) return false
            val state = JSONObject(file.readText(Charsets.UTF_8))
            state.optString("state") == "playing"
        } catch (e: Exception) {
            Log.w(TAG, "isMusicPlaying failed: ${e.message}")
            false
        }
    }

    private val snapshotHandler = Handler(Looper.getMainLooper())

    private fun scheduleSnapshot(sourceView: View, hideView: View?, surfaceId: String) {
        // 800ms 让 Picasso 加载完 + shimmer 停。SDK 没有 render-complete 回调。
        // 截 cardView 整张（含天气图标、圆角白底）；截图时隐藏 close 按钮，悬浮球里 X 无意义。
        snapshotHandler.postDelayed({
            try {
                val prevVisibility = hideView?.visibility ?: View.VISIBLE
                hideView?.visibility = View.INVISIBLE
                val width = sourceView.width
                val height = sourceView.height
                if (width <= 0 || height <= 0) {
                    hideView?.visibility = prevVisibility
                    return@postDelayed
                }
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                sourceView.draw(canvas)
                CardSnapshotBus.emit(bmp)
                Log.i(TAG, "Snapshot emitted: $surfaceId ${width}x${height}")
                hideView?.visibility = prevVisibility
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot failed: ${e.message}")
            }
        }, 800L)
    }
}
