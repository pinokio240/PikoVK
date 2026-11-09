package com.pikovk.browser.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Запись пачки кук в CookieManager с гарантией порядка (1.3.8).
//
// ПРИЧИНА («переключение срабатывает только после сворачивания/разворачивания»):
// CookieManager.setCookie — АСИНХРОННЫЙ. Запись ставится в очередь внутреннего
// потока WebView, и загрузка, запущенная сразу после неё, уходила в сеть со
// СТАРЫМ набором кук. VK по старым кукам возвращал мобильную версию, а после
// сворачивания приложения (процесс пересоздаётся, очередь давно обработана)
// «всё работало». Классическая гонка записи кук и сетевого запроса.
//
// РЕШЕНИЕ: используется вариант setCookie с колбэком — он срабатывает по факту
// записи куки в хранилище. onCommitted вызывается, когда завершены ВСЕ записи
// пачки, либо по страховочному таймауту (на части сборок системного WebView
// колбэк может не прийти вовсе), всегда на главном потоке. Что случится
// раньше — то и побеждает: это fail-open, поведение не хуже прежнего.
//
// Ошибка одной записи не отменяет остальные и не блокирует загрузку.
object CookieWriter {

    private const val TAG = "PikoVK-Nav"

    // Страховка: сколько максимум ждём колбэков записей, мс.
    private const val COMMIT_TIMEOUT_MS = 500L

    // Одна запись куки: адрес сайта и готовая строка куки (с атрибутами).
    class Write(val site: String, val cookieLine: String) {
        // Имя куки — для журнала без чувствительных значений.
        fun name(): String = cookieLine.substringBefore(';').substringBefore('=')
    }

    // Записать все куки пачки и вызвать onCommitted (главный поток) после
    // фактической записи всех кук либо по таймауту — что случится раньше.
    fun writeAll(cm: CookieManager, writes: List<Write>, onCommitted: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        // Выполняется ровно один раз: по последнему колбэку или по таймауту.
        fun finish() {
            if (finished.compareAndSet(false, true)) {
                cm.flush()
                main.post { onCommitted() }
            }
        }
        if (writes.isEmpty()) {
            finish()
            return
        }
        val remaining = AtomicInteger(writes.size)
        for (w in writes) {
            try {
                cm.setCookie(w.site, w.cookieLine) { ok ->
                    if (!ok) {
                        Log.w(TAG, "кука не записана: ${w.name()} (${w.site})")
                    }
                    if (remaining.decrementAndGet() == 0) finish()
                }
            } catch (_: Exception) {
                // запись не критична: VK останется в текущей версии/теме
                if (remaining.decrementAndGet() == 0) finish()
            }
        }
        main.postDelayed({ finish() }, COMMIT_TIMEOUT_MS)
    }
}
