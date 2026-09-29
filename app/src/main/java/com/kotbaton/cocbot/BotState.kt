package com.kotbaton.cocbot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Общее состояние бота, за которым следят активити, служба и оверлей. */
object BotState {
    const val TAG = "CocBot"

    val running = MutableStateFlow(false)
    val status = MutableStateFlow("Остановлен")
    val log = MutableStateFlow<List<String>>(emptyList())

    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fileStamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    @Volatile
    private var logFile: File? = null

    /** Включает запись лога в файл. Вызывается из активити и службы. */
    fun attachFile(ctx: Context) {
        if (logFile != null) return
        val f = File(ctx.getExternalFilesDir(null), "bot.log")
        // Файл не должен расти бесконечно.
        if (f.exists() && f.length() > 512 * 1024) f.delete()
        logFile = f
    }

    fun logFilePath(): String = logFile?.absolutePath ?: "лог в файл не пишется"

    fun log(msg: String) {
        val line = "[${stamp.format(Date())}] $msg"
        log.value = (log.value + line).takeLast(400)
        Log.i(TAG, msg)
        val f = logFile ?: return
        try {
            synchronized(this) {
                f.appendText("${fileStamp.format(Date())} $msg\n")
            }
        } catch (e: Exception) {
            // Запись в файл не должна ломать работу бота.
        }
    }

    fun clearLog() {
        log.value = emptyList()
    }
}
