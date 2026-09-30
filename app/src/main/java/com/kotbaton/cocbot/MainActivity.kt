package com.kotbaton.cocbot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kotbaton.cocbot.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/** Главный экран: запуск сценариев, выбор шаблона атаки, статус и лог. */
class MainActivity : AppCompatActivity() {

    private companion object {
        val UPGRADE_TEMPLATES = setOf("builder", "wall_row", "upgrade", "confirm", "not_enough", "close")
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var cfg: BotConfig
    private var presets: List<AttackPreset> = emptyList()
    private var pendingAction: String? = null

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val action = pendingAction ?: return@registerForActivityResult
            pendingAction = null
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
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
        title = "${getString(R.string.app_name)} ${appVersion()}"
        cfg = BotConfig.load(this)
        BotState.attachFile(this)
        Vision.migrateOwnTemplates(this)

        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        b.btnStart.setOnClickListener { startWithProjection(BotService.ACTION_START) }
        b.btnStop.setOnClickListener { startService(BotService.stopIntent(this)) }
        b.btnFarmFull.setOnClickListener { startWithProjection(BotService.ACTION_FARM_FULL) }
        b.btnUpgrade.setOnClickListener { startWithProjection(BotService.ACTION_UPGRADE) }
        b.btnCollect.setOnClickListener { startWithProjection(BotService.ACTION_COLLECT) }
        b.btnTest.setOnClickListener { startWithProjection(BotService.ACTION_TEST) }
        b.btnScreenshot.setOnClickListener { startWithProjection(BotService.ACTION_SCREENSHOT) }
        b.btnCalibrate.setOnClickListener { showCalibration() }
        b.btnPresets.setOnClickListener { startActivity(Intent(this, PresetsActivity::class.java)) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
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
                launch { BotStats.text.collect { b.tvStats.text = it } }
                launch {
                    BotState.running.collect { running ->
                        b.btnStart.isEnabled = !running
                        b.btnStop.isEnabled = running
                        b.btnTest.isEnabled = !running
                        b.btnScreenshot.isEnabled = !running
                        b.btnUpgrade.isEnabled = !running
                        b.btnFarmFull.isEnabled = !running
                        b.btnCollect.isEnabled = !running
                    }
                }
                launch {
                    BotState.log.collect { lines ->
                        b.tvLog.text = lines.joinToString("\n")
                        b.scrollLog.post { b.scrollLog.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cfg = BotConfig.load(this)
        fillPresets()
        showReadiness()
    }

    private fun fillPresets() {
        presets = PresetStore.load(this)
        val names = presets.map { it.name }
        b.spPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val index = names.indexOf(cfg.presetName).takeIf { it >= 0 } ?: 0
        b.spPreset.setSelection(index)
        updatePresetInfo(index)
        b.spPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val p = presets.getOrNull(position) ?: return
                if (cfg.presetName != p.name) {
                    cfg.presetName = p.name
                    cfg.save(this@MainActivity)
                }
                updatePresetInfo(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun updatePresetInfo(index: Int) {
        val p = presets.getOrNull(index)
        b.tvPresetInfo.text = if (p == null) "" else p.summary()
    }

    /** Строка «всё ли готово»: разрешения, шаблоны кнопок, цели прокачки. */
    private fun showReadiness() {
        val problems = mutableListOf<String>()
        if (BotAccessibilityService.instance == null) problems += "нет службы доступности"
        if (!Settings.canDrawOverlays(this)) problems += "нет показа поверх окон"
        val vision = Vision(this) { cfg.threshold }
        val missing = vision.missingFor(cfg)
        val missingCore = missing.filter { it in Vision.CORE }
        if (missingCore.isNotEmpty()) {
            problems += "нет шаблонов: ${missingCore.joinToString()}"
        } else if (cfg.autoUpgrade && missing.any { it in UPGRADE_TEMPLATES }) {
            problems += "прокачка стен будет пропущена: нет ${missing.filter { it in UPGRADE_TEMPLATES }.joinToString()}"
        }
        b.tvReady.text = if (problems.isEmpty()) {
            "✔ Всё готово к запуску"
        } else {
            "✖ " + problems.joinToString("; ")
        }
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
            val preset = presets.getOrNull(b.spPreset.selectedItemPosition)
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

    /** Версия из манифеста, например «0.2». */
    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
