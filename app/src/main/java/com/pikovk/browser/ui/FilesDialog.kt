package com.pikovk.browser.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

// 1.3.38 (доработка-27): собственный файловый менеджер для прикрепления
// произвольных файлов — аналог MediaGalleryDialog для документов. Системный
// DocumentsUI на устройстве владельца сломан: при возврате активити
// пересоздаётся и результат пикера теряется («результат пикера потерян:
// колбэка нет») — файлы не прикреплялись. Здесь список строится напрямую
// по файловой системе: старт в Download, навигация по папкам, «Вверх»;
// выбранные файлы отдаются странице как content:// через FileProvider
// (file:// WebView на API 33+ прочитать не может) прямо через
// filePathCallback — канал «результат активити», в котором системные
// пикеры теряли payload, не используется вовсе.
// Доступ к общему хранилищу диалог запрашивает сам (то, что просил
// владелец): Android 11+ — «Все файлы» через настройки
// (Environment.isExternalStorageManager(), только SDK>=30; переключатель
// работает лишь при объявленном в манифесте MANAGE_EXTERNAL_STORAGE),
// Android 8–10 — READ_EXTERNAL_STORAGE обычным runtime-запросом. Без
// доступа listFiles() возвращает null — показывается «Нет доступа к
// папке», «Вверх» остаётся доступен, чтение повторяется тапом по строке.
// MANAGE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE — осознанный
// запасной вариант. Запасной DocumentsUI включается только при отказе
// в разрешениях (MainActivity.launchFilesDialog) и в MODE_SAVE.
object FilesDialog {

    private val io = Executors.newSingleThreadExecutor()

    // Лимит элементов на одну папку (как LIMIT в MediaGalleryDialog):
    // каталог с тысячами записей не должен подвешивать ListView;
    // превышение помечается в хинте.
    private const val LIMIT = 500

    // Содержимое одной папки: children — записи (папки первыми), total —
    // сколько было до отсечения по LIMIT, truncated — отсекали ли.
    private class Listing(
        val children: ArrayList<File>,
        val total: Int,
        val truncated: Boolean
    )

    // onResult: choice — выбранные uri (null — отмена/закрытие)
    fun show(
        activity: MainActivity,
        multiple: Boolean,
        onResult: (choice: Array<Uri>?) -> Unit
    ) {
        io.execute {
            val start = startDir(activity)
            activity.runOnUiThread { build(activity, start, multiple, onResult) }
        }
    }

    // Стартовая папка — Download (как просил владелец); если её нет
    // (null/не существует) — корень внешнего тома; совсем без внешнего
    // тома — каталог файлов приложения, он читается всегда.
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory/getExternalStorageDirectory
    private fun startDir(activity: MainActivity): File {
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (downloads != null && downloads.isDirectory) return downloads
        val root = Environment.getExternalStorageDirectory()
        if (root != null && root.isDirectory) return root
        val appDir = activity.getExternalFilesDir(null)
        if (appDir != null) {
            try {
                appDir.mkdirs()
            } catch (_: Exception) {
            }
            return appDir
        }
        return activity.filesDir
    }

    // Доступ к общему внешнему хранилищу: Android 11+ — «Все файлы»
    // (Environment.isExternalStorageManager(), только SDK>=30), Android 8–10 —
    // READ_EXTERNAL_STORAGE. На Android 11+ media-разрешения дают только
    // медиа, произвольные документы без «Все файлы» не читаются вовсе —
    // поэтому без доступа диалог честно показывает «Нет доступа к папке».
    private fun hasStorageAccess(activity: MainActivity): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    // Запрос доступа (владелец просил запрос разрешения для доступа):
    // Android 11+ — экран настроек «Все файлы» (переключатель действует
    // только при объявленном в манифесте MANAGE_EXTERNAL_STORAGE), Android
    // 8–10 — обычный runtime-запрос. Ответ самим диалогом не ждётся
    // (registerForActivityResult из объекта невозможен, а
    // MainActivity.onRequestPermissionsResult на нас не завязан): после
    // возврата владелец тапом по строке/«Вверх» перечитает папку уже
    // с доступом.
    private fun requestStorageAccess(activity: MainActivity) {
        if (Build.VERSION.SDK_INT >= 30) {
            val own = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + activity.packageName)
            )
            try {
                activity.startActivity(own)
            } catch (_: Exception) {
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                } catch (_: Exception) {
                    // экрана нет — остаёмся в диалоге, доступ не выдать
                }
            }
        } else {
            // requestCode произвольный: ответ приходит в MainActivity и
            // там не обрабатывается — нам он и не нужен, см. комментарий выше
            activity.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                27001
            )
        }
    }

    // Дети папки: null — listFiles()==null (нет доступа). Скрытые файлы
    // пропускаем, папки идут первыми, имена сравниваются без учёта
    // регистра; больше LIMIT — первые LIMIT с пометкой.
    private fun readChildren(dir: File): Listing? {
        val kids = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return null
        val out = ArrayList<File>(kids.size)
        for (f in kids) {
            if (f.name.startsWith(".")) continue
            out.add(f)
        }
        out.sortWith(
            compareByDescending<File> { it.isDirectory }
                .thenComparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        )
        val total = out.size
        if (total > LIMIT) {
            out.subList(LIMIT, total).clear()
        }
        return Listing(out, total, total > LIMIT)
    }

    // Родитель, в который можно подняться: строго внутри внешнего тома
    // (сам том тоже разрешён). null — вверх идти некуда: выше системные
    // каталоги нечитаемы и вне путей FileProvider.
    private fun parentWithinTop(d: File, top: File?): File? {
        if (top == null) return null
        return try {
            val p = d.canonicalFile.parentFile ?: return null
            val ps = p.absolutePath
            val ts = top.absolutePath
            if (ps == ts || ps.startsWith(ts + File.separator)) p else null
        } catch (_: Exception) {
            null
        }
    }

    // Человекочитаемый размер: Б/КБ/МБ/ГБ, одна десятая; «,0» не показываем.
    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " Б"
        var v = bytes / 1024.0
        val units = arrayOf("КБ", "МБ", "ГБ")
        var u = 0
        while (v >= 1024.0 && u < units.size - 1) {
            v /= 1024.0
            u++
        }
        val s = String.format(Locale.getDefault(), "%.1f", v)
        val trimmed = if (s.endsWith(",0") || s.endsWith(".0")) s.dropLast(2) else s
        return trimmed + " " + units[u]
    }

    @Suppress("DEPRECATION") // Environment.getExternalStorageDirectory()
    private fun build(
        activity: MainActivity,
        start: File,
        multiple: Boolean,
        onResult: (Array<Uri>?) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // LinkedHashSet: порядок выбора сохраняется — страница получает
        // файлы в том порядке, в котором владелец тапал.
        val sel = LinkedHashSet<File>()

        // Корень навигации — внешний том; все отдаваемые файлы лежат под
        // ним, что и заявлено в file_paths.xml (external-path «.»).
        val top: File? = try {
            Environment.getExternalStorageDirectory()?.canonicalFile
        } catch (_: Exception) {
            null
        }

        val startCanonical = try { start.canonicalFile } catch (_: Exception) { start }

        // Текущая папка и последняя загрузка; трогаются только с UI-потока.
        var dir = startCanonical
        var listing = Listing(ArrayList<File>(), 0, false)
        var noAccess = false

        val pathView = TextView(activity).apply {
            setPadding(dp(16), dp(12), dp(16), 0)
            textSize = 12f
            setTextColor(0xFF9AA0A6.toInt())
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
            text = startCanonical.absolutePath
        }
        val hint = TextView(activity).apply {
            setPadding(dp(16), dp(6), dp(16), dp(8))
            text = "Загрузка…"
        }
        val up = Button(activity).apply {
            text = "Вверх"
            isEnabled = parentWithinTop(startCanonical, top) != null
        }
        val access = Button(activity).apply {
            text = "Дать доступ к файлам"
            visibility = if (hasStorageAccess(activity)) View.GONE else View.VISIBLE
            setOnClickListener { requestStorageAccess(activity) }
        }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(up, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(access, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val list = ListView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        fun rowOffset() = if (noAccess) 1 else 0

        val adapter = object : BaseAdapter() {
            override fun getCount() = listing.children.size + rowOffset()
            override fun getItem(position: Int): File? = null
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = convertView as? TextView
                    ?: TextView(parent.context).apply {
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        textSize = 15f
                        setTextColor(0xFFE0E0E0.toInt())
                    }
                if (noAccess && position == 0) {
                    // единственная строка при отказе listFiles(): тап — повторить
                    tv.text = "Нет доступа к папке — тапните, чтобы повторить"
                    tv.setTextColor(0xFFE0E0E0.toInt())
                    tv.paint.isFakeBoldText = true
                    tv.alpha = 0.75f
                    return tv
                }
                val f = listing.children[position - rowOffset()]
                if (f.isDirectory) {
                    // папки — как ссылки: синие и жирные, имя со слэшем
                    tv.text = f.name + "/"
                    tv.setTextColor(0xFF8AB4F8.toInt())
                    tv.paint.isFakeBoldText = true
                } else {
                    tv.text = f.name + " · " + humanSize(f.length())
                    tv.setTextColor(0xFFE0E0E0.toInt())
                    tv.paint.isFakeBoldText = false
                }
                tv.alpha = if (f in sel) 1f else 0.6f
                return tv
            }
        }
        list.adapter = adapter

        fun refreshHint() {
            hint.text = buildString {
                append("Тап — выбрать, повторный — снять. Папка — открыть. Выбрано: ${sel.size}")
                if (!noAccess && listing.truncated) {
                    // файлов в папке больше лимита — честная пометка
                    append("\nПоказаны первые $LIMIT из ${listing.total} элементов.")
                }
            }
        }

        fun open(d: File) {
            val target = try { d.canonicalFile } catch (_: Exception) { d }
            dir = target
            pathView.text = target.absolutePath
            io.execute {
                val loaded = readChildren(target)
                activity.runOnUiThread {
                    // пока список грузился, могли уйти в другую папку
                    if (dir == target) {
                        val accessNow = hasStorageAccess(activity)
                        access.visibility = if (accessNow) View.GONE else View.VISIBLE
                        up.isEnabled = parentWithinTop(target, top) != null
                        if (loaded == null) {
                            // listFiles()==null — нет доступа, не крашится
                            noAccess = true
                            hint.text = "Нет доступа к папке. Нажмите «Дать доступ к файлам», выдайте разрешение, вернитесь — и тапните по строке, чтобы повторить."
                            adapter.notifyDataSetChanged()
                        } else {
                            noAccess = false
                            listing = loaded
                            refreshHint()
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }

        up.setOnClickListener {
            parentWithinTop(dir, top)?.let { open(it) }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(pathView)
            addView(hint)
            addView(buttons)
            addView(list)
        }

        var finished = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Файлы")
            .setView(root)
            .setPositiveButton("Прикрепить", null)
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
        ok.text = "Прикрепить (0)"
        ok.isEnabled = false
        ok.setOnClickListener {
            if (!finished && sel.isNotEmpty()) {
                finished = true
                onResult(sel.map { toContentUri(activity, it) }.toTypedArray())
                dialog.dismiss()
            }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            if (finished) return@setOnItemClickListener
            if (noAccess && position == 0) {
                open(dir) // повторная попытка чтения той же папки
                return@setOnItemClickListener
            }
            val f = listing.children[position - rowOffset()]
            if (f.isDirectory) {
                open(f)
            } else if (!multiple) {
                // одиночный режим: немедленный результат одним тапом
                finished = true
                onResult(arrayOf(toContentUri(activity, f)))
                dialog.dismiss()
            } else {
                if (f in sel) sel.remove(f) else sel.add(f)
                refreshHint()
                ok.text = "Прикрепить (${sel.size})"
                ok.isEnabled = sel.isNotEmpty()
                adapter.notifyDataSetChanged()
            }
        }

        open(startCanonical)

        // владелец просил запрос разрешения: если доступа нет — просим сразу
        if (!hasStorageAccess(activity)) {
            requestStorageAccess(activity)
        }
    }

    // FileProvider content://-uri: WebView с allowContentAccess=true читает
    // контент собственного приложения без явного гранта. Authority — из
    // AndroidManifest (<provider> ${applicationId}.filesProvider, совпадает
    // с packageName в рантайме). Пути — res/xml/file_paths.xml: external-path
    // «.» (весь внешний том, ровно те корни, куда ведёт навигация) и
    // external-files-path (каталог приложения).
    private fun toContentUri(activity: MainActivity, file: File): Uri =
        try {
            FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.filesProvider",
                file
            )
        } catch (_: IllegalArgumentException) {
            // файл вне объявленных путей FileProvider (навигация выше корня
            // внешнего тома не пускает — случиться не должно; путь-запаска
            // на случай старта из filesDir при отсутствии внешнего тома).
            // Честный file:// из внешнего хранилища WebView с
            // allowFileAccess=true прочтёт при выданном
            // MANAGE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE — осознанный
            // запасной вариант.
            Uri.fromFile(file)
        }
}
