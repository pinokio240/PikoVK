package com.pikovk.browser.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.pikovk.browser.bridge.VkbBridge
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
//   download(payload)       — скачивание {url, hls, name, rawId}
//   nativePlay(payload)     — ИГРАТЬ трек в службе {url,title,artist,
//                             duration,rawId,seek} (1.3.23)
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

    private fun send(kind: String, reqId: Int, ok: Boolean, data: String, error: String) {
        val obj = JSONObject()
        obj.put("kind", kind)
        obj.put("reqId", reqId)
        obj.put("ok", ok)
        if (ok) {
            obj.put("data", JSONObject(data))
        } else {
            obj.put("error", error)
        }
        respond(webHolder(), obj.toString())
    }

    private fun sendList(kind: String, reqId: Int, ok: Boolean, items: String, append: Boolean, error: String) {
        val obj = JSONObject()
        obj.put("kind", kind)
        obj.put("reqId", reqId)
        obj.put("ok", ok)
        obj.put("append", append)
        if (ok) {
            obj.put("items", JSONArray(items))
        } else {
            obj.put("error", error)
        }
        respond(webHolder(), obj.toString())
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
            sendList("tracks", reqId, ok, normalizeResp(resp), append != 0,
                if (ok) "" else "Не удалось загрузить (нет сессии VK или сеть недоступна)")
        }.start()
    }

    @JavascriptInterface
    fun search(query: String, offset: Int, reqId: Int, append: Int) {
        Thread {
            val resp = MusicApi.search(query, offset)
            val ok = resp != null
            sendList("tracks", reqId, ok, normalizeResp(resp), append != 0,
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
            sendList("playlists", reqId, resp != null, list, append != 0,
                if (resp != null) "" else "Не удалось загрузить плейлисты")
        }.start()
    }

    @JavascriptInterface
    fun pltracks(payload: String, reqId: Int, append: Int) {
        Thread {
            var ok = false
            var list = "[]"
            var err = "Неверные данные плейлиста"
            try {
                val p = JSONObject(payload)
                val owner = p.optLong("owner", 0)
                val id = p.optLong("id", 0)
                val key: String? = p.optString("key", "")
                if (owner != 0L && id != 0L) {
                    val safeKey: String = if (key == null) "" else key
                    val resp = MusicApi.playlistTracks(owner, id, safeKey, 0)
                    if (resp != null) {
                        ok = true
                        err = ""
                        list = normalizeResp(resp)
                    } else {
                        err = "Не удалось загрузить треки плейлиста"
                    }
                }
            } catch (_: Exception) {
            }
            sendList("tracks", reqId, ok, list, append != 0, err)
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

    // Скачивание: mp3 — в системный DownloadManager (общий путь VkbBridge),
    // HLS — сегменты в один .ts (см. MusicApi.downloadHls).
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
                if (hls != 0) {
                    val dlName: String = if (name == null || name.isEmpty()) "трек" else name
                    toastMain("Скачиваю: " + dlName + "…")
                    val saved = MusicApi.downloadHls(context, url, if (name == null || name.isEmpty()) "vk_track" else name) { }
                    if (saved != null) {
                        toastMain("Готово: " + saved + " (в «Загрузках»)")
                    } else {
                        toastMain("Не удалось скачать HLS-поток")
                    }
                } else {
                    val fileName = MusicApi.sanitize(if (name == null || name.isEmpty()) "vk_track" else name) + ".mp3"
                    VkbBridge.enqueueNamed(context, url, fileName)
                }
            } catch (_: Exception) {
                toastMain("Не удалось начать скачивание")
            }
        }.start()
    }

    private fun toastMain(msg: String) {
        main.post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // 1.3.23: воспроизведение уехало в службу (нативный MediaPlayer).
    // Вкладка — пульт: отдала трек и управляет; звук не зависит от жизни
    // WebView. Старт службы ПОКА экран виден — тот же принцип упреждения
    // 1.3.22 (Android «Background audio hardening»).
    @JavascriptInterface
    fun nativePlay(payload: String) {
        try {
            PlaybackService.nPlay(context, payload)
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
