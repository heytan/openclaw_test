package com.openclaw.car.agenui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

object CardSnapshotBus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(Bitmap) -> Unit>()

    fun subscribe(l: (Bitmap) -> Unit) {
        synchronized(listeners) { listeners.add(l) }
    }

    fun unsubscribe(l: (Bitmap) -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    fun emit(bmp: Bitmap) {
        synchronized(listeners) {
            val copy = listeners.toList()
            main.post { copy.forEach { runCatching { it(bmp) } } }
        }
    }
}
