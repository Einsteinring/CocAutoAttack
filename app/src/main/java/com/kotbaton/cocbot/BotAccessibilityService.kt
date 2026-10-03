package com.kotbaton.cocbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Служба доступности: выполняет жесты (тапы) на экране, следит, какое приложение сейчас на экране,
 * и останавливает бота кнопкой громкости вниз.
 */
class BotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BotAccessibilityService? = null
            private set

        /** Пакет приложения, которое сейчас на экране. null, пока не было ни одного события. */
        @Volatile
        var foregroundPackage: String? = null
            private set

        private const val TAP_MS = 40L

        /**
         * Можно ли тапать: на экране игра. Пока не пришло ни одного события (служба только включилась),
         * считаем, что можно, иначе бот не начал бы работу.
         */
        fun gameOnScreen(): Boolean {
            val pkg = foregroundPackage ?: return true
            return pkg == BotService.GAME_PACKAGE
        }

        /** Окна, которые не меняют «приложение на экране»: шторка, клавиатура, всплывашки и плашки бота. */
        internal fun isTransientWindow(pkg: String, className: String?, ownPackage: String = "com.kotbaton.cocbot"): Boolean {
            if (pkg == "com.android.systemui") return true
            val cls = className ?: return true
            // У самого бота сменой приложения считаются только его экраны, а не плашки поверх игры.
            if (pkg == ownPackage) return !cls.endsWith("Activity")
            return cls.startsWith("android.widget.") ||
                cls.startsWith("android.view.") ||
                cls.startsWith("android.inputmethodservice.")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BotState.log("Служба доступности подключена")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (isTransientWindow(pkg, event.className?.toString(), packageName)) return
        if (pkg != foregroundPackage) {
            val wasGame = foregroundPackage == BotService.GAME_PACKAGE
            foregroundPackage = pkg
            if (BotState.running.value && wasGame && pkg != BotService.GAME_PACKAGE) {
                BotState.log("Игра ушла с экрана: тапы приостановлены")
            }
        }
    }

    /** Громкость вниз останавливает бота, пока он работает. В остальное время кнопка работает как обычно. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN || !BotState.running.value) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            BotState.log("Нажата громкость вниз: останавливаю")
            try {
                startService(BotService.stopIntent(this))
            } catch (e: Exception) {
                BotState.log("Не удалось остановить: ${e.message}")
            }
        }
        return true
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Кнопка «Домой»: игра уходит в фон, после этого её процесс можно завершить. */
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Одиночный тап в пикселях экрана. Не выполняется, если на экране не игра. */
    suspend fun tap(x: Float, y: Float): Boolean {
        if (!gameOnScreen()) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_MS)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** Свайп из одной точки в другую. Не выполняется, если на экране не игра. */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        if (!gameOnScreen()) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Серия тапов с интервалом. Несколько тапов упаковываются в один жест
     * (до getMaxStrokeCount штрихов), чтобы высадка была быстрой. Перед каждой пачкой
     * проверяется, что игра всё ещё на экране: если свернули, остаток не тапается.
     */
    suspend fun tapSequence(points: List<PointF>, intervalMs: Long): Boolean {
        if (points.isEmpty()) return true
        val chunkSize = GestureDescription.getMaxStrokeCount().coerceAtLeast(1)
        val maxDuration = GestureDescription.getMaxGestureDuration()
        var allOk = true
        for (chunk in points.chunked(chunkSize)) {
            if (!gameOnScreen()) return false
            val builder = GestureDescription.Builder()
            chunk.forEachIndexed { i, p ->
                val start = (i * intervalMs).coerceAtMost(maxDuration - TAP_MS - 1)
                val path = Path().apply { moveTo(p.x, p.y) }
                builder.addStroke(GestureDescription.StrokeDescription(path, start, TAP_MS))
            }
            if (!dispatch(builder.build())) allOk = false
        }
        return allOk
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val accepted = try {
                dispatchGesture(gesture, callback, null)
            } catch (e: Exception) {
                BotState.log("Ошибка жеста: ${e.message}")
                false
            }
            if (!accepted && cont.isActive) cont.resume(false)
        }
}
