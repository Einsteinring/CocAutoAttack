package com.kotbaton.cocbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Служба доступности: единственная её задача выполнять жесты (тапы) на экране. */
class BotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BotAccessibilityService? = null
            private set

        private const val TAP_MS = 40L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BotState.log("Служба доступности подключена")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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

    /** Одиночный тап в пикселях экрана. */
    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_MS)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** Свайп из одной точки в другую. */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Серия тапов с интервалом. Несколько тапов упаковываются в один жест
     * (до getMaxStrokeCount штрихов), чтобы высадка была быстрой.
     */
    suspend fun tapSequence(points: List<PointF>, intervalMs: Long): Boolean {
        if (points.isEmpty()) return true
        val chunkSize = GestureDescription.getMaxStrokeCount().coerceAtLeast(1)
        val maxDuration = GestureDescription.getMaxGestureDuration()
        var allOk = true
        for (chunk in points.chunked(chunkSize)) {
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
