package com.kotbaton.cocbot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kotbaton.cocbot.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/** Главный экран: готовность к запуску, состояние и счётчики, запуск, инструменты и лог. */
class MainActivity : AppCompatActivity() {

    private companion object {
        val UPGRADE_TEMPLATES = setOf("builder", "wall_row", "upgrade", "confirm", "not_enough", "close")
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var cfg: BotConfig
    private var activePreset: AttackPreset? = null
    private var pendingAction: String? = null

    /** Чем занят текущий заход: подпись под статусом, пока бот работает. */
    private var startedAction: String? = null

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val action = pendingAction ?: return@registerForActivityResult
            pendingAction = null
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                startedAction = action
                val i = Intent(this, BotService::class.java)
                    .setAction(action)
                    .putExtra(BotService.EXTRA_RESULT_CODE, res.resultCode)
                    .putExtra(BotService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                launchGame()
            } else {
                toast("Захват экрана не разрешён")
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = getString(R.string.app_name)
        supportActionBar?.subtitle = appVersion()
        cfg = BotConfig.load(this)
        BotState.attachFile(this)
        Vision.migrateOwnTemplates(this)

        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        b.cardPreset.setOnClickListener { startActivity(Intent(this, PresetsActivity::class.java)) }
        b.btnStart.setOnClickListener {
            val farmToFull = b.toggleMode.checkedButtonId == R.id.btn_mode_full
            startWithProjection(if (farmToFull) BotService.ACTION_FARM_FULL else BotService.ACTION_START)
        }
        b.btnStop.setOnClickListener { startService(BotService.stopIntent(this)) }
        b.btnUpgrade.setOnClickListener { startWithProjection(BotService.ACTION_UPGRADE) }
        b.btnCollect.setOnClickListener { startWithProjection(BotService.ACTION_COLLECT) }
        b.btnTest.setOnClickListener { startWithProjection(BotService.ACTION_TEST) }
        b.btnScreenshot.setOnClickListener { startWithProjection(BotService.ACTION_SCREENSHOT) }
        b.btnCalibrate.setOnClickListener { showCalibration() }
        b.btnHelp.setOnClickListener { startActivity(Intent(this, TemplatesActivity::class.java)) }
        b.btnClearLog.setOnClickListener { BotState.clearLog() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { BotState.status.collect { b.tvStatus.text = it } }
                launch { BotStats.text.collect { showStats(it) } }
                launch { BotState.running.collect { showRunning(it) } }
                launch {
                    BotState.log.collect { lines ->
                        b.tvLog.text = lines.joinToString("\n")
                        b.scrollLog.post { b.scrollLog.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        cfg = BotConfig.load(this)
        b.btnModeCount.text = attacks(cfg.maxAttacks)
        showPreset()
        showReadiness()
    }

    /** Карточка активного шаблона. Если сохранённого имени больше нет, берётся первый в списке. */
    private fun showPreset() {
        val p = PresetStore.active(this, cfg.presetName)
        activePreset = p
        if (cfg.presetName != p.name) {
            cfg.presetName = p.name
            cfg.save(this)
        }
        b.tvPresetName.text = p.name
        b.tvPresetInfo.text = p.summary()
    }

    /** Чек-лист «Перед запуском»: разрешения и шаблоны кнопок. */
    private fun showReadiness() {
        readyRow(b.dotAccessibility, b.tvAccessibilityState, b.btnAccessibility, BotAccessibilityService.instance != null)
        readyRow(b.dotOverlay, b.tvOverlayState, b.btnOverlay, Settings.canDrawOverlays(this))

        val vision = Vision(this) { cfg.threshold }
        val missing = vision.missingFor(cfg)
        val missingCore = missing.filter { it in Vision.CORE }
        val missingUpgrade = missing.filter { it in UPGRADE_TEMPLATES }
        val catalog = Vision.catalog()
        val present = catalog.count { vision.has(it.name) }
        val (text, colorRes) = when {
            missingCore.isNotEmpty() -> "нет ${missingCore.joinToString()}" to R.color.danger
            cfg.autoUpgrade && missingUpgrade.isNotEmpty() ->
                "стены пропустим: нет ${missingUpgrade.joinToString()}" to R.color.accent
            else -> "$present из ${catalog.size}" to R.color.ok
        }
        b.tvTemplatesState.text = text
        b.tvTemplatesState.setTextColor(color(colorRes))
        b.dotTemplates.backgroundTintList = ColorStateList.valueOf(color(colorRes))
    }

    private fun readyRow(dot: View, state: TextView, action: Button, ok: Boolean) {
        dot.backgroundTintList = ColorStateList.valueOf(color(if (ok) R.color.ok else R.color.danger))
        state.visibility = if (ok) View.VISIBLE else View.GONE
        action.visibility = if (ok) View.GONE else View.VISIBLE
    }

    /** Плитки счётчиков; редкие счётчики и добыча строкой под ними, только когда они есть. */
    private fun showStats(text: String) {
        b.tvStatAttacks.text = BotStats.attacks.toString()
        b.tvStatSkipped.text = BotStats.skipped.toString()
        b.tvStatUpgrades.text = BotStats.upgrades.toString()
        b.tvStatMinutes.text = BotStats.minutes().toString()
        val extra = text.split(" · ").filter {
            it.startsWith("сборов") || it.startsWith("сдач") || it.startsWith("добыча")
        }
        b.tvStats.text = extra.joinToString(" · ")
        b.tvStats.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showRunning(running: Boolean) {
        b.groupStart.visibility = if (running) View.GONE else View.VISIBLE
        b.groupStop.visibility = if (running) View.VISIBLE else View.GONE
        b.cardPreset.visibility = if (running) View.GONE else View.VISIBLE
        b.tvRunningLabel.visibility = if (running) View.VISIBLE else View.GONE
        b.dotStatus.visibility = if (running) View.GONE else View.VISIBLE
        b.cardStatus.strokeColor = color(if (running) R.color.ok_border else R.color.border)
        b.tvToolsHint.visibility = if (running) View.VISIBLE else View.GONE
        b.btnTest.isEnabled = !running
        b.btnScreenshot.isEnabled = !running
        b.btnUpgrade.isEnabled = !running
        b.btnCollect.isEnabled = !running

        val p = activePreset
        if (running && p != null) {
            val mode = when (startedAction) {
                BotService.ACTION_START -> " · режим «${attacks(cfg.maxAttacks)}»"
                BotService.ACTION_FARM_FULL -> " · режим «До полных хранилищ»"
                BotService.ACTION_UPGRADE -> " · только стены"
                BotService.ACTION_COLLECT -> " · только сбор"
                else -> ""
            }
            b.tvStatusSub.text = p.name + mode
            b.tvStatusSub.visibility = View.VISIBLE
        } else {
            b.tvStatusSub.visibility = View.GONE
        }
        if (!running) startedAction = null
    }

    private fun showCalibration() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Сначала разрешите показ поверх окон")
            return
        }
        startService(Intent(this, BotService::class.java).setAction(BotService.ACTION_CALIBRATE))
        launchGame()
    }

    private fun startWithProjection(action: String) {
        val needsTaps = action == BotService.ACTION_START ||
            action == BotService.ACTION_FARM_FULL ||
            action == BotService.ACTION_UPGRADE ||
            action == BotService.ACTION_COLLECT
        if (needsTaps && BotAccessibilityService.instance == null) {
            toast("Сначала включите службу доступности")
            return
        }
        if (action == BotService.ACTION_START || action == BotService.ACTION_FARM_FULL) {
            val preset = activePreset
            if (preset == null || preset.steps.isEmpty()) {
                toast("В выбранном шаблоне нет шагов высадки")
                return
            }
        }
        pendingAction = action
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage(BotService.GAME_PACKAGE)
        if (i != null) {
            startActivity(i)
        } else {
            toast("Clash of Clans не найден, откройте игру вручную")
        }
    }

    /** «1 атака», «3 атаки», «20 атак». */
    private fun attacks(n: Int): String {
        val word = when {
            n % 100 in 11..19 -> "атак"
            n % 10 == 1 -> "атака"
            n % 10 in 2..4 -> "атаки"
            else -> "атак"
        }
        return "$n $word"
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    /** Версия из манифеста, например «0.2». */
    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
