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

    private enum class Pick { SLOTS, TARGETS, DESELECT, GOLD, ELIXIR, DARK }

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
            Pick.TARGETS -> {
                cfg.upgradeTargets = points.toMutableList()
                toast("Целей прокачки: ${points.size}")
            }
            Pick.DESELECT -> points.firstOrNull()?.let {
                cfg.deselectPoint = it
                toast("Точка пустого места: $it")
            }
            Pick.GOLD -> cfg.goldRect = rectOf(points) ?: cfg.goldRect
            Pick.ELIXIR -> cfg.elixirRect = rectOf(points) ?: cfg.elixirRect
            Pick.DARK -> cfg.darkRect = rectOf(points) ?: cfg.darkRect
        }
        cfg.save(this)
        fillUi()
    }

    private fun rectOf(points: List<PointN>): RectN? =
        if (points.size >= 2) RectN(points[0].x, points[0].y, points[1].x, points[1].y) else null

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
        b.btnPickTargets.setOnClickListener {
            openPicker(
                Pick.TARGETS, PickMode.POINTS, "Цели прокачки",
                "Откройте свою базу и отметьте здания и участки стен, которые бот будет улучшать. Порядок = очередь обхода.",
                cfg.upgradeTargets.map { it.toString() }, 40
            )
        }
        b.btnPickDeselect.setOnClickListener {
            openPicker(
                Pick.DESELECT, PickMode.SINGLE, "Точка пустого места",
                "Отметьте пустую землю или небо: тап по ней снимает выделение здания.",
                listOf(cfg.deselectPoint.toString()), 1
            )
        }
        b.btnRectGold.setOnClickListener { openRect(Pick.GOLD, "Область золота", cfg.goldRect) }
        b.btnRectElixir.setOnClickListener { openRect(Pick.ELIXIR, "Область эликсира", cfg.elixirRect) }
        b.btnRectDark.setOnClickListener { openRect(Pick.DARK, "Область чёрного эликсира", cfg.darkRect) }

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

    private fun openRect(what: Pick, title: String, current: RectN) {
        val initial = if (current.isEmpty) emptyList() else listOf(
            PointN(current.x1, current.y1).toString(),
            PointN(current.x2, current.y2).toString(),
        )
        openPicker(
            what, PickMode.RECT, title,
            "Отметьте два угла прямоугольника вокруг числа на экране выбора базы.",
            initial, 2
        )
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
        b.cbTrain.isChecked = cfg.trainTroops
        b.cbUpgrade.isChecked = cfg.autoUpgrade
        b.etUpgradeEvery.setText(cfg.upgradeEveryAttacks.toString())
        b.etUpgradeMax.setText(cfg.upgradeMaxPerRun.toString())
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
        b.tvTargetsInfo.text = if (cfg.upgradeTargets.isEmpty()) {
            "Цели не заданы: бот сам выбирает улучшения через меню строителей. Отметьте цели, если хотите решать сами."
        } else {
            "Целей прокачки: ${cfg.upgradeTargets.size}, пустое место: ${cfg.deselectPoint}"
        }
        val rects = listOf("золото" to cfg.goldRect, "эликсир" to cfg.elixirRect, "чэ" to cfg.darkRect)
            .filter { !it.second.isEmpty }
            .joinToString(", ") { it.first }
        b.tvLootRects.text = if (rects.isEmpty()) "Области чисел добычи не заданы" else "Области заданы: $rects"
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
        cfg.trainTroops = b.cbTrain.isChecked
        cfg.autoUpgrade = b.cbUpgrade.isChecked
        cfg.upgradeEveryAttacks = b.etUpgradeEvery.int("Прокачка раз в N атак", 1, 50) ?: return false
        cfg.upgradeMaxPerRun = b.etUpgradeMax.int("Целей за заход", 1, 40) ?: return false
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
        if (cfg.lootFilter && cfg.goldRect.isEmpty && cfg.elixirRect.isEmpty && cfg.darkRect.isEmpty) {
            toast("Для фильтра добычи задайте хотя бы одну область числа")
            return false
        }
        cfg.save(this)
        return true
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
