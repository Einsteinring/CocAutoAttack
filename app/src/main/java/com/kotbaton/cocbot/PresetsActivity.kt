package com.kotbaton.cocbot

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kotbaton.cocbot.databinding.ActivityPresetsBinding
import com.kotbaton.cocbot.databinding.ItemPresetBinding

/** Список шаблонов атаки: выбрать активный, изменить, скопировать, удалить. */
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
            AlertDialog.Builder(this)
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
            row.tvName.text = if (active) "● ${preset.name}" else preset.name
            row.tvSummary.text = preset.summary()
            row.btnSelect.isEnabled = !active
            row.btnSelect.setOnClickListener {
                cfg.presetName = preset.name
                cfg.save(this)
                render()
                toast("Активный шаблон: ${preset.name}")
            }
            row.btnEdit.setOnClickListener { edit(preset.name) }
            row.btnCopy.setOnClickListener {
                presets.add(preset.copyWithName(uniqueName("${preset.name} (копия)")))
                PresetStore.save(this, presets)
                render()
            }
            row.btnDelete.setOnClickListener {
                if (presets.size <= 1) {
                    toast("Последний шаблон удалить нельзя")
                    return@setOnClickListener
                }
                AlertDialog.Builder(this)
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
            b.container.addView(row.root)
        }
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
