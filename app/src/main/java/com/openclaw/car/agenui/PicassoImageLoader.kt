package com.openclaw.car.agenui

import android.graphics.drawable.Drawable
import android.util.Log
import com.amap.agenui.render.image.ImageCallback
import com.amap.agenui.render.image.ImageLoadResult
import com.amap.agenui.render.image.ImageLoader
import com.amap.agenui.render.image.ImageLoaderError
import com.squareup.picasso.MemoryPolicy
import com.squareup.picasso.Picasso
import java.util.concurrent.atomic.AtomicInteger

class PicassoImageLoader : ImageLoader {

    private val requestCounter = AtomicInteger(0)
    private val pendingRequests = mutableMapOf<String, com.squareup.picasso.Request>()
    // Hold STRONG references to Targets — Picasso.into(Target) uses weak refs internally, so
    // the Target gets GC'd before the network callback fires → image silently never shows.
    private val activeTargets = java.util.Collections.synchronizedSet(mutableSetOf<com.squareup.picasso.Target>())

    override fun loadImage(url: String, options: Map<String, Any>?, callback: ImageCallback): String {
        val requestId = "picasso_${requestCounter.incrementAndGet()}"
        Log.i(TAG, "loadImage: url=$url opts=$options requestId=$requestId")
        val requestCreator = Picasso.get().load(url)
            .memoryPolicy(MemoryPolicy.NO_CACHE, MemoryPolicy.NO_STORE)

        options?.let { opts ->
            (opts["width"] as? Int)?.let { w ->
                (opts["height"] as? Int)?.let { h ->
                    requestCreator.resize(w, h)
                }
            }
        }

        val target = object : com.squareup.picasso.Target {
            override fun onBitmapLoaded(bitmap: android.graphics.Bitmap, from: com.squareup.picasso.Picasso.LoadedFrom) {
                activeTargets.remove(this)
                Log.i(TAG, "onBitmapLoaded: url=$url ${bitmap.width}x${bitmap.height} from=$from")
                val drawable = android.graphics.drawable.BitmapDrawable(bitmap)
                callback.onSuccess(ImageLoadResult.bitmap(drawable, from != com.squareup.picasso.Picasso.LoadedFrom.NETWORK))
            }

            override fun onBitmapFailed(e: java.lang.Exception?, errorDrawable: Drawable?) {
                activeTargets.remove(this)
                Log.w(TAG, "onBitmapFailed: url=$url err=${e?.message}", e)
                callback.onFailure(ImageLoaderError.networkError(url, e))
            }

            override fun onPrepareLoad(placeHolderDrawable: Drawable?) {}
        }
        activeTargets.add(target)

        val request = requestCreator.into(target)
        return requestId
    }

    override fun cancel(requestId: String) {
        pendingRequests.remove(requestId)
    }

    companion object {
        private const val TAG = "CarAgent.AGenUI"
    }
}
