package com.pikovk.browser.music

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import com.pikovk.browser.util.Prefs
import com.pikovk.browser.util.SimpleHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URLEncoder

// Клиент музыки VK для экрана «Музыка» (1.3.23). Механизм 1:1 перенесён из
// проверенного расширения VK Music Player (VK Next, CWS hjilpkmlcpnlaimhaedgnljddlnneipg):
// код расширения распакован и изучен, ключевые фрагменты сверены вручную.
//
// КАК ЭТО РАБОТАЕТ У VK NEXT:
//  1) web_token: POST https://login.vk.ru/?act=web_token с телом
//     version=1&app_id=6287487 и cookie веб-сессии (credentials:include).
//     Ответ {"type":"okay","data":{access_token,user_id,expires}} — токен
//     веб-клиента VK, scope включает audio.*. Кэш в storage, обновление
//     при expires - now < 100 с.
//  2) API: POST https://api.vk.ru/method/<Метод>?client_id=6287487, тело
//     access_token=...&v=5.251&lang=ru&<параметры>, заголовок Origin vk.ru.
//     Ответ {"response":...} или {"error":{error_code,...}}.
//  3) Ссылка трека: audio_streams[type=hls_ts].url (m3u8, большинство
//     новых треков) либо url (прямое mp3). Маскированные ссылки
//     (...audio_api_unavailable?extra=) разворачивает audioUnmaskSource —
//     портирован дословно (алфавит сверен с бандлом).
//  4) Битрейт: mp3 — Content-Length*8/длительность; HLS — один сегмент:
//     байты*8*(totalduration/сегм_длительность)/1000.
//
// Всё fail-open: любая ошибка сети/ответа — null или false, экран музыки
// показывает «не удалось» и позволяет повторить. Явные null-проверки,
// без ?: и !!, только строчные комментарии.
object MusicApi {

    private const val APP_ID = "6287487" // app id веб-клиента VK, как в расширении
    private const val API_V = "5.251"
    private const val TOKEN_URL = "https://login.vk.ru/?act=web_token"
    private const val API_BASE = "https://api.vk.ru/method"

    // Кэш битрейта: rawId -> kbps (в памяти процесса, как audioBitrate_<rawId>
    // в расширении).
    private val bitrateCache = HashMap<String, Int>()

    // ------------------------------------------------------------------
    // Токен
    // ------------------------------------------------------------------

    fun token(): JSONObject? {
        val now = System.currentTimeMillis() / 1000
        val saved = Prefs.musicToken
        if (saved.length > 10) {
            // годен ещё минимум 100 с — как getToken() в расширении
            if (Prefs.musicTokenExp - now >= 100) {
                val cached = JSONObject()
                cached.put("access_token", saved)
                cached.put("user_id", Prefs.musicUserId)
                return cached
            }
        }
        val cookieJar = CookieManager.getInstance()
        val cookie: String? = cookieJar.getCookie("https://login.vk.ru")
        if (cookie == null) {
            // нет веб-сессии — токен не получить, экран покажет «войдите в VK»
            return null
        }
        val headers = HashMap<String, String>()
        headers["Cookie"] = cookie
        headers["Origin"] = "https://vk.ru"
        headers["User-Agent"] = agent()
        val body = "version=1&app_id=" + APP_ID
        val raw = SimpleHttp.post(TOKEN_URL, body, headers, 65536)
        if (raw == null) return null
        return try {
            val root = JSONObject(raw)
            val type: String? = root.optString("type", "")
            if (type != "okay") return null
            val data = root.optJSONObject("data")
            if (data == null) return null
            val tkn: String? = data.optString("access_token", "")
            if (tkn == null || tkn.length < 10) return null
            Prefs.musicToken = tkn
            Prefs.musicUserId = data.optLong("user_id", 0)
            Prefs.musicTokenExp = data.optLong("expires", now + 86000)
            data
        } catch (_: Exception) {
            null
        }
    }

    private fun agent(): String {
        val a: String? = System.getProperty("http.agent")
        if (a == null) return "PikoVK/1.0"
        return a
    }

    // ------------------------------------------------------------------
    // Вызов метода API
    // ------------------------------------------------------------------

    fun call(method: String, params: Map<String, String>): JSONObject? {
        val tkn = token()
        if (tkn == null) return null
        val access: String? = tkn.optString("access_token", "")
        if (access == null || access.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("access_token=").append(enc(access))
        sb.append("&v=").append(API_V)
        sb.append("&lang=ru")
        val it = params.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            sb.append("&").append(e.key).append("=").append(enc(e.value))
        }
        val headers = HashMap<String, String>()
        headers["Origin"] = "https://vk.ru"
        headers["User-Agent"] = agent()
        val cookieJar = CookieManager.getInstance()
        val cookie: String? = cookieJar.getCookie("https://api.vk.ru")
        if (cookie != null && cookie.isNotEmpty()) {
            headers["Cookie"] = cookie
        }
        val raw = SimpleHttp.post(
            API_BASE + "/" + method + "?client_id=" + APP_ID,
            sb.toString(), headers, 3 * 1024 * 1024
        )
        if (raw == null) return null
        return try {
            val root = JSONObject(raw)
            if (root.has("response")) {
                val resp = root.opt("response")
                // методы audio.* отдают объект {count,items:[...]} — нормализуем
                if (resp is JSONObject) return resp
                val wrap = JSONObject()
                if (resp is JSONArray) wrap.put("items", resp)
                return wrap
            }
            null // {"error":...} или пусто — вызывающий покажет отказ
        } catch (_: Exception) {
            null
        }
    }

    private fun enc(s: String): String {
        return try {
            URLEncoder.encode(s, "UTF-8")
        } catch (_: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------------
    // Списки: моя музыка / поиск / плейлисты / треки плейлиста
    // ------------------------------------------------------------------

    fun myMusic(offset: Int): JSONObject? {
        val uid = Prefs.musicUserId
        if (uid == 0L) return null
        val p = HashMap<String, String>()
        p["owner_id"] = uid.toString()
        p["count"] = "50"
        p["offset"] = offset.toString()
        return call("audio.get", p)
    }

    fun search(query: String, offset: Int): JSONObject? {
        val p = HashMap<String, String>()
        p["q"] = query
        p["count"] = "50"
        p["offset"] = offset.toString()
        return call("audio.search", p)
    }

    fun playlists(offset: Int): JSONObject? {
        val uid = Prefs.musicUserId
        if (uid == 0L) return null
        val p = HashMap<String, String>()
        p["owner_id"] = uid.toString()
        p["count"] = "25"
        p["offset"] = offset.toString()
        return call("audio.getPlaylists", p)
    }

    fun playlistTracks(ownerId: Long, playlistId: Long, accessKey: String, offset: Int): JSONObject? {
        val p = HashMap<String, String>()
        p["owner_id"] = ownerId.toString()
        p["album_id"] = playlistId.toString()
        if (accessKey.isNotEmpty()) p["access_key"] = accessKey
        p["count"] = "50"
        p["offset"] = offset.toString()
        return call("audio.get", p)
    }

    // ------------------------------------------------------------------
    // Нормализация трека для UI
    // ------------------------------------------------------------------

    // Возвращает объект для music.html:
    // {rawId, title, artist, duration, cover, url, hls, restricted}
    fun normalize(item: JSONObject): JSONObject? {
        val out = JSONObject()
        val id: Long = item.optLong("id", 0)
        val ownerId: Long = item.optLong("owner_id", 0)
        if (id == 0L || ownerId == 0L) return null
        val key: String? = item.optString("access_key", "")
        var rawId = ownerId.toString() + "_" + id.toString()
        if (key != null && key.isNotEmpty()) rawId = rawId + "_" + key
        out.put("rawId", rawId)
        var title: String? = item.optString("title", "")
        var artist: String? = item.optString("artist", "")
        if (title == null) title = ""
        if (artist == null) artist = ""
        out.put("title", title)
        out.put("artist", artist)
        out.put("duration", item.optLong("duration", 0))
        // обложка: album.thumb.photo_300 (лестница из расширения), запасные размеры
        var cover = ""
        val album = item.optJSONObject("album")
        if (album != null) {
            val thumb = album.optJSONObject("thumb")
            if (thumb != null) {
                cover = pickCover(thumb)
            }
        }
        out.put("cover", cover)
        // ограничения/удаление — такие строки в UI серые и не играют
        val dmca = item.optBoolean("dmca_blocked", false)
        val restricted: Int = item.optInt("content_restricted", 0)
        out.put("restricted", if (dmca || restricted != 0) 1 else 0)
        // ссылка: hls_ts-поток приоритетнее (так же в VK Music Player), иначе url
        var url = ""
        var hls = false
        val streams = item.optJSONArray("audio_streams")
        if (streams != null) {
            for (i in 0 until streams.length()) {
                val st = streams.optJSONObject(i)
                if (st == null) continue
                val type: String? = st.optString("type", "")
                if (type == "hls_ts") {
                    url = st.optString("url", "")
                    hls = true
                    break
                }
            }
        }
        if (url.isEmpty()) {
            url = item.optString("url", "")
            if (url == null) url = ""
            if (url.isNotEmpty() && url.contains(".m3u8")) hls = true
        }
        // маскированная ссылка — разворачиваем (нужен id зрителя)
        if (url.isNotEmpty() && url.contains("audio_api_unavailable")) {
            val fixed = unmask(url, Prefs.musicUserId)
            if (fixed.isNotEmpty()) url = fixed
        }
        out.put("url", url)
        out.put("hls", if (hls) 1 else 0)
        return out
    }

    // Список items -> список нормализованных треков (null-элементы пропускаем)
    fun normalizeItems(items: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
            if (it == null) continue
            val n = normalize(it)
            if (n != null) out.put(n)
        }
        return out
    }

    fun pickCover(thumb: JSONObject): String {
        val ladder = intArrayOf(300, 135, 68, 270, 600, 34, 1200)
        for (i in ladder.indices) {
            val src: String? = thumb.optString("photo_" + ladder[i], "")
            if (src != null && src.isNotEmpty()) return src
        }
        return ""
    }

    // ------------------------------------------------------------------
    // audioUnmaskSource — дословный порт из VK Music Player / vkopt
    // (алфавит сверен с бандлом расширения). Разворачивает ссылки
    // вида ...audio_api_unavailable?extra=<b64>#<b64>.
    // ------------------------------------------------------------------

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN0PQRSTUVWXYZO123456789+/="

    private fun vkB64Decode(s: String): String? {
        if (s.isEmpty()) return null
        if (s.length % 4 == 1) return null
        var a = 0
        var n = 0
        val sb = StringBuilder()
        for (c in 0 until s.length) {
            val idx = ALPHABET.indexOf(s[c])
            if (idx < 0) continue
            a = if (n % 4 > 0) 64 * a + idx else idx
            if (n % 4 > 0) {
                // в JS сдвиг берётся уже ПОСЛЕ инкремента счётчика (_++%4),
                // поэтому -(2*(n+1)) & 6 — даёт стандартные сдвиги 4/2/0
                val shift = -2 * (n + 1) and 6
                sb.append(((255 and (a shr shift)).toChar()))
            }
            n++
        }
        return sb.toString()
    }

    private fun opV(s: String): String = s.reversed()

    private fun opR(s: String, arg: Int): String {
        val dbl = ALPHABET + ALPHABET
        val sb = StringBuilder(s)
        for (i in sb.length - 1 downTo 0) {
            val idx = dbl.indexOf(sb[i])
            if (idx >= 0) {
                // JS substr(u-a,1) при отрицательном старте отсчитывает с конца
                var from = idx - arg
                if (from < 0) from = dbl.length + from
                sb[i] = dbl[from]
            }
        }
        return sb.toString()
    }

    private fun opS(s: String, arg: Int): String {
        val len = s.length
        if (len == 0) return s
        // псевдослучайная перестановка из vkopt: E=(g*(y+1)^E+y)%g, E=Math.abs(E)
        val perm = IntArray(len)
        var k = if (arg < 0) -arg else arg
        for (y in len - 1 downTo 0) {
            k = ((len * (y + 1)) xor (k + y)) % len
            perm[y] = k
        }
        val arr = s.toCharArray()
        for (c in 1 until len) {
            val pos = perm[len - 1 - c]
            val tmp = arr[c]
            arr[c] = arr[pos]
            arr[pos] = tmp
        }
        return String(arr)
    }

    private fun opI(s: String, arg: Int, vkId: Long): String = opS(s, (arg.toLong() xor vkId).toInt())

    private fun opX(s: String, arg: String): String {
        if (arg.isEmpty()) return s
        val key = arg[0].code
        val sb = StringBuilder()
        for (i in s.indices) {
            sb.append((s[i].code xor key).toChar())
        }
        return sb.toString()
    }

    fun unmask(url: String, vkId: Long): String {
        try {
            if (!url.contains("audio_api_unavailable")) return url
            val extraIdx = url.indexOf("?extra=")
            if (extraIdx < 0) return url
            val parts = url.substring(extraIdx + 7).split("#")
            if (parts.size < 2) return url
            var vals = vkB64Decode(parts[0])
            val opsRaw: String? = if (parts[1].isEmpty()) "" else vkB64Decode(parts[1])
            if (vals == null || opsRaw == null) return url
            if (opsRaw.isNotEmpty()) {
                val ops = opsRaw.split("\t")
                var i = ops.size - 1
                while (i >= 0) {
                    // операция: имя, затем аргументы через \v (вертикальная табуляция)
                    val opParts = ops[i].split("\u000B")
                    val name = opParts[0]
                    var arg1 = ""
                    if (opParts.size > 1) arg1 = opParts[1]
                    val intArg: Int? = arg1.toIntOrNull()
                    when (name) {
                        "v" -> vals = opV(vals)
                        "r" -> if (intArg != null) vals = opR(vals, intArg) else return url
                        "s" -> if (intArg != null) vals = opS(vals, intArg) else return url
                        "i" -> if (intArg != null) vals = opI(vals, intArg, vkId) else return url
                        "x" -> vals = opX(vals, arg1)
                        else -> return url // неизвестная операция — вернуть как есть
                    }
                    i--
                }
            }
            if (vals.length > 4 && vals.substring(0, 4) == "http") return vals
            return url
        } catch (_: Exception) {
            return url
        }
    }

    // ------------------------------------------------------------------
    // Битрейт (как в расширении: mp3 — размер/длительность; HLS — один
    // сегмент). Результат кэшируется по rawId.
    // ------------------------------------------------------------------

    fun probeBitrate(rawId: String, url: String, hls: Boolean, durationSec: Long): Int? {
        val cached: Int? = bitrateCache[rawId]
        if (cached != null) return cached
        if (durationSec <= 0) return null
        var kbps = 0
        if (!hls) {
            val len: Long? = SimpleHttp.contentLength(url)
            if (len == null) return null
            kbps = ((len * 8) / durationSec / 1000).toInt()
        } else {
            val raw: String? = SimpleHttp.get(url, "", agent(), 262144)
            if (raw == null) return null
            val seg = firstHlsSegmentInfo(raw, url)
            if (seg == null) return null
            val bytes: ByteArray? = SimpleHttp.getBytes(seg.first, 1024 * 1024)
            if (bytes == null) return null
            val segDur = seg.second
            if (segDur <= 0) return null
            val totalEst = bytes.size.toDouble() * (durationSec.toDouble() / segDur)
            kbps = (totalEst * 8 / durationSec / 1000).toInt()
        }
        if (kbps <= 0) return null
        bitrateCache[rawId] = kbps
        return kbps
    }

    // Первый медиа-сегмент плейлиста и его EXTINF-длительность.
    // Если это мастер-плейлист (#EXT-X-STREAM-INF) — берём первую вариацию.
    fun firstHlsSegmentInfo(playlistText: String, baseUrl: String): Pair<String, Double>? {
        val lines = playlistText.split("\n")
        var i = 0
        var segDur = 0.0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // мастер-плейлист: следующий непустой URL — дочерняя вариация
                var j = i + 1
                while (j < lines.size) {
                    val child = lines[j].trim()
                    if (child.isNotEmpty() && !child.startsWith("#")) {
                        val childUrl = resolveUrl(baseUrl, child)
                        val childText: String? = SimpleHttp.get(childUrl, "", agent(), 262144)
                        if (childText == null) return null
                        return firstHlsSegmentInfo(childText, childUrl)
                    }
                    j++
                }
                return null
            }
            if (line.startsWith("#EXTINF")) {
                val colon = line.indexOf(':')
                val comma = line.indexOf(',')
                val end = if (comma > colon) comma else line.length
                if (colon > 0 && end > colon + 1) {
                    val parsedDur: Double? = line.substring(colon + 1, end).toDoubleOrNull()
                    if (parsedDur != null) segDur = parsedDur
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val segUrl = resolveUrl(baseUrl, line)
                return Pair(segUrl, segDur)
            }
            i++
        }
        return null
    }

    fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        // относительный путь: режем базу до последнего '/'
        val slash = base.lastIndexOf('/')
        if (slash < 0) return rel
        return base.substring(0, slash + 1) + rel
    }

    // ------------------------------------------------------------------
    // Скачивание HLS-потока: плейлист -> сегменты подряд -> один файл .ts
    // (конвертации в mp3 нет — её у VK Next делают ffmpeg.wasm/сервер;
    // .ts играют VLC/MX Player и большинство плееров). Возвращает файл или null.
    // ------------------------------------------------------------------

    // 1.3.23: открыть поток в «Загрузки» под именем <name>.ts.
    // Android 10+ — MediaStore.Downloads (без разрешений), 8.0–9 — файл
    // в публичном каталоге Downloads. Возвращает поток, имя и (на 8–9)
    // файл для последующего завершения. Закрытие и финализация — finishTsOutput.
    private fun openTsOutput(ctx: Context, name: String): Triple<OutputStream, String, Any?>? {
        val safe = sanitize(name) + ".ts"
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp2t")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                values.put(MediaStore.MediaColumns.IS_PENDING, 1)
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) return null
                val os = resolver.openOutputStream(uri)
                if (os == null) return null
                return Triple(os, safe, uri)
            } catch (_: Exception) {
                return null
            }
        }
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dir != null && !dir.exists()) dir.mkdirs()
            val f = File(dir, safe)
            return Triple(FileOutputStream(f), safe, f)
        } catch (_: Exception) {
            return null
        }
    }

    // 1.3.23: финализация записи (снять IS_PENDING на Android 10+). Успешность
    // не критична: файл уже записан, недозапись лучше, чем потерять всё.
    private fun finishTsOutput(ctx: Context, handle: Any?) {
        if (handle == null) return
        if (Build.VERSION.SDK_INT >= 29 && handle is android.net.Uri) {
            try {
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(handle, values, null, null)
            } catch (_: Exception) {
            }
        }
    }

    // Скачивание HLS-потока: плейлист -> сегменты подряд -> один файл .ts в
    // «Загрузках» (конвертации в mp3 нет — её у VK Next делают ffmpeg.wasm/
    // сервер; .ts играют VLC/MX Player и большинство плееров). Возвращает
    // имя файла или null.
    fun downloadHls(ctx: Context, url: String, name: String, onProgress: (Int) -> Unit): String? {
        try {
            val raw: String? = SimpleHttp.get(url, "", agent(), 262144)
            if (raw == null) return null
            var text = raw
            var base = url
            // мастер-плейлист -> первая вариация
            if (text.contains("#EXT-X-STREAM-INF")) {
                val child: Pair<String, Double>? = firstHlsSegmentInfo(text, base)
                if (child == null) return null
                val childText: String? = SimpleHttp.get(child.first, "", agent(), 262144)
                if (childText == null) return null
                text = childText
                base = child.first
            }
            val segs = ArrayList<String>()
            val lines = text.split("\n")
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    segs.add(resolveUrl(base, line))
                    if (segs.size >= 600) break // страховка от бесконечных списков
                }
            }
            if (segs.isEmpty()) return null
            val out = openTsOutput(ctx, name)
            if (out == null) return null
            val stream = out.first
            val buf = ByteArray(65536)
            var done = 0
            try {
                for (i in segs.indices) {
                    val opened = java.net.URL(segs[i]).openConnection()
                    if (opened !is java.net.HttpURLConnection) {
                        return null
                    }
                    opened.connectTimeout = 15000
                    opened.readTimeout = 20000
                    opened.instanceFollowRedirects = true
                    val code = opened.responseCode
                    if (code !in 200..299) {
                        opened.disconnect()
                        return null
                    }
                    opened.inputStream.use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            stream.write(buf, 0, n)
                        }
                    }
                    opened.disconnect()
                    done++
                    onProgress((done * 100) / segs.size)
                }
            } finally {
                stream.close()
                finishTsOutput(ctx, out.third)
            }
            return out.second
        } catch (_: Exception) {
            return null
        }
    }

    fun sanitize(name: String): String {
        val bad = "/\\:*?\"<>|"
        val sb = StringBuilder()
        for (i in name.indices) {
            val ch = name[i]
            if (bad.indexOf(ch) >= 0) {
                sb.append('_')
            } else if (ch.code >= 32) {
                sb.append(ch)
            }
        }
        var out = sb.toString().trim()
        if (out.length > 120) out = out.substring(0, 120)
        if (out.isEmpty()) out = "vk_track"
        return out
    }
}
