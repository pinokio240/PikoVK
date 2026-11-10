package com.pikovk.browser.music

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.pikovk.browser.media.PlaybackService
import org.json.JSONArray
import org.json.JSONObject

// JS-мост экрана «Музыка» (1.3.23): window.VkbMusic из assets/music.html.
//
// Вызовы из страницы (поток JS-моста, сеть можно прямо здесь):
//   load(offset)            — «Моя музыка» (audio.get)
//   search(query, offset)   — поиск (audio.search)
//   playlists(offset)       — плейлисты (audio.getPlaylists)
//   pltracks(payload)       — треки плейлиста {owner, id, key, offset}
//   probe(rawId, url, hls, duration) — проба битрейта (кэш в MusicApi)
//   download(payload)       — скачивание {url, hls, name, rawId} — через
//                             диспетчер TrackDownloader (прогресс/отмена
//                             видны во вкладке «Загрузки»)
//   downloadCurrent()       — скачать тот трек, что играет сейчас
//                             (кнопка в самом плеере)
//   getDownloads()          — снимок диспетчера загрузок (строкой —
//                             JS-мост умеет возвращать значения)
//   cancelDownload(id)      — отменить задачу загрузки
//   nativeQueue(payload)    — ОЧЕРЕДЬ от вкладки в службу {tracks, index,
//                             shuffle, repeat}: дальше служба играет и
//                             переходит сама (1.3.23, доработка)
//   nativeShift(dir)        — вперёд/назад по очереди службы (dir=1/-1;
//                             назад с откатом в начало трека)
//   nativeMode(shuffle, repeat) — режимы воспроизведения в службе
//   exitTab()               — кнопка «‹ ВК»: вернуться к вкладке браузера
//   nativePlay(payload)     — одиночный трек в службу (оставлен для
//                             совместимости, вкладка использует nativeQueue)
//   nativePause/nativeResume/nativeSeek(sec) — управление движком службы
//   syncPlayer()            — свежая вкладка забирает состояние службы
//
// Ответы уходят в страницу одним способом: evaluateJavascript
// window.__vkbMusicData(<json>) — как в VK Music Player данные плеера
// приходят в UI одним каналом. Позицию трека служба пушит сама (тикер в
// PlaybackService → window.__vkbMusicPos).
//
// fail-open: ошибки сети/JSON не роняют страницу — в UI уходит
// {kind, ok:false, error}. Явные null-проверки, без ?: и !!.
class MusicBridge(private val context: Context, private val webHolder: () -> WebView?) {

    private val main = Handler(Looper.getMainLooper())

    private fun respond(web: WebView?, json: String) {
        // страница могла быть пересобрана/закрыта — тогда ответ просто не нужен
        val w = web
        if (w == null) return
        w.post {
            runCatching {
                w.evaluateJavascript("window.__vkbMusicData&&window.__vkbMusicData(" + json + ");", null)
            }
        }
    }

    private fun sendList(kind: String, reqId: Int, ok: Boolean, items: String, append: Boolean, rawPage: Int, total: Int, error: String) {
        val obj = JSONObject()
        obj.put("kind", kind)
        obj.put("reqId", reqId)
        obj.put("ok", ok)
        obj.put("append", append)
        // 1.3.23 доработка-2: размер СЫРОЙ страницы (до нормализации) —
        // паттерн AudioLibraryPager из PinoK: offset сдвигаем на сырой размер,
        // конец списка = пустая сырая страница. Нормализация может отбросить
        // треки (id==0) — если сдвигать по числу показанных, offset дрейфует
        // и VK отдаёт те же треки по кругу (дрейфующая пагинация)
        obj.put("raw", rawPage)
        // 1.3.23 доработка-4: общее число записей по мнению VK (response.count)
        // для счётчика «Прогружено N из M» на вкладке. 0 — VK число не сказал
        obj.put("total", total)
        if (ok) {
            obj.put("items", JSONArray(items))
        } else {
            obj.put("error", error)
        }
        respond(webHolder(), obj.toString())
    }

    // Размер сырой страницы ответа (0 — ответа/списка нет)
    private fun rawCount(resp: JSONObject?): Int {
        if (resp == null) return 0
        val items = resp.optJSONArray("items")
        if (items == null) return 0
        return items.length()
    }

    // Общее число записей по мнению VK (response.count). 0 — не сказал
    private fun totalCount(resp: JSONObject?): Int {
        if (resp == null) return 0
        return resp.optInt("count", 0)
    }

    private fun normalizeResp(resp: JSONObject?): String {
        if (resp == null) return "[]"
        val items = resp.optJSONArray("items")
        if (items != null) return MusicApi.normalizeItems(items).toString()
        return "[]"
    }

    @JavascriptInterface
    fun load(offset: Int, reqId: Int, append: Int) {
        Thread {
            val resp = MusicApi.myMusic(offset)
            val ok = resp != null
            sendList("tracks", reqId, ok, normalizeResp(resp), append != 0, rawCount(resp), totalCount(resp),
                if (ok) "" else "Не удалось загрузить (нет сессии VK или сеть недоступна)")
        }.start()
    }

    @JavascriptInterface
    fun search(query: String, offset: Int, reqId: Int, append: Int) {
        Thread {
            val resp = MusicApi.search(query, offset)
            val ok = resp != null
            sendList("tracks", reqId, ok, normalizeResp(resp), append != 0, rawCount(resp), totalCount(resp),
                if (ok) "" else "Поиск не удался")
        }.start()
    }

    @JavascriptInterface
    fun playlists(offset: Int, reqId: Int, append: Int) {
        Thread {
            val resp = MusicApi.playlists(offset)
            var list = "[]"
            if (resp != null) {
                val arr = resp.optJSONArray("items")
                if (arr != null) list = arr.toString()
            }
            sendList("playlists", reqId, resp != null, list, append != 0, rawCount(resp), totalCount(resp),
                if (resp != null) "" else "Не удалось загрузить плейлисты")
        }.start()
    }

    @JavascriptInterface
    fun pltracks(payload: String, reqId: Int, append: Int) {
        Thread {
            var ok = false
            var list = "[]"
            var raw = 0
            var total = 0
            var err = "Неверные данные плейлиста"
            try {
                val p = JSONObject(payload)
                val owner = p.optLong("owner", 0)
                val id = p.optLong("id", 0)
                val key: String? = p.optString("key", "")
                // 1.3.23 доработка-2: offset плейлиста — дозагрузка по скроллу
                // (как в полной версии VK), раньше всегда качалась одна страница
                val offset = p.optInt("offset", 0)
                if (owner != 0L && id != 0L) {
                    val safeKey: String = if (key == null) "" else key
                    val resp = MusicApi.playlistTracks(owner, id, safeKey, offset)
                    if (resp != null) {
                        ok = true
                        err = ""
                        raw = rawCount(resp)
                        total = totalCount(resp)
                        list = normalizeResp(resp)
                    } else {
                        err = "Не удалось загрузить треки плейлиста"
                    }
                }
            } catch (_: Exception) {
            }
            sendList("tracks", reqId, ok, list, append != 0, raw, total, err)
        }.start()
    }

    // Проба битрейта: ответ адресный, по reqId; кэш внутри MusicApi.
    @JavascriptInterface
    fun probe(rawId: String, url: String, hls: Int, duration: Long, reqId: Int) {
        Thread {
            val kbps = MusicApi.probeBitrate(rawId, url, hls != 0, duration)
            if (kbps == null) return@Thread
            val obj = JSONObject()
            obj.put("kind", "bitrate")
            obj.put("reqId", reqId)
            obj.put("rawId", rawId)
            obj.put("kbps", kbps)
            respond(webHolder(), obj.toString())
        }.start()
    }

    // Скачивание: единая точка — диспетчер TrackDownloader (mp3 и HLS,
    // прогресс и отмена видны во вкладке «Загрузки»). Системный
    // DownloadManager остался на страницах VK (VkbBridge.enqueueNamed).
    @JavascriptInterface
    fun download(payload: String) {
        Thread {
            try {
                val p = JSONObject(payload)
                val url: String? = p.optString("url", "")
                val name: String? = p.optString("name", "")
                val hls = p.optInt("hls", 0)
                if (url == null || url.isEmpty()) {
                    toastMain("Ссылки нет — обновите список")
                    return@Thread
                }
                val dlName: String = if (name == null || name.isEmpty()) "vk_track" else name
                TrackDownloader.enqueue(context, url, hls != 0, dlName)
                toastMain("Скачиваю: " + dlName + "…")
            } catch (_: Exception) {
                toastMain("Не удалось начать скачивание")
            }
        }.start()
    }

    // 1.3.23: скачать тот трек, что играет сейчас (кнопка в самом плеере).
    // Данные берём из службы — она хранит ссылку/имя текущего трека, поэтому
    // работает и после пересоздания вкладки, и для очереди из другого списка.
    @JavascriptInterface
    fun downloadCurrent() {
        Thread {
            try {
                val info = JSONObject(PlaybackService.currentDownload())
                val url: String? = info.optString("url", "")
                if (url == null || url.isEmpty()) {
                    toastMain("Ссылки нет — запустите трек заново")
                    return@Thread
                }
                val name: String? = info.optString("name", "")
                val dlName: String = if (name == null || name.isEmpty()) "vk_track" else name
                val hls = info.optInt("hls", 0)
                TrackDownloader.enqueue(context, url, hls != 0, dlName)
                toastMain("Скачиваю: " + dlName + "…")
            } catch (_: Exception) {
                toastMain("Не удалось начать скачивание")
            }
        }.start()
    }

    // 1.3.23: снимок диспетчера загрузок для вкладки «Загрузки». JS-мост
    // умеет возвращать строку — отдельные reqId и каналы ответа не нужны.
    @JavascriptInterface
    fun getDownloads(): String {
        return try {
            TrackDownloader.snapshot()
        } catch (_: Exception) {
            "[]"
        }
    }

    // 1.3.23: отменить задачу загрузки (кнопка ✕ во вкладке «Загрузки»)
    @JavascriptInterface
    fun cancelDownload(id: Int) {
        try {
            TrackDownloader.cancel(id)
        } catch (_: Exception) {
        }
    }

    // 1.3.23: кнопка «‹ ВК» в шапке вкладки — вернуться к вкладке браузера
    @JavascriptInterface
    fun exitTab() {
        main.post {
            try {
                val act = context
                if (act is Activity) act.finish()
            } catch (_: Exception) {
            }
        }
    }

    private fun toastMain(msg: String) {
        main.post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 1.3.23 (доработка): воспроизведение и ОЧЕРЕДЬ живут в службе.
    // Вкладка — пульт: отдала список и номер — служба сама играет, сама
    // переходит к следующему по завершению/ошибке, сама обрабатывает
    // шторку/гарнитуру. Список отдаётся через nativeQueue, кнопки
    // вперёд/назад — nativeShift. Старт службы ПОКА экран виден — тот же
    // принцип упреждения 1.3.22 (Android «Background audio hardening»).
    @JavascriptInterface
    fun nativePlay(payload: String) {
        try {
            PlaybackService.nPlay(context, payload)
        } catch (_: Exception) {
        }
    }

    // 1.3.23 (доработка): очередь + номер играющего — службе
    @JavascriptInterface
    fun nativeQueue(payload: String) {
        try {
            PlaybackService.nQueue(context, payload)
        } catch (_: Exception) {
        }
    }

    // 1.3.23 (доработка): вперёд/назад по очереди службы (dir = 1 или -1)
    @JavascriptInterface
    fun nativeShift(dir: Int) {
        try {
            if (dir < 0) {
                PlaybackService.nPrev()
            } else {
                PlaybackService.nShift(dir)
            }
        } catch (_: Exception) {
        }
    }

    // 1.3.23 (доработка): режимы воспроизведения — службе
    @JavascriptInterface
    fun nativeMode(shuffle: Int, repeat: String) {
        try {
            PlaybackService.nMode(shuffle, repeat)
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun nativePause() {
        try {
            PlaybackService.nPause()
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun nativeResume() {
        try {
            PlaybackService.nResume()
        } catch (_: Exception) {
        }
    }

    @JavascriptInterface
    fun nativeSeek(sec: Double) {
        try {
            PlaybackService.nSeek(sec)
        } catch (_: Exception) {
        }
    }

    // Свежая вкладка (переоткрытие после закрытия/смерти рендерера)
    // забирает текущее состояние службы — карточка в UI оживает.
    @JavascriptInterface
    fun syncPlayer() {
        try {
            respond(webHolder(), PlaybackService.nativeState())
        } catch (_: Exception) {
        }
    }
}
