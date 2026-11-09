package com.pikovk.browser.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

// Диспетчер загрузок треков вкладки «Музыка» (1.3.23, доработка).
// Единая точка входа enqueue() и для mp3, и для HLS: задача попадает в
// общий список, качается в фоне, прогресс и статус видны во вкладке
// «Загрузки» (снимок snapshot(), отмена cancel(id)).
//
// Почему mp3 качаем сами, а не системным DownloadManager: нужен живой
// прогресс и отмена внутри приложения; cookie сессии VK и UA WebView
// подставляем сами (сессия выдана WebView — см. MusicApi.agent()).
// Системный DownloadManager остался на страницах VK (VkbBridge).
//
// Потокобезопасность: список задач и их поля меняются только под
// synchronized(this) — снимок для страницы всегда согласованный.
// Отмена — volatile-флаг, поток загрузки сам остановится на очередном
// куске и удалит недокачанный файл. Fail-open: любая ошибка сети/записи
// переводит задачу в «ошибка», диспетчер живёт дальше.
object TrackDownloader {

    const val ST_WAIT = 0 // в очереди
    const val ST_RUN = 1 // качается
    const val ST_DONE = 2 // готово
    const val ST_ERR = 3 // ошибка
    const val ST_CANCEL = 4 // отменено пользователем

    class Task(val id: Int, val url: String, val hls: Boolean, val name: String) {
        var status: Int = ST_WAIT
        var progress: Int = 0 // проценты 0..100
        var file: String = "" // итоговое имя файла
        @Volatile var cancelled: Boolean = false
    }

    private val tasks = ArrayList<Task>()
    private var nextId: Int = 1
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun enqueue(ctx: Context, url: String, hls: Boolean, name: String) {
        // активити может закрыться в любой момент — живём на applicationContext
        val app = ctx.applicationContext
        val t = Task(nextId, url, hls, name)
        nextId++
        tasks.add(0, t) // свежие сверху
        // список не растёт бесконечно: подрезаем ЗАВЕРШЁННЫЕ с хвоста,
        // живые (ждущие/качающиеся) задачи не выбрасываем
        while (tasks.size > 50) {
            var removed = false
            var idx = tasks.size - 1
            while (idx >= 0) {
                val st = tasks[idx].status
                if (st == ST_DONE || st == ST_ERR || st == ST_CANCEL) {
                    tasks.removeAt(idx)
                    removed = true
                    break
                }
                idx--
            }
            if (!removed) break
        }
        Thread { runTask(app, t) }.start()
    }

    // Снимок для вкладки «Загрузки» (JSON-массив, порядок — как на экране)
    @Synchronized
    fun snapshot(): String {
        val arr = JSONArray()
        for (i in tasks.indices) {
            val t = tasks[i]
            val o = JSONObject()
            try {
                o.put("id", t.id)
                o.put("name", t.name)
                o.put("status", t.status)
                o.put("progress", t.progress)
                o.put("file", t.file)
                o.put("hls", if (t.hls) 1 else 0)
            } catch (_: Exception) {
                continue // битую строку пропускаем, остальные показываем
            }
            arr.put(o)
        }
        return arr.toString()
    }

    @Synchronized
    fun cancel(id: Int) {
        for (i in tasks.indices) {
            val t = tasks[i]
            if (t.id == id) {
                t.cancelled = true
                if (t.status == ST_WAIT) {
                    // ещё не стартовала — пометим сразу; запущенная сама
                    // дойдёт до проверки флага и остановится
                    t.status = ST_CANCEL
                }
            }
        }
    }

    // Обновление состояния задачи. Вызывается из потока загрузки (любой).
    @Synchronized
    private fun update(t: Task, status: Int, progress: Int, file: String) {
        if (t.cancelled && status == ST_RUN) {
            return // отмененную не «оживляем»
        }
        t.status = status
        t.progress = progress
        if (file.isNotEmpty()) t.file = file
    }

    private fun runTask(app: Context, t: Task) {
        // отмену могли успеть нажать ещё до старта потока
        if (t.cancelled) {
            update(t, ST_CANCEL, 0, "")
            return
        }
        update(t, ST_RUN, 0, "")
        var file = ""
        try {
            if (t.hls) {
                val saved: String? = MusicApi.downloadHls(app, t.url, t.name, { p ->
                    update(t, ST_RUN, p, "")
                }, { t.cancelled })
                if (saved != null) file = saved
            } else {
                file = downloadMp3(app, t, t.url)
            }
        } catch (_: Exception) {
            file = ""
        }
        if (t.cancelled) {
            update(t, ST_CANCEL, 0, "")
            return
        }
        if (file.isEmpty()) {
            update(t, ST_ERR, 0, "")
            toast(app, "Не удалось скачать: " + t.name)
        } else {
            update(t, ST_DONE, 100, file)
            toast(app, "Готово: " + file)
        }
    }

    // mp3: прямая ссылка vkuseraudio — качаем с cookie сессии и UA WebView.
    // Возвращает имя файла или "" (ошибка/отмена); недокачанное удаляем.
    private fun downloadMp3(app: Context, t: Task, url: String): String {
        val opened = MusicApi.openOutput(app, t.name + ".mp3", "audio/mpeg")
        if (opened == null) return ""
        val out: OutputStream = opened.first
        val cookie: String? = CookieManager.getInstance().getCookie(url)
        var ok = false
        try {
            val oc = URL(url).openConnection()
            if (oc !is HttpURLConnection) return ""
            oc.connectTimeout = 15000
            oc.readTimeout = 20000
            oc.instanceFollowRedirects = true
            if (cookie != null && cookie.isNotEmpty()) {
                oc.setRequestProperty("Cookie", cookie)
            }
            oc.setRequestProperty("User-Agent", MusicApi.agent())
            val code = oc.responseCode
            if (code !in 200..299) {
                oc.disconnect()
                return ""
            }
            val total = oc.contentLengthLong
            val buf = ByteArray(65536)
            var done: Long = 0
            oc.inputStream.use { ins ->
                while (true) {
                    if (t.cancelled) return ""
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        var p = ((done * 100) / total).toInt()
                        if (p > 100) p = 100
                        update(t, ST_RUN, p, "")
                    }
                }
            }
            oc.disconnect()
            ok = true
            // снимаем IS_PENDING — без этого файл НЕ ВИДЕН в «Загрузках»
            MusicApi.finishOutput(app, opened.third)
            return opened.second
        } catch (_: Exception) {
            return ""
        } finally {
            try {
                out.close()
            } catch (_: Exception) {
            }
            if (!ok) {
                // недокачанный/отменённый файл не оставляем в «Загрузках»
                MusicApi.dropPartial(app, opened.third)
            }
        }
    }

    private fun toast(ctx: Context, msg: String) {
        main.post {
            runCatching {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
