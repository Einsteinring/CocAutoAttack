package com.kotbaton.cocbot

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.kotbaton.cocbot.databinding.ActivitySettingsBinding
import java.util.Locale

/** Общие настройки: панель войск, дела между боями, фильтр добычи, поведение. */
class SettingsActivity : AppCompatActivity() {

    private enum class Pick { SLOTS }

    private lateinit var b: ActivitySettingsBinding
    private lateinit var cfg: BotConfig
    private var pending: Pick? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val what = pending
        pending = null
        if (res.resultCode != Activity.RESULT_OK || what == null) return@registerForActivityResult
        val points = res.data?.getStringArrayListExtra(PointPickerActivity.RESULT_POINTS)
            .orEmpty()
            .mapNotNull { PointN.parse(it) }
        when (what) {
            Pick.SLOTS -> {
                cfg.slotPoints = points.toMutableList()
                if (points.isNotEmpty()) cfg.slotCount = points.size
                toast("Карточек отмечено: ${points.size}")
            }
        }
        cfg.save(this)
        fillUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "Настройки"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        cfg = BotConfig.load(this)
        fillUi()

        b.btnPickSlots.setOnClickListener {
            openPicker(
                Pick.SLOTS, PickMode.POINTS, "Карточки войск",
                "Тапните по каждой карточке слева направо. Порядок = номера, которые вы пишете в шагах шаблона.",
                cfg.slotPoints.map { it.toString() }, 14
            )
        }
        b.btnSave.setOnClickListener {
            if (save()) {
                toast("Сохранено")
                finish()
            }
        }
    }

    private fun openPicker(what: Pick, mode: PickMode, title: String, hint: String, initial: List<String>, max: Int) {
        if (PointPickerActivity.latestScreenshot(this) == null) {
            toast("Сначала сделайте скриншот нужного экрана игры")
            return
        }
        pending = what
        picker.launch(PointPickerActivity.intent(this, mode, title, hint, initial, max))
    }

    private fun fillUi() {
        b.etMaxAttacks.setText(cfg.maxAttacks.toString())
        b.etThreshold.setText(String.format(Locale.US, "%.2f", cfg.threshold))
        b.etBattleTimeout.setText(cfg.battleTimeoutSec.toString())
        b.etSlotY.setText(fmtN(cfg.slotY))
        b.etSlotFirstX.setText(fmtN(cfg.slotFirstX))
        b.etSlotStepX.setText(fmtN(cfg.slotStepX))
        b.etSlotCount.setText(cfg.slotCount.toString())
        b.cbCollect.isChecked = cfg.collectResources
        b.cbUpgrade.isChecked = cfg.autoUpgrade
        b.etUpgradeEvery.setText(cfg.upgradeEveryAttacks.toString())
        b.cbStopFull.isChecked = cfg.stopWhenFull
        b.cbFullGold.isChecked = cfg.fullGold
        b.cbFullElixir.isChecked = cfg.fullElixir
        b.cbFullDark.isChecked = cfg.fullDark
        b.cbLootFilter.isChecked = cfg.lootFilter
        b.etMinGold.setText(cfg.minGold.toString())
        b.etMinElixir.setText(cfg.minElixir.toString())
        b.etMinDark.setText(cfg.minDark.toString())
        b.etMaxSkips.setText(cfg.maxSkips.toString())
        b.etSkipBases.setText(cfg.skipBases.toString())
        b.cbHumanize.isChecked = cfg.humanize
        b.cbRestart.isChecked = cfg.restartGameOnStuck
        b.etPauseMin.setText(cfg.pauseBetweenMinSec.toString())
        b.etPauseMax.setText(cfg.pauseBetweenMaxSec.toString())

        b.tvSlotsInfo.text = if (cfg.slotPoints.isEmpty()) {
            "Карточки считаются по формуле ниже. Точнее — отметить их на скриншоте."
        } else {
            "Отмечено карточек: ${cfg.slotPoints.size} (формула ниже не используется)"
        }
    }

    private fun EditText.int(name: String, min: Int, max: Int): Int? {
        val v = text.toString().trim().toIntOrNull()
        if (v == null || v < min || v > max) {
            toast("«$name»: введите число от $min до $max")
            return null
        }
        return v
    }

    private fun EditText.float(name: String, min: Float, max: Float): Float? {
        val v = text.toString().trim().replace(',', '.').toFloatOrNull()
        if (v == null || v < min || v > max) {
            toast("«$name»: введите число от $min до $max")
            return null
        }
        return v
    }

    private fun save(): Boolean {
        cfg.maxAttacks = b.etMaxAttacks.int("Сколько атак", 1, 10000) ?: return false
        cfg.threshold = (b.etThreshold.float("Порог", 0.5f, 0.99f) ?: return false).toDouble()
        cfg.battleTimeoutSec = b.etBattleTimeout.int("Сдаться через", 10, 300) ?: return false
        cfg.slotY = b.etSlotY.float("Y карточек", 0f, 1f) ?: return false
        cfg.slotFirstX = b.etSlotFirstX.float("X первой карточки", 0f, 1f) ?: return false
        cfg.slotStepX = b.etSlotStepX.float("Шаг X", 0.01f, 0.5f) ?: return false
        cfg.slotCount = b.etSlotCount.int("Карточек", 1, 14) ?: return false
        cfg.collectResources = b.cbCollect.isChecked
        cfg.autoUpgrade = b.cbUpgrade.isChecked
        cfg.upgradeEveryAttacks = b.etUpgradeEvery.int("Прокачка раз в N атак", 1, 50) ?: return false
        cfg.stopWhenFull = b.cbStopFull.isChecked
        cfg.fullGold = b.cbFullGold.isChecked
        cfg.fullElixir = b.cbFullElixir.isChecked
        cfg.fullDark = b.cbFullDark.isChecked
        cfg.lootFilter = b.cbLootFilter.isChecked
        cfg.minGold = b.etMinGold.int("Мин. золото", 0, 20_000_000) ?: return false
        cfg.minElixir = b.etMinElixir.int("Мин. эликсир", 0, 20_000_000) ?: return false
        cfg.minDark = b.etMinDark.int("Мин. чёрный эликсир", 0, 500_000) ?: return false
        cfg.maxSkips = b.etMaxSkips.int("Макс. пропусков", 0, 200) ?: return false
        cfg.skipBases = b.etSkipBases.int("Пропустить баз", 0, 50) ?: return false
        cfg.humanize = b.cbHumanize.isChecked
        cfg.restartGameOnStuck = b.cbRestart.isChecked
        cfg.pauseBetweenMinSec = b.etPauseMin.int("Пауза от", 0, 600) ?: return false
        cfg.pauseBetweenMaxSec = b.etPauseMax.int("Пауза до", 0, 600) ?: return false
        if (cfg.pauseBetweenMaxSec < cfg.pauseBetweenMinSec) {
            toast("Верхняя граница паузы меньше нижней")
            return false
        }
        cfg.save(this)
        return true
    }

    /** Без сообщений: так настройки сохраняются молча, когда пользователь уходит с экрана. */
    private var quiet = false

    private fun toast(msg: String) {
        if (!quiet) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Ушли с экрана (в том числе стрелкой назад) и не нажали «Сохранить»: сохраняем то, что введено. */
    override fun onPause() {
        quiet = true
        try {
            save()
        } finally {
            quiet = false
        }
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
