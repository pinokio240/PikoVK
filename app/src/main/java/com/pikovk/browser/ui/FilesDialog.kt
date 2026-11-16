package com.pikovk.browser.ui

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

// Собственный диалог выбора файлов (1.3.38, доработка-27) — аналог
// MediaGalleryDialog для документов. Системный DocumentsUI на устройстве
// владельца сломан: при возврате активити пересоздаётся и результат пикера
// теряется («результат пикера потерян: колбэка нет») — файлы не прикреплялись.
// Здесь список файлов строится напрямую по файловой системе и отдаётся
// странице через filePathCallback, без внешней активити; выбранные файлы
// отдаются как content:// через FileProvider (file:// WebView на API 33+
// прочитать не может). Кнопка «Другие файлы…» внутри диалога — запасной
// путь к произвольным файлам: открывает системный DocumentsUI уже из
// стабильного состояния, минуя пересоздание перед самым пикером.
object FilesDialog {

    private val io = Executors.newSingleThreadExecutor()

    // onResult: choice — выбранные uri (null — закрытие/переход к Другим файлам)
    fun show(
        activity: MainActivity,
        multiple: Boolean,
        onResult: (choice: Array<Uri>?) -> Unit
    ) {
        io.execute {
            val dirs = collectRoots(activity)
            val files = ArrayList<File>()
            for (dir in dirs) {
                val kids = dir.listFiles() ?: continue
                for (f in kids) {
                    if (!f.isFile) continue
                    if (f.name.startsWith(".")) continue
                    files.add(f)
                }
            }
            files.sortByDescending { it.lastModified() }
            activity.runOnUiThread {
                if (files.isEmpty()) {
                    // Доступных для чтения файлов не нашлось — сразу запасной
                    // системный пикер.
                    onResult(null)
                } else {
                    build(activity, files, multiple, onResult)
                }
            }
        }
    }

    // Корни, файлы которых приложение может читать и отдавать через FileProvider.
    // На Android 13+ без полного доступа перечисляются гарантированно читаемые
    // каталоги: специфичные для приложения (getExternalFilesDir) и стандартные
    // Downloads/DCIM/Movies/Pictures. Произвольные документы с медиа-разрешениями
    // Android не отдаёт — для них владелец использует «Другие файлы…».
    private fun collectRoots(activity: MainActivity): ArrayList<File> {
        val roots = ArrayList<File>()
        fun add(dir: File?) {
            if (dir != null) {
                try {
                    if (dir.exists() || dir.mkdirs()) roots.add(dir)
                } catch (_: Exception) {
                }
            }
        }
        add(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
        if (Build.VERSION.SDK_INT >= 29) {
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
        }
        add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
        add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
        add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
        val result = ArrayList<File>()
        val seen = HashSet<String>()
        for (d in roots) {
            val f = d.canonicalFile
            if (seen.add(f.absolutePath)) result.add(f)
        }
        return result
    }

    private fun build(
        activity: MainActivity,
        files: ArrayList<File>,
        multiple: Boolean,
        onResult: (Array<Uri>?) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val sel = LinkedHashSet<File>()
        val hint = TextView(activity).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            text = "Тап — выбрать, повторный — снять. Выбрано: 0"
        }
        val list = ListView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = files.size
            override fun getItem(position: Int) = files[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = convertView as? TextView
                    ?: TextView(parent.context).apply {
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        textSize = 15f
                        setTextColor(0xFFE0E0E0.toInt())
                    }
                val f = files[position]
                tv.text = f.name
                tv.alpha = if (f in sel) 1f else 0.6f
                return tv
            }
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(list)
        }

        var finished = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Файлы")
            .setView(root)
            .setPositiveButton("Прикрепить", null)
            .setNeutralButton("Другие файлы…") { dlg, _ ->
                if (!finished) {
                    finished = true
                    dlg.dismiss()
                    onResult(null) // сигнал открыть DocumentsUI
                }
            }
            .setNegativeButton("Отмена") { _, _ ->
                if (!finished) {
                    finished = true
                    onResult(null)
                }
            }
            .setOnCancelListener {
                if (!finished) {
                    finished = true
                    onResult(null)
                }
            }
            .create()
        dialog.show()
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.isEnabled = false
        ok.setOnClickListener {
            if (!finished && sel.isNotEmpty()) {
                finished = true
                dialog.dismiss()
                onResult(
                    sel.map { toContentUri(activity, it) }.toTypedArray()
                )
            }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val f = files[position]
            if (f in sel) sel.remove(f) else {
                if (!multiple) sel.clear()
                sel.add(f)
            }
            hint.text = "Тап — выбрать, повторный — снять. Выбрано: ${sel.size}"
            ok.text = "Прикрепить (${sel.size})"
            ok.isEnabled = sel.isNotEmpty()
            (list.adapter as BaseAdapter).notifyDataSetChanged()
        }
    }

    // FileProvider content://-uri: WebView должен иметь право читать результат.
    // Использует authority из AndroidManifest (см. <provider> .filesProvider).
    private fun toContentUri(activity: MainActivity, file: File): Uri =
        try {
            FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.filesProvider",
                file
            )
        } catch (_: IllegalArgumentException) {
            // файл вне объявленных путей FileProvider (не должно случиться,
            // если все корни из collectRoots включены в file_paths) — на
            // всякий случай честный file://
            Uri.fromFile(file)
        }
}