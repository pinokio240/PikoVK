package com.pikovk.browser.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 1.3.31 (доработка-21): диагностический журнал прикрепления файлов.
// Репорты владельца («файлы не прикрепляются в диалогах») без logcat не
// дают признака, ГДЕ рвётся цепочка: страница → input[type=file] →
// onShowFileChooser → пикер → результат → страница → upload-сервер VK.
// Все нативные звенья (MainActivity: startFileChooser, fileChooser,
// storagePerm, launchFileChooserNow) и JS-звенья (метки [PikoVK] из
// DIAG_JS через VkWebChromeClient.onConsoleMessage) пишут сюда. Журнал
// показывается в Настройках («Диагностика прикрепления») — владельцу не
// нужен adb. Отдельно от logcat: файл маленький, только эти звенья.
object DiagLog {
    private const val FILE = "piko_diag.log"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(ctx: Context, msg: String) {
        Log.i("PikoVK-Diag", msg)
        try {
            File(ctx.filesDir, FILE).appendText("${fmt.format(Date())} $msg\n")
        } catch (_: Exception) {
            // журнал не должен ломать само прикрепление
        }
    }

    @Synchronized
    fun read(ctx: Context): String = try {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) f.readText() else "(журнал пуст — прикрепление ещё не пробовали)"
    } catch (_: Exception) {
        "(журнал недоступен)"
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, FILE).delete()
        } catch (_: Exception) {
        }
    }
}
