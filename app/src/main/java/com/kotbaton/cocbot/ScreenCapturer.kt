package com.kotbaton.cocbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display

/**
 * Захват экрана через MediaProjection. Каждый новый кадр сразу копируется
 * в [frame], поэтому [capture] всегда возвращает актуальное состояние экрана.
 */
class ScreenCapturer(private val context: Context, private val projection: MediaProjection) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("cocbot-capture").apply { start() }
    private val handler = Handler(thread.looper)

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var frame: Bitmap? = null
    private var frameValid = false
    private val lock = Any()

    @Volatile
    var released = false
        private set

    /** Захват остановили мы сами, а не система или пользователь. */
    @Volatile
    private var selfStop = false

    init {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!selfStop) {
                    BotState.log("Захват экрана прерван: нажали «Остановить» в шторке или его забрала система")
                }
                release()
            }
        }, mainHandler)
        setup()
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val disp = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        disp.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun setup() {
        synchronized(lock) {
            val (w, h) = screenSize()
            width = w
            height = h
            frameValid = false
            val dpi = context.resources.displayMetrics.densityDpi
            reader?.close()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            r.setOnImageAvailableListener({ onFrame(it) }, handler)
            reader = r
            val d = display
            if (d == null) {
                display = projection.createVirtualDisplay(
                    "cocbot", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    r.surface, null, handler
                )
            } else {
                d.surface = r.surface
                d.resize(w, h, dpi)
            }
            BotState.log("Захват экрана: ${w}x${h}")
        }
    }

    private fun onFrame(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val bmpWidth = rowStride / pixelStride
            synchronized(lock) {
                if (released) return
                val f = frame?.takeIf { it.width == bmpWidth && it.height == image.height }
                    ?: Bitmap.createBitmap(bmpWidth, image.height, Bitmap.Config.ARGB_8888).also { frame = it }
                plane.buffer.rewind()
                f.copyPixelsFromBuffer(plane.buffer)
                frameValid = true
            }
        } catch (e: IllegalStateException) {
            // Кадр закрылся вместе со старым ImageReader при повороте экрана, это нормально.
        } catch (e: Exception) {
            BotState.log("Ошибка кадра: ${e.message}")
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
            }
        }
    }

    /** Копия последнего кадра (ровно размером экрана) или null, если кадров ещё не было. */
    fun capture(): Bitmap? {
        if (released) return null
        val (w, h) = screenSize()
        if (w != width || h != height) {
            BotState.log("Экран повернулся, пересоздаю захват")
            setup()
            return null
        }
        synchronized(lock) {
            val f = frame ?: return null
            if (!frameValid) return null
            return Bitmap.createBitmap(f, 0, 0, minOf(width, f.width), minOf(height, f.height))
        }
    }

    fun release() {
        if (released) return
        released = true
        synchronized(lock) {
            try {
                display?.release()
            } catch (e: Exception) {
            }
            try {
                reader?.close()
            } catch (e: Exception) {
            }
            display = null
            reader = null
            frame = null
        }
        selfStop = true
        try {
            projection.stop()
        } catch (e: Exception) {
        }
        thread.quitSafely()
    }
}
