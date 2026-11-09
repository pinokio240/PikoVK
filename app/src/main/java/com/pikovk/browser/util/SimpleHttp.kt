package com.pikovk.browser.util

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

// Простой GET с cookie (1.3.20): общий помощник фонового опроса.
// Раньше такой запрос был приватным методом NotifyService; когда к опросу
// «Ответов» добавился опрос личных сообщений (ImPoller), логика вынесена
// сюда, чтобы не дублировать.
//
// Поведение прежнее и fail-open: любая ошибка сети, таймаут или плохой
// статус дают null — вызывающий просто ждёт следующего цикла опроса.
object SimpleHttp {

    fun get(url: String, cookie: String, userAgent: String, maxBytes: Int): String? {
        var conn: HttpURLConnection? = null
        try {
            val opened = URL(url).openConnection()
            if (opened !is HttpURLConnection) return null // не http(s) — не наш случай
            conn = opened
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            conn.setRequestProperty("Accept-Language", "ru")
            val ok = conn.responseCode in 200..299
            val out = ByteArrayOutputStream()
            if (ok) {
                conn.inputStream.use { ins ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) break // страховка от гигантских ответов
                    }
                }
            }
            conn.disconnect()
            if (ok) return out.toString("UTF-8")
            return null
        } catch (_: Exception) {
            val c = conn
            if (c != null) c.disconnect()
            return null
        }
    }

    // 1.3.23: POST с произвольными заголовками (экран «Музыка»: web_token
    // login.vk.ru и методы audio.* на api.vk.ru, оба требуют заголовок Origin
    // https://vk.ru и cookie web-сессии). Тело — уже собранная form-urlencoded
    // строка. Fail-open: null при любой ошибке.
    fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        maxBytes: Int
    ): String? {
        var conn: HttpURLConnection? = null
        try {
            val opened = URL(url).openConnection()
            if (opened !is HttpURLConnection) return null
            conn = opened
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.instanceFollowRedirects = true
            val it = headers.entries.iterator()
            while (it.hasNext()) {
                val h = it.next()
                conn.setRequestProperty(h.key, h.value)
            }
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { os -> os.write(bytes) }
            val ok = conn.responseCode in 200..299
            val out = ByteArrayOutputStream()
            val ins = if (ok) conn.inputStream else conn.errorStream
            if (ins != null) {
                ins.use { stream ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) break
                    }
                }
            }
            conn.disconnect()
            if (!ok) return null
            return out.toString("UTF-8")
        } catch (_: Exception) {
            val c = conn
            if (c != null) c.disconnect()
            return null
        }
    }

    // 1.3.23: Content-Length для оценки битрейта mp3 (как в VK Music Saver:
    // kbps = размер * 8 / длительность). null — заголовка нет (CDN отдал
    // chunked) — тогда значок битрейта просто не показываем.
    fun contentLength(url: String): Long? {
        var conn: HttpURLConnection? = null
        try {
            val opened = URL(url).openConnection()
            if (opened !is HttpURLConnection) return null
            conn = opened
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val len = conn.contentLengthLong
            conn.disconnect()
            if (len > 0) return len
            return null
        } catch (_: Exception) {
            val c = conn
            if (c != null) c.disconnect()
            return null
        }
    }

    // 1.3.23: первые maxBytes бинарного ответа (проба битрейта HLS по одному
    // сегменту — ровно как в VK Music Player: один сегмент + EXTINF).
    fun getBytes(url: String, maxBytes: Int): ByteArray? {
        var conn: HttpURLConnection? = null
        try {
            val opened = URL(url).openConnection()
            if (opened !is HttpURLConnection) return null
            conn = opened
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            val ok = conn.responseCode in 200..299
            if (!ok) {
                conn.disconnect()
                return null
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) break
                }
            }
            conn.disconnect()
            return out.toByteArray()
        } catch (_: Exception) {
            val c = conn
            if (c != null) c.disconnect()
            return null
        }
    }
}
