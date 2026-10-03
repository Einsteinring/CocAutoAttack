package com.kotbaton.cocbot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Плавающая подпись со статусом бота и полноэкранная разметка для калибровки. */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null
    private var stopView: TextView? = null
    private var calibView: CalibrationView? = null
    private val hideCalibration = Runnable { hideCalibration() }

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    private fun params(fullscreen: Boolean): WindowManager.LayoutParams {
        val size = if (fullscreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // Оба окна сквозные для касаний: плашка иначе перехватывала бы тапы бота по игре.
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        // Плашка в левом верхнем углу: вверху по центру у игры значки строителей, там же края высадки.
        p.gravity = if (fullscreen) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    fun showStatus(text: String, onClick: () -> Unit) {
        handler.post {
            if (!canDraw() || statusView != null) return@post
            val tv = TextView(ctx).apply {
                setText(text)
                setTextColor(Color.WHITE)
                setBackgroundColor(0xAA000000.toInt())
                setPadding(16, 4, 16, 4)
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.22f).toInt()
            }
            try {
                wm.addView(tv, params(fullscreen = false))
                statusView = tv
            } catch (e: Exception) {
                BotState.log("Оверлей не показан: ${e.message}")
            }
        }
    }

    /**
     * Круглая кнопка «Стоп» поверх игры, у левого края экрана посередине. Бот туда никогда не тапает:
     * края высадки начинаются правее, пузыри сбора у левого края отбрасываются, а кнопки игры
     * стоят ниже и выше. Это единственное окно бота, которое ловит касания.
     */
    fun showStopButton(onClick: () -> Unit) {
        handler.post {
            if (!canDraw() || stopView != null) return@post
            val dm = ctx.resources.displayMetrics
            val size = (40 * dm.density).toInt()
            val tv = TextView(ctx).apply {
                text = "■"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 16f
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xE6D32F2F.toInt())
                    setStroke((2 * dm.density).toInt(), Color.WHITE)
                }
                contentDescription = "Остановить бота"
                setOnClickListener { onClick() }
            }
            val p = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            p.x = 0
            p.y = (minOf(dm.widthPixels, dm.heightPixels) * 0.42f).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            try {
                wm.addView(tv, p)
                stopView = tv
            } catch (e: Exception) {
                BotState.log("Кнопка «Стоп» поверх игры не показана: ${e.message}")
            }
        }
    }

    fun updateStatus(text: String) {
        handler.post { statusView?.text = text }
    }

    fun hideStatus() {
        handler.post {
            listOfNotNull(statusView, stopView).forEach {
                try {
                    wm.removeView(it)
                } catch (e: Exception) {
                }
            }
            statusView = null
            stopView = null
        }
    }

    fun showCalibration(cfg: BotConfig, preset: AttackPreset, durationMs: Long) {
        handler.post {
            hideCalibration()
            if (!canDraw()) return@post
            val v = CalibrationView(ctx, cfg, preset)
            try {
                wm.addView(v, params(fullscreen = true))
                calibView = v
                handler.removeCallbacks(hideCalibration)
                handler.postDelayed(hideCalibration, durationMs)
            } catch (e: Exception) {
                BotState.log("Разметка не показана: ${e.message}")
            }
        }
    }

    fun hideCalibration() {
        calibView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        calibView = null
    }

    fun hideAll() {
        hideStatus()
        handler.post { hideCalibration() }
    }
}

/** Рисует поверх игры края высадки, карточки войск, цели прокачки и области добычи. */
class CalibrationView(
    ctx: Context,
    private val cfg: BotConfig,
    private val preset: AttackPreset,
) : View(ctx) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val n = preset.pointsPerEdge.coerceAtLeast(1)

        preset.effectiveEdges(if (h > 0f) w / h else 2.2f).forEachIndexed { i, e ->
            canvas.drawLine(e.x1 * w, e.y1 * h, e.x2 * w, e.y2 * h, linePaint)
            for (k in 0 until n) {
                val t = if (n <= 1) 0.5f else k.toFloat() / (n - 1)
                val p = e.at(t)
                canvas.drawCircle(p.x * w, p.y * h, 8f, pointPaint)
            }
            canvas.drawText("край ${i + 1}", e.x1 * w, e.y1 * h - 12f, textPaint)
        }

        val slots = maxOf(cfg.slotCount, cfg.slotPoints.size)
        for (s in 0 until slots) {
            val p = cfg.slotPoint(s)
            canvas.drawCircle(p.x * w, p.y * h, 22f, slotPaint)
            canvas.drawText("${s + 1}", p.x * w - 8f, p.y * h - 30f, textPaint)
        }



        canvas.drawText("Шаблон: ${preset.name}", 24f, 44f, textPaint)
        canvas.drawText("красное — края высадки, голубое — карточки войск", 24f, 80f, textPaint)
    }
}
