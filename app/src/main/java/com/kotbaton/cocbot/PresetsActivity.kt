package com.kotbaton.cocbot

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kotbaton.cocbot.databinding.ActivityPresetsBinding
import com.kotbaton.cocbot.databinding.ItemPresetBinding

/** Список шаблонов атаки: тап по строке выбирает активный, карандаш открывает редактор, меню «ещё» копирует и удаляет. */
class PresetsActivity : AppCompatActivity() {

    private lateinit var b: ActivityPresetsBinding
    private lateinit var cfg: BotConfig
    private var presets: MutableList<AttackPreset> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPresetsBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "Шаблоны атак"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.btnNew.setOnClickListener {
            val preset = AttackPreset(
                name = uniqueName("Мой шаблон"),
                steps = mutableListOf(DeployStep(0, 20, listOf(0, 1))),
            )
            presets.add(preset)
            PresetStore.save(this, presets)
            edit(preset.name)
        }
        b.btnReset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Вернуть стандартные шаблоны?")
                .setMessage("Ваши изменения в шаблонах будут потеряны.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Вернуть") { _, _ ->
                    presets = PresetStore.resetToBuiltIn(this)
                    render()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        cfg = BotConfig.load(this)
        presets = PresetStore.load(this)
        render()
    }

    private fun uniqueName(base: String): String {
        var name = base
        var i = 2
        while (presets.any { it.name == name }) {
            name = "$base $i"
            i++
        }
        return name
    }

    private fun render() {
        b.container.removeAllViews()
        for (preset in presets.toList()) {
            val row = ItemPresetBinding.inflate(layoutInflater, b.container, false)
            val active = preset.name == cfg.presetName
            row.ivRadio.setImageResource(if (active) R.drawable.radio_on else R.drawable.radio_off)
            row.tvName.text = preset.name
            row.tvName.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            row.tvSummary.text = preset.summary()
            row.root.background = if (active) ContextCompat.getDrawable(this, R.drawable.bg_row_active) else null
            row.root.setOnClickListener {
                if (active) return@setOnClickListener
                cfg.presetName = preset.name
                cfg.save(this)
                render()
                toast("Активный шаблон: ${preset.name}")
            }
            row.btnEdit.setOnClickListener { edit(preset.name) }
            row.btnMore.setOnClickListener { v -> showMenu(v, preset) }
            b.container.addView(row.root)
        }
    }

    private fun showMenu(anchor: View, preset: AttackPreset) {
        val menu = PopupMenu(this, anchor, Gravity.END)
        menu.menuInflater.inflate(R.menu.preset_item, menu.menu)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_copy -> {
                    presets.add(preset.copyWithName(uniqueName("${preset.name} (копия)")))
                    PresetStore.save(this, presets)
                    render()
                    true
                }
                R.id.action_delete -> {
                    confirmDelete(preset)
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun confirmDelete(preset: AttackPreset) {
        if (presets.size <= 1) {
            toast("Последний шаблон удалить нельзя")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить «${preset.name}»?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                presets.removeAll { it.name == preset.name }
                PresetStore.save(this, presets)
                if (cfg.presetName == preset.name) {
                    cfg.presetName = presets.first().name
                    cfg.save(this)
                }
                render()
            }
            .show()
    }

    private fun edit(name: String) {
        startActivity(
            Intent(this, PresetEditorActivity::class.java)
                .putExtra(PresetEditorActivity.EXTRA_NAME, name)
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
