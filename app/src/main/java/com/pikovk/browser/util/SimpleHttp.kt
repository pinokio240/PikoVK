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
}
