package com.openclaw.car.agenui

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.amap.agenui.render.surface.ISurfaceManagerListener
import com.amap.agenui.render.surface.Surface
import com.amap.agenui.render.surface.SurfaceManager
import com.amap.agenui.render.surface.SurfaceSize
import com.google.android.material.card.MaterialCardView
import com.openclaw.car.OpenClawApp

class InteractiveCardActivity : AppCompatActivity() {

    companion object {
        const val TAG = "${OpenClawApp.TAG}.InteractiveCard"
        const val EXTRA_CARD_JSON = "card_json"
        const val EXTRA_CARD_X = "card_x"
        const val EXTRA_CARD_Y = "card_y"
        const val EXTRA_CARD_W = "card_w"
        /** App 切后台前截图回调（Service 在 overlay 里显示静态截图，后台持久）。 */
        @Volatile var onBackgroundSnapshot: ((android.graphics.Bitmap) -> Unit)? = null
        /** App 回前台回调（隐藏静态截图，恢复交互卡片）。 */
        @Volatile var onForegroundRestore: (() -> Unit)? = null
        @Volatile var instance: InteractiveCardActivity? = null
        @Volatile var moveListener: ((Int, Int) -> Unit)? = null
    }

    private var surfaceManager: SurfaceManager? = null
    private var cardView: MaterialCardView? = null
    private var surfaceContainer: FrameLayout? = null
    private var surface: Surface? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var patchedJson: String? = null
    private var surfaceCreateCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val musicController = MusicCardController(TAG)
    @Volatile private var cachedSurfaceSize: SurfaceSize? = null

    private val surfaceListener = object : ISurfaceManagerListener {
        override fun onCreateSurface(s: Surface) {
            surface = s
            surfaceCreateCount++
            s.container?.let { view ->
                runOnUiThread {
                    surfaceContainer?.removeAllViews()
                    surfaceContainer?.addView(view)
                    AGenUIHelpers.clearInnerCardBackgrounds(view)
                    cardView?.let { cv -> cv.post { if (cv.width > 0 && cv.height > 0) AGenUIHelpers.attachCardBackground(this@InteractiveCardActivity, cv) } }
                    if (s.surfaceId == "music") musicController.startSync { surface }
                    // Surface recreation (2nd+ time): the SDK doesn't auto-redraw after the
                    // system reclaims + recreates the surface → re-stream the JSON to restore content.
                    if (surfaceCreateCount > 1) {
                        val mgr = surfaceManager; val json = patchedJson
                        if (mgr != null && json != null) {
                            try {
                                mgr.beginTextStream()
                                for (line in json.lines()) { if (line.isNotBlank()) mgr.receiveTextChunk(line) }
                                mgr.endTextStream()
                                Log.i(TAG, "Re-rendered on surface recreation (#$surfaceCreateCount)")
                            } catch (e: Exception) { Log.w(TAG, "Re-render failed: ${e.message}") }
                        }
                    }
                }
            }
        }
        override fun onDeleteSurface(s: Surface) { if (s.surfaceId == "music") musicController.stopSync(); if (surface == s) surface = null }
        override fun onError(s: Surface?, code: Int, message: String?) { Log.e(TAG, "Surface error: $code $message") }
        override fun surfaceSize(surfaceId: String): SurfaceSize? = cachedSurfaceSize
        override fun onReceiveActionEvent(event: String) { musicController.handleActionEvent(this@InteractiveCardActivity, event) { surface } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        val json = intent.getStringExtra(EXTRA_CARD_JSON) ?: LatestCardJson.json ?: run { finish(); return }
        val cardX = intent.getIntExtra(EXTRA_CARD_X, 0)
        val cardY = intent.getIntExtra(EXTRA_CARD_Y, 0)
        val cardW = intent.getIntExtra(EXTRA_CARD_W, (resources.displayMetrics.widthPixels * 0.4).toInt())
        cachedSurfaceSize = SurfaceSize(cardW.toFloat(), resources.displayMetrics.heightPixels.toFloat())
        window.setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        val weatherResult = AGenUIHelpers.processWeatherCard(json)
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(Color.parseColor("#FFFFFF")); radius = dpToPx(16f); cardElevation = dpToPx(2f); strokeColor = Color.TRANSPARENT; strokeWidth = 0
        }
        val frame = FrameLayout(this).apply { setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12)) }
        card.addView(frame)
        val sc = FrameLayout(this); frame.addView(sc); surfaceContainer = sc; cardView = card

        val p = WindowManager.LayoutParams(cardW, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = cardX; y = cardY }
        overlayParams = p; windowManager.addView(card, p)
        moveListener = { x, y -> p.x = x; p.y = y; try { windowManager.updateViewLayout(card, p) } catch (_: Exception) {} }

        surfaceManager = try { SurfaceManager(this).also { it.addListener(surfaceListener) } } catch (e: Exception) { finish(); return }
        try {
            patchedJson = AGenUIHelpers.patchUnknownIcons(AGenUIHelpers.patchCardBackgrounds(AGenUIHelpers.patchImageDimensions(weatherResult?.themedJson ?: json)))
            surfaceManager!!.beginTextStream(); for (line in patchedJson!!.lines()) { if (line.isNotBlank()) surfaceManager!!.receiveTextChunk(line) }; surfaceManager!!.endTextStream()
        } catch (e: Exception) { finish(); return }
    }

    override fun onDestroy() {
        musicController.stopSync(); handler.removeCallbacksAndMessages(null)
        try { surfaceManager?.removeListener(surfaceListener); surfaceManager?.destroy() } catch (_: Exception) {}
        surfaceManager = null
        cardView?.let { cv -> try { windowManager.removeView(cv) } catch (_: Exception) {} }
        cardView = null; overlayParams = null
        if (instance === this) instance = null
        // Don't clear moveListener here — a new InteractiveCardActivity (refresh) sets it in
        // onCreate, and clearing here would race with that. collapse() clears it when done.
        onBackgroundSnapshot = null; onForegroundRestore = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // App is backgrounding → the Activity's surface will be torn down. Capture the card's
        // current state as a bitmap so the bubble's Service overlay can show a static snapshot
        // (which persists over background — Service overlay isn't paused).
        cardView?.let { cv ->
            if (cv.width > 0 && cv.height > 0) {
                try {
                    val bmp = android.graphics.Bitmap.createBitmap(cv.width, cv.height, android.graphics.Bitmap.Config.ARGB_8888)
                    cv.draw(android.graphics.Canvas(bmp))
                    onBackgroundSnapshot?.invoke(bmp)
                    Log.i(TAG, "Captured background snapshot ${cv.width}x${cv.height}")
                } catch (e: Exception) {
                    Log.w(TAG, "Background snapshot capture failed: ${e.message}")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // App back to foreground → hide the static snapshot, the live interactive card returns.
        onForegroundRestore?.invoke()
    }

    private fun dpToPx(dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
    private fun dpToPx(dp: Int): Int = dpToPx(dp.toFloat()).toInt()
}
