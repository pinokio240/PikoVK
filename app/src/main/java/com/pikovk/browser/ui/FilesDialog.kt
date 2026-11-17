package com.pikovk.browser.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
import java.text.SimpleDateFormat
import java.util.Date
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
// 1.3.39 (доработка-28): переработка дизайна строк и сортировка по
// просьбе владельца. (1) Строка списка — ДВЕ строки текста: имя файла
// жирным и серая строка «размер · дата изменения» (у папок — «Папка»);
// перед именем — цветная метка типа «●» (SpannableString +
// ForegroundColorSpan): изображения — зелёный, видео — фиолетовый,
// аудио — голубой, pdf — красный, архивы — оранжевый, документы —
// синий, apk — жёлтый, остальное — серый; выбранный файл — акцентное
// имя с префиксом «✓ » и alpha ряда 1f. Пустая папка — строка
// «Папка пуста», не ошибка. (2) Сортировка: кнопка «Сортировка: …»
// в ряду кнопок, тап листает режимы «имя → тип → дата → имя»; папки
// ВСЕГДА первыми (среди папок — по имени); режим хранится в private var
// объекта-синглтона (роль companion-статики — живёт весь сеанс
// приложения); смена режима сразу пересортирует уже загруженный listing
// на UI-потоке, без повторного чтения файловой системы. Контакт
// show(activity, multiple, onResult), «Прикрепить»/отмена, флаг finished,
// доступ к хранилищу, лимит 500, старт в Download, «Вверх», FileProvider —
// без изменений.
object FilesDialog {

    private val io = Executors.newSingleThreadExecutor()

    // 1.3.39 (доработка-28): режим сортировки списка. Хранится в private
    // var объекта-синглтона (роль companion-статики): значение переживает
    // закрытие диалога и живёт весь сеанс приложения. По умолчанию —
    // «по имени», как вел себя прежний список.
    private enum class SortMode(val title: String) {
        NAME("имя"), TYPE("тип"), DATE("дата")
    }

    private var sortMode = SortMode.NAME

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

    // 1.3.39 (доработка-28): расширение файла в нижнем регистре (без точки);
    // у имени без точки — пустая строка. Нужно и для сортировки «по типу»,
    // и для цвета метки «●».
    private fun extOf(f: File): String {
        val n = f.name
        val dot = n.lastIndexOf('.')
        return if (dot < 0) "" else n.substring(dot + 1).lowercase(Locale.getDefault())
    }

    // 1.3.39 (доработка-28): сортировка по текущему режиму sortMode.
    // Раньше сортировки не было вовсе — был жёсткий порядок «папки первыми,
    // внутри по имени». Теперь: ПАПКИ ВСЕГДА первыми (среди папок — по имени
    // без учёта регистра), файлы — по режиму: NAME — A→Я без учёта регистра;
    // TYPE — по расширению по алфавиту, внутри группы по имени; DATE — новые
    // сверху (при равном времени — по имени, чтобы порядок был стабильным).
    // Вызывается и при загрузке папки (в io-исполнителе, вместе с чтением
    // ФС), и при смене режима на UI-потоке поверх уже загруженного listing —
    // без повторного чтения файловой системы; элементов ≤ LIMIT, дёшево.
    private fun sortChildren(children: ArrayList<File>) {
        children.sortWith { a, b ->
            if (a.isDirectory != b.isDirectory) {
                // папка всегда раньше файла
                if (a.isDirectory) -1 else 1
            } else if (a.isDirectory) {
                // среди папок — всегда по имени, независимо от режима
                a.name.compareTo(b.name, ignoreCase = true)
            } else {
                when (sortMode) {
                    SortMode.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                    SortMode.TYPE -> {
                        val ea = extOf(a)
                        val eb = extOf(b)
                        if (ea != eb) ea.compareTo(eb)
                        else a.name.compareTo(b.name, ignoreCase = true)
                    }
                    SortMode.DATE -> {
                        val d = b.lastModified().compareTo(a.lastModified())
                        if (d != 0) d else a.name.compareTo(b.name, ignoreCase = true)
                    }
                }
            }
        }
    }

    // 1.3.39 (доработка-28): цвет метки «●» перед именем — по расширению
    // файла (типу). Папка — голубая метка, как «ссылка».
    private fun dotColorFor(f: File): Int {
        if (f.isDirectory) return 0xFF8AB4F8.toInt() // папка — голубой
        return when (extOf(f)) {
            "jpg", "png", "gif", "webp", "bmp", "heic" ->
                0xFF81C995 // изображения — зелёный
            "mp4", "mkv", "avi", "mov", "webm", "3gp" ->
                0xFFC58AF9 // видео — фиолетовый
            "mp3", "wav", "ogg", "m4a", "flac" ->
                0xFF8AB4F8 // аудио — голубой
            "pdf" ->
                0xFFF28B82 // pdf — красный
            "zip", "rar", "7z", "tar", "gz" ->
                0xFFFDD663 // архивы — оранжевый
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "txt" ->
                0xFF8AB4F8 // документы — синий
            "apk" ->
                0xFFFFD54F // apk — жёлтый
            else ->
                0xFF9AA0A6 // остальное — серый
        }.toInt()
    }

    // Дети папки: null — listFiles()==null (нет доступа). Скрытые файлы
    // пропускаем, папки идут первыми, имена сравниваются без учёта
    // регистра; больше LIMIT — первые LIMIT с пометкой.
    // 1.3.39 (доработка-28): порядок записей теперь задаёт session-режим
    // sortMode через sortChildren — папки по-прежнему всегда первыми,
    // имя без учёта регистра осталось запасным ключом во всех режимах.
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
        // 1.3.39 (доработка-28): единый компаратор — тот же, что и при
        // пересортировке по тапу; режим берётся из session-переменной.
        sortChildren(out)
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

        // 1.3.39 (доработка-28): формат второй строки файла — дата изменения
        // «дд.ММ.гггг ЧЧ:мм». SimpleDateFormat создаётся один на диалог и
        // используется только с UI-потока (getView), поэтому без синхронизации;
        // локаль — по умолчанию, как требовал владелец.
        val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

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
        // 1.3.39 (доработка-28): кнопка сортировки в ряду кнопок, между
        // «Вверх» и доступом; равные доли веса у всех кнопок ряда. Текущий
        // режим виден в тексте («Сортировка: имя/тип/дата»); обработчик тапа
        // вешается ниже, после создания adapter'а (он нужен для перерисовки).
        val sortBtn = Button(activity).apply {
            text = "Сортировка: ${sortMode.title}"
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
            addView(sortBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

        // 1.3.39 (доработка-28): +1 служебная строка «Папка пуста», когда
        // доступ есть, а детей ноль — пустая папка не ошибка. С «Нет доступа…»
        // не сочетается: noAccess приоритетнее (guard !noAccess), прежний
        // список нечитаемой папки при noAccess по-прежнему виден ниже строки.
        fun extraRow() = if (!noAccess && listing.children.isEmpty()) 1 else 0

        // 1.3.39 (доработка-28): создание двухстрочной строки списка —
        // вертикальный LinearLayout с двумя TextView. Первая строка — имя
        // (жирным, светло-серый 15sp), вторая — «размер · дата изменения»
        // (серый 12sp). Отступы чуть плотнее прежних 14dp: строк стало две.
        fun makeRow(ctx: Context): LinearLayout {
            val name = TextView(ctx).apply {
                textSize = 15f
                setTextColor(0xFFE0E0E0.toInt())
                setTypeface(typeface, Typeface.BOLD)
            }
            val sub = TextView(ctx).apply {
                textSize = 12f
                setTextColor(0xFF9AA0A6.toInt())
            }
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(name)
                addView(sub)
            }
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = listing.children.size + rowOffset() + extraRow()
            override fun getItem(position: Int): File? = null
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                // 1.3.39 (доработка-28): convertView — всегда тот же
                // двухстрочный LinearLayout (ровно один тип строк): служебные
                // строки «Нет доступа…» и «Папка пуста» рисуются в нём же,
                // вторая строка прячется, — ListView переиспользует view
                // без getViewTypeCount-логики.
                val row = (convertView as? LinearLayout) ?: makeRow(parent.context)
                val name = row.getChildAt(0) as TextView
                val sub = row.getChildAt(1) as TextView
                if (noAccess && position == 0) {
                    // единственная строка при отказе listFiles(): тап — повторить
                    name.text = "Нет доступа к папке — тапните, чтобы повторить"
                    name.setTextColor(0xFFE0E0E0.toInt())
                    sub.visibility = View.GONE
                    row.alpha = 0.75f
                    return row
                }
                if (extraRow() == 1 && position == 0) {
                    // 1.3.39 (доработка-28): пустая папка — серая строка,
                    // не ошибка; тапом не является.
                    name.text = "Папка пуста"
                    name.setTextColor(0xFF9AA0A6.toInt())
                    sub.visibility = View.GONE
                    row.alpha = 1f
                    return row
                }
                sub.visibility = View.VISIBLE
                val f = listing.children[position - rowOffset()]
                val selected = f in sel
                // 1.3.39 (доработка-28): перед именем — маленькая цветная
                // метка типа «●» (SpannableString + ForegroundColorSpan,
                // размер точки уменьшен RelativeSizeSpan); у выбранного
                // файла перед меткой ещё префикс «✓ ».
                val builder = StringBuilder()
                if (selected) builder.append("✓ ")
                val dotStart = builder.length
                builder.append("●").append("  ")
                // старое оформление папок сохранено: имя со слэшем
                builder.append(if (f.isDirectory) f.name + "/" else f.name)
                val label = SpannableString(builder)
                label.setSpan(
                    ForegroundColorSpan(dotColorFor(f)),
                    dotStart,
                    dotStart + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                label.setSpan(
                    RelativeSizeSpan(0.8f), // «маленький» символ метки
                    dotStart,
                    dotStart + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                name.text = label
                // 1.3.39 (доработка-28): имя выбранного файла — акцентное;
                // папки остались «ссылками» (синими, как раньше); обычные
                // файлы — светло-серые, как и было.
                name.setTextColor(
                    when {
                        selected || f.isDirectory -> 0xFF8AB4F8.toInt()
                        else -> 0xFFE0E0E0.toInt()
                    }
                )
                // 1.3.39 (доработка-28): вторая строка. Файл — «размер ·
                // дата изменения» (humanSize как был + SimpleDateFormat).
                // Папка — «Папка»: подсчёт «N элементов» потребовал бы
                // listFiles() каждой подпапки прямо на UI-потоке, берём
                // дешёвый вариант из двух разрешённых владельцем.
                sub.text = if (f.isDirectory) {
                    "Папка"
                } else {
                    humanSize(f.length()) + " · " + dateFmt.format(Date(f.lastModified()))
                }
                // alpha ряда: выбранное — 1f, остальное — 0.6f, как раньше
                row.alpha = if (selected) 1f else 0.6f
                return row
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

        // 1.3.39 (доработка-28): тап по кнопке сортировки — цикл
        // «имя → тип → дата → имя»; новый режим сразу пишется в
        // session-переменную sortMode и показывается в тексте кнопки.
        // Пересортировка уже загруженного listing идёт на UI-потоке,
        // БЕЗ повторного чтения файловой системы (клеток ≤ LIMIT — дёшево),
        // затем обычная перерисовка списка.
        sortBtn.setOnClickListener {
            sortMode = when (sortMode) {
                SortMode.NAME -> SortMode.TYPE
                SortMode.TYPE -> SortMode.DATE
                SortMode.DATE -> SortMode.NAME
            }
            sortBtn.text = "Сортировка: ${sortMode.title}"
            sortChildren(listing.children)
            adapter.notifyDataSetChanged()
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
            // 1.3.39 (доработка-28): служебная строка «Папка пуста» —
            // тапом не является ни файлом, ни повтором чтения.
            if (listing.children.isEmpty()) return@setOnItemClickListener
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
