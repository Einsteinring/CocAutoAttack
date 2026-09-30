package com.kotbaton.cocbot

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kotbaton.cocbot.databinding.ActivityTemplatesBinding
import com.kotbaton.cocbot.databinding.ItemTemplateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Шаблоны кнопок: что есть (своё или встроенное), чего не хватает,
 * вырезать из снимка, взять готовый PNG и выгрузить всё архивом в «Загрузки».
 */
class TemplatesActivity : AppCompatActivity() {

    private lateinit var b: ActivityTemplatesBinding
    private lateinit var vision: Vision
    private var importName: String? = null

    private val fileImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = importName
        importName = null
        if (uri == null || name == null) return@registerForActivityResult
        importFile(uri, name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTemplatesBinding.inflate(layoutInflater)
        setContentView(b.root)
        title = "Шаблоны кнопок"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        vision = Vision(this) { 0.8 }

        b.btnImport.setOnClickListener { chooseNameThenImport() }
        b.btnCut.setOnClickListener { chooseNameThenCut() }
        b.btnExport.setOnClickListener { export() }
    }

    override fun onResume() {
        super.onResume()
        vision.clearCache()
        render()
    }

    private fun render() {
        val cfg = BotConfig.load(this)
        val needed = vision.missingFor(cfg).toSet()
        val shots = PointPickerActivity.latestScreenshot(this)
        val bundled = vision.bundledCount()
        b.tvPath.text = if (bundled > 0) {
            "Встроено в приложение: $bundled шаблонов, язык игры: ${vision.bundledLang().ifBlank { "?" }}"
        } else {
            "Встроенных шаблонов в этой сборке нет"
        }
        b.tvShot.text = if (shots == null) {
            "Снимков экрана пока нет. Нажмите «Скриншот» на главном экране."
        } else {
            val count = vision.screensDir.listFiles { f -> f.name.endsWith(".png", true) }?.size ?: 0
            "Снимков экрана: $count, последний: ${shots.name}"
        }

        b.container.removeAllViews()
        val catalog = Vision.catalog()
        addSection("Обязательные", catalog.filter { it.core }, needed)
        addSection("Для отдельных функций", catalog.filter { !it.core }, needed)
    }

    /** Заголовок раздела и карточка со строками шаблонов. */
    private fun addSection(title: String, items: List<TemplateInfo>, needed: Set<String>) {
        val header = TextView(this).apply {
            text = title
            setTextAppearance(R.style.TextAppearance_CocBot_Section)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(2), dp(12), dp(2), dp(8)) }
        }
        b.container.addView(header)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@TemplatesActivity, R.drawable.bg_card)
            dividerDrawable = ContextCompat.getDrawable(this@TemplatesActivity, R.drawable.divider)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        for (info in items) card.addView(row(info, needed, card))
        b.container.addView(card)
    }

    private fun row(info: TemplateInfo, needed: Set<String>, parent: ViewGroup): View {
        val row = ItemTemplateBinding.inflate(layoutInflater, parent, false)
        val file = File(vision.templatesDir, "${info.name}.png")
        val own = file.exists()
        val inApk = vision.isBundled(info.name)
        row.tvName.text = "${info.name}.png"
        val mark = when {
            info.core -> "обязательный"
            info.name in needed -> "нужен для включённой функции"
            else -> "по желанию"
        }
        row.tvAbout.text = "${info.about} · $mark"
        when {
            own -> badge(row.tvSize, "Своя · ${sizeOf(file)}", R.drawable.bg_pill_accent, R.color.accent)
            inApk -> badge(row.tvSize, "Встроенная", R.drawable.bg_pill, R.color.text_2)
            else -> badge(row.tvSize, "Нет", R.drawable.bg_pill_danger, R.color.danger)
        }
        row.btnMore.setOnClickListener { v -> showMenu(v, info, own, inApk, file) }
        return row.root
    }

    private fun badge(view: TextView, text: String, bg: Int, color: Int) {
        view.text = text
        view.setBackgroundResource(bg)
        view.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun showMenu(anchor: View, info: TemplateInfo, own: Boolean, inApk: Boolean, file: File) {
        val menu = PopupMenu(this, anchor, Gravity.END)
        menu.menuInflater.inflate(R.menu.template_item, menu.menu)
        menu.menu.findItem(R.id.action_delete).isEnabled = own
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_cut -> {
                    cut(info.name)
                    true
                }
                R.id.action_import -> {
                    importFor(info.name)
                    true
                }
                R.id.action_delete -> {
                    val note = if (inApk) "После удаления будет использоваться встроенная." else null
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Удалить свою ${info.name}.png?")
                        .setMessage(note)
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить") { _, _ ->
                            file.delete()
                            vision.clearCache()
                            render()
                        }
                        .show()
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun sizeOf(file: File): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return if (opts.outWidth > 0) "${opts.outWidth}×${opts.outHeight}" else "файл не читается"
    }

    private fun cut(name: String) {
        if (PointPickerActivity.latestScreenshot(this) == null) {
            toast("Сначала нажмите «Скриншот» на главном экране")
            return
        }
        startActivity(Intent(this, TemplateCutterActivity::class.java).putExtra(TemplateCutterActivity.EXTRA_NAME, name))
    }

    private fun importFor(name: String) {
        importName = name
        fileImport.launch(arrayOf("image/*"))
    }

    private fun chooseName(title: String, onPick: (String) -> Unit) {
        val items = Vision.catalog()
        val labels = items.map { "${it.name}.png — ${it.about}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(labels) { _, which -> onPick(items[which].name) }
            .show()
    }

    private fun chooseNameThenImport() = chooseName("Чем будет этот файл?") { importFor(it) }

    private fun chooseNameThenCut() = chooseName("Что вырезаем?") { cut(it) }

    /** Перекладывает выбранный PNG в папку шаблонов под нужным именем. */
    private fun importFile(uri: Uri, name: String) {
        try {
            val bitmap = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                toast("Не удалось открыть картинку")
                return
            }
            if (bitmap.width < 8 || bitmap.height < 8) {
                toast("Картинка слишком маленькая")
                return
            }
            val scaled = Vision.toWorkScale(bitmap, screenShortSide())
            val file = File(vision.templatesDir, "$name.png")
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            vision.clearCache()
            BotState.log("Шаблон $name.png добавлен (${scaled.width}×${scaled.height})")
            toast("$name.png добавлен, ${scaled.width}×${scaled.height}")
            render()
        } catch (e: Exception) {
            toast("Ошибка импорта: ${e.message}")
        }
    }

    /** Высота экрана в альбомной ориентации: из такого кадра обычно режут кнопки. */
    private fun screenShortSide(): Int {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return minOf(metrics.widthPixels, metrics.heightPixels)
    }

    // --- Выгрузка ---

    private fun export() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast("Выгрузка работает на Android 10 и новее")
            return
        }
        b.btnExport.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { writeExportZip() } }
            b.btnExport.isEnabled = true
            result.onSuccess { name ->
                MaterialAlertDialogBuilder(this@TemplatesActivity)
                    .setTitle("Архив готов")
                    .setMessage(
                        "Файл $name лежит в папке «Загрузки».\n\n" +
                            "Внутри все снимки экрана, ваши вырезки, лог и сведения о телефоне. " +
                            "Его можно переслать через Telegram или скопировать на компьютер."
                    )
                    .setPositiveButton("Понятно", null)
                    .show()
            }.onFailure { toast("Не удалось выгрузить: ${it.message}") }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeExportZip(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val name = "CocAutoAttack_$stamp.zip"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("папка «Загрузки» недоступна")
        val out = contentResolver.openOutputStream(uri) ?: error("не открылся файл для записи")
        out.use { stream ->
            ZipOutputStream(stream).use { zip ->
                addDir(zip, vision.screensDir, "screens")
                addDir(zip, vision.templatesDir, "templates")
                val log = File(getExternalFilesDir(null), "bot.log")
                if (log.exists()) addFile(zip, log, "bot.log")
                zip.putNextEntry(ZipEntry("device.txt"))
                zip.write(deviceInfo(this).toByteArray())
                zip.closeEntry()
            }
        }
        return name
    }

    private fun addDir(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { addFile(zip, it, "$prefix/${it.name}") }
    }

    private fun addFile(zip: ZipOutputStream, file: File, entry: String) {
        zip.putNextEntry(ZipEntry(entry))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

/** Сведения о телефоне для калибровки встроенных шаблонов. */
internal fun deviceInfo(ctx: Context): String {
    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
    val game = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(BotService.GAME_PACKAGE, 0).versionName ?: "?"
    } catch (e: Exception) {
        "не установлена"
    }
    val app = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
    return buildString {
        appendLine("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Экран: ${maxOf(metrics.widthPixels, metrics.heightPixels)}x${minOf(metrics.widthPixels, metrics.heightPixels)}, dpi ${metrics.densityDpi}")
        appendLine("Clash of Clans: $game")
        appendLine("CoC AutoAttack: $app")
        appendLine("Язык системы: ${Locale.getDefault()}")
    }
}
