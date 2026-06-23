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
import com.openclaw.car.agenui.LatestCardJson
import com.openclaw.car.agenui.MusicCardController
import com.openclaw.car.widget.FlowLayout
import org.json.JSONObject

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

    private val musicController = MusicCardController(TAG)

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
        LatestCardJson.json = json
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
                        // SDK CardComponent hardcodes a white card background; make it
                        // transparent so the outer image+scrim shows.
                        AGenUIHelpers.clearInnerCardBackgrounds(view)
                        // Add bg image + scrim behind the surface content, sized to the card's
                        // real measured size (post-layout, explicit size → no measure inflation).
                        AGenUIHelpers.attachCardBackground(ctx, cardView)
                        // Record the card's width so the bubble card can match it (consistent bg crop).
                        cardView.post { LatestCardJson.cardWidth = cardView.width }
                        cardsScroll?.post { cardsScroll?.fullScroll(View.FOCUS_DOWN) }
                        scheduleSnapshot(cardView, deleteBtn, surface.surfaceId)
                        if (surface.surfaceId == "music") {
                            musicController.startSync { entry.surface }
                        }
                    }
                }
            }

            override fun onDeleteSurface(surface: Surface) {
                if (surface.surfaceId == "music") {
                    musicController.stopSync()
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
                val ctx2 = context
                if (ctx2 == null) { Log.w(TAG, "onReceiveActionEvent: context null"); return }
                musicController.handleActionEvent(ctx2, event) { entry.surface }
            }
        }

        surfaceManager.addListener(listener)
        container.addView(cardView, 0)

        try {
            val patched = AGenUIHelpers.patchImageDimensions(weatherResult?.themedJson ?: json)
            val surfaceJson = AGenUIHelpers.patchUnknownIcons(AGenUIHelpers.patchCardBackgrounds(patched))
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
        musicController.stopSync()
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
