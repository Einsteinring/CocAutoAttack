package com.kotbaton.cocbot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground-служба: держит MediaProjection и крутит выбранный сценарий бота. */
class BotService : Service() {

    companion object {
        const val ACTION_START = "com.kotbaton.cocbot.START"
        const val ACTION_TEST = "com.kotbaton.cocbot.TEST"
        const val ACTION_SCREENSHOT = "com.kotbaton.cocbot.SCREENSHOT"
        const val ACTION_UPGRADE = "com.kotbaton.cocbot.UPGRADE"
        const val ACTION_COLLECT = "com.kotbaton.cocbot.COLLECT"
        const val ACTION_FARM_FULL = "com.kotbaton.cocbot.FARM_FULL"
        const val ACTION_STOP = "com.kotbaton.cocbot.STOP"
        const val ACTION_CALIBRATE = "com.kotbaton.cocbot.CALIBRATE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        const val GAME_PACKAGE = "com.supercell.clashofclans"

        private const val CHANNEL = "bot"
        private const val NOTIF_ID = 1

        fun stopIntent(ctx: Context): Intent = Intent(ctx, BotService::class.java).setAction(ACTION_STOP)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var capturer: ScreenCapturer? = null
    private lateinit var overlay: OverlayController
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayController(this)
        BotState.attachFile(this)
        Vision.migrateOwnTemplates(this)
        createChannel()
        mainScope.launch {
            BotState.status.collect { s ->
                if (foreground) notify(s)
                overlay.updateStatus(s)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopBot("Остановлено пользователем")
            ACTION_CALIBRATE -> showCalibration()
            ACTION_START, ACTION_FARM_FULL, ACTION_TEST, ACTION_SCREENSHOT, ACTION_UPGRADE, ACTION_COLLECT -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun showCalibration() {
        if (!overlay.canDraw()) {
            BotState.log("Нет разрешения на показ поверх других окон")
            return
        }
        val cfg = BotConfig.load(this)
        overlay.showCalibration(cfg, PresetStore.active(this, cfg.presetName), 20_000L)
    }

    private fun startCapture(intent: Intent) {
        if (job?.isActive == true) {
            BotState.log("Бот уже запущен")
            return
        }
        startAsForeground()
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (code != Activity.RESULT_OK || data == null) {
            finish("Нет разрешения на захват экрана")
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            BotState.log("MediaProjection: ${e.message}")
            null
        }
        if (projection == null) {
            finish("Не удалось получить доступ к экрану")
            return
        }
        val cap = try {
            ScreenCapturer(this, projection)
        } catch (e: Exception) {
            finish("Ошибка захвата экрана: ${e.message}")
            return
        }
        capturer = cap

        val cfg = BotConfig.load(this)
        val preset = PresetStore.active(this, cfg.presetName)
        val vision = Vision(this) { cfg.threshold }
        vision.clearCache()
        val engine = BotEngine(cfg, preset, cap, vision, { BotAccessibilityService.instance }, { relaunchGame() }, { closeGame() })
        BotState.running.value = true
        if (overlay.canDraw()) {
            overlay.showStatus("CoC бот") { stopBot("Остановлено через оверлей") }
        }

        val action = intent.action
        job = scope.launch {
            var reason = "Завершено"
            try {
                when (action) {
                    ACTION_START -> engine.run()
                    ACTION_FARM_FULL -> engine.run(farmToFull = true)
                    ACTION_TEST -> engine.runTest(5)
                    ACTION_SCREENSHOT -> engine.screenshot(5)
                    ACTION_UPGRADE -> engine.upgradeOnly()
                    ACTION_COLLECT -> engine.collectOnly()
                }
                reason = BotState.status.value
            } catch (e: CancellationException) {
                reason = "Остановлено"
            } catch (e: Exception) {
                BotState.log("Ошибка: ${e.message}")
                reason = "Ошибка: ${e.message}"
            } finally {
                mainHandler.post { finish(reason) }
            }
        }
    }

    /** Закрывает игру: сначала «Домой», потом завершение фонового процесса игры. */
    private fun closeGame(): Boolean {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(GAME_PACKAGE)
            true
        } catch (e: Exception) {
            BotState.log("Не удалось закрыть игру: ${e.message}")
            false
        }
    }

    private fun relaunchGame(): Boolean {
        val i = packageManager.getLaunchIntentForPackage(GAME_PACKAGE) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(i)
            true
        } catch (e: Exception) {
            BotState.log("Не удалось открыть игру: ${e.message}")
            false
        }
    }

    private fun stopBot(reason: String) {
        job?.cancel()
        job = null
        finish(reason)
    }

    /** Освобождает ресурсы. Вызывается только из главного потока, повторные вызовы безопасны. */
    private fun finish(reason: String) {
        val wasActive = capturer != null || BotState.running.value
        capturer?.release()
        capturer = null
        job = null
        BotState.running.value = false
        if (wasActive) BotState.status.value = reason
        overlay.hideStatus()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        mainScope.cancel()
        capturer?.release()
        capturer = null
        overlay.hideAll()
        BotState.running.value = false
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val stats = BotStats.text.value
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.take(60))
            .setContentText(stats.ifBlank { getString(R.string.app_name) })
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$stats"))
            .setContentIntent(open)
            .addAction(0, "Стоп", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startAsForeground() {
        if (foreground) return
        val n = buildNotification(BotState.status.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
        foreground = true
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
