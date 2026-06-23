package com.openclaw.car.agenui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import com.amap.agenui.render.surface.ISurfaceManagerListener
import com.amap.agenui.render.surface.Surface
import com.amap.agenui.render.surface.SurfaceManager
import com.amap.agenui.render.surface.SurfaceSize
import com.google.android.material.card.MaterialCardView
import com.openclaw.car.OpenClawApp

/**
 * 离屏卡片渲染 Activity：当 app 被杀/AGenUIFragment 不可用时，由 FloatingBubbleService
 * 启动本 Activity 渲染 A2UI JSON。渲染完成后截图发 [CardSnapshotBus]，悬浮球接收并显示。
 *
 * 用户视角：透明 + 无动画 + 不入最近任务，渲染完成（约 1s 内）自动 finish。
 *
 * 注意：启动 Activity 会暂停用户当前 app（如导航），但透明主题下用户视觉无变化，
 * 只是底层 app 短暂 onPause（停 TTS、动画等）。渲染完立即 finish 恢复。
 */
class BackgroundCardRenderActivity : AppCompatActivity() {

    companion object {
        const val TAG = "${OpenClawApp.TAG}.BgRender"
        const val EXTRA_A2UI_JSON = "a2ui_json"
        private const val SNAPSHOT_DELAY_MS = 800L
        private const val SAFETY_FINISH_MS = 5000L
    }

    private var surfaceManager: SurfaceManager? = null
    private var cardView: MaterialCardView? = null
    private var surfaceContainer: FrameLayout? = null
    private var deleteBtn: ImageView? = null
    private var snapshotEmitted = false
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedSurfaceSize: SurfaceSize? = null

    private val surfaceListener = object : ISurfaceManagerListener {
        override fun onCreateSurface(surface: Surface) {
            val view = surface.container
            if (view != null) {
                runOnUiThread {
                    surfaceContainer?.removeAllViews()
                    surfaceContainer?.addView(view)
                    Log.i(TAG, "Surface view added: ${surface.surfaceId}")
                    handler.postDelayed({ snapshotAndFinish() }, SNAPSHOT_DELAY_MS)
                }
            }
        }

        override fun onDeleteSurface(surface: Surface) {}

        override fun onError(surface: Surface?, code: Int, message: String?) {
            Log.e(TAG, "Surface error: code=$code msg=$message")
            finish()
        }

        override fun surfaceSize(surfaceId: String): SurfaceSize? = cachedSurfaceSize
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val json = intent.getStringExtra(EXTRA_A2UI_JSON) ?: run {
            Log.w(TAG, "No A2UI JSON in intent, finishing")
            finish()
            return
        }
        LatestCardJson.json = json
        Log.i(TAG, "onCreate: json length=${json.length}")

        val dm = resources.displayMetrics
        // 卡片宽度约束到屏幕宽度的 40%（≈ AGenUI tab 里 FlowLayout.getChildWidth 的实际宽度），
        // 避免 MATCH_PARENT 导致 surface 在 1728px 宽度下被拉伸变形。
        val cardWidthPx = (dm.widthPixels * 0.4).toInt()
        cachedSurfaceSize = SurfaceSize(cardWidthPx.toFloat(), dm.heightPixels.toFloat())

        val root = FrameLayout(this).apply {
            layoutParams = MarginLayoutParams(
                MarginLayoutParams.MATCH_PARENT,
                MarginLayoutParams.WRAP_CONTENT
            )
        }
        setContentView(root)

        val weatherResult = AGenUIHelpers.processWeatherCard(json)
        val isWeather = weatherResult != null

        val card = MaterialCardView(this).apply {
            layoutParams = MarginLayoutParams(
                cardWidthPx,
                MarginLayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(20) }
            setCardBackgroundColor(Color.parseColor("#FFFFFF"))
            radius = dpToPx(16f)
            cardElevation = dpToPx(2f)
            strokeColor = Color.TRANSPARENT
            strokeWidth = 0
        }
        val frame = FrameLayout(this).apply {
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }
        card.addView(frame)

        val sc = FrameLayout(this)
        frame.addView(sc)
        surfaceContainer = sc

        val close = ImageView(this).apply {
            setImageResource(com.openclaw.car.R.drawable.ic_close)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
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
        }
        frame.addView(close)
        deleteBtn = close

        if (isWeather) {
            ImageView(this).apply {
                setImageResource(AGenUIHelpers.getWeatherIconRes(weatherResult!!.condition))
                layoutParams = FrameLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                    gravity = Gravity.END or Gravity.TOP
                    topMargin = dpToPx(10)
                    marginEnd = dpToPx(10)
                }
                alpha = 0.85f
            }.also { frame.addView(it) }
        }

        root.addView(card)
        cardView = card

        // 把 cardView 平移到屏幕外：root FrameLayout 默认 gravity=top|start，
        // 卡片白底会直接露在屏幕左上角，用户看到"卡片在桌面闪烁"。
        // translationX/Y 不影响 View.draw(canvas) 直绘（snapshot 路径不带 transform），
        // 也不影响 measure/layout，所以渲染时机和尺寸都不变，只是系统合成时不显示。
        card.translationX = -10_000f
        card.translationY = -10_000f

        val mgr = try {
            SurfaceManager(this).also { it.addListener(surfaceListener) }
        } catch (e: Exception) {
            Log.e(TAG, "SurfaceManager creation failed", e)
            finish()
            return
        }
        surfaceManager = mgr

        try {
            val patched = AGenUIHelpers.patchUnknownIcons(
                AGenUIHelpers.patchImageDimensions(weatherResult?.themedJson ?: json)
            )
            mgr.beginTextStream()
            for (line in patched.lines()) {
                if (line.isNotBlank()) {
                    mgr.receiveTextChunk(line)
                }
            }
            mgr.endTextStream()
            Log.i(TAG, "A2UI stream complete")
        } catch (e: Exception) {
            Log.e(TAG, "A2UI stream failed", e)
            finish()
            return
        }

        // 安全网：万一 SurfaceManager 没回调 onCreateSurface（如 native 异常），5s 后强退
        handler.postDelayed({
            if (!snapshotEmitted && !isFinishing) {
                Log.w(TAG, "Safety timeout, finishing without snapshot")
                finish()
            }
        }, SAFETY_FINISH_MS)
    }

    private fun snapshotAndFinish() {
        if (snapshotEmitted) return
        val target = cardView ?: run { finish(); return }
        val prevVisibility = deleteBtn?.visibility ?: View.VISIBLE
        deleteBtn?.visibility = View.INVISIBLE
        try {
            val width = target.width
            val height = target.height
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "snapshotAndFinish: zero size ${width}x${height}")
                finish()
                return
            }
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            target.draw(Canvas(bmp))
            CardSnapshotBus.emit(bmp)
            snapshotEmitted = true
            Log.i(TAG, "Snapshot emitted: ${width}x${height}")
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot failed: ${e.message}")
        } finally {
            deleteBtn?.visibility = prevVisibility
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            surfaceManager?.removeListener(surfaceListener)
            surfaceManager?.destroy()
        } catch (_: Exception) {}
        surfaceManager = null
        super.onDestroy()
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun dpToPx(dp: Int): Int = dpToPx(dp.toFloat()).toInt()
}
