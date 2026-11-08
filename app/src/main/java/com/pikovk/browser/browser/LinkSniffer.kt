package com.pikovk.browser.browser

import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.pikovk.browser.util.Prefs

// Снифер ссылок (1.3.5): каждый адрес, который открывает пользователь,
// разбирается на схему/хост/путь/параметры и построчно выводится в logcat
// под тегом "PikoVK-Link" — каждая строка снабжена тегом, поэтому фильтр
// `adb logcat -s PikoVK-Link` показывает запись целиком.
//
// Каких событий ждать (см. EVENT_*):
//  - клик        — переход, инициированный страницей (shouldOverrideUrlLoading);
//  - запрос      — каждый запрос главного фрейма, ВКЛЮЧАЯ промежуточные
//                  серверные редиректы: цепочка 302 видна покадрово;
//  - спа-переход — внутренняя навигация VK без перезагрузки (pushState):
//                  обычные клики по ленте/профилям приходят именно так;
//  - перезагрузка — явный reload той же страницы;
//  - скачивание  — файл, переданный системному загрузчику.
//
// Особые параметры разворачиваются для чтения:
//  - _to — обёртка act=client_url_proxy (штатная ссылка «Версия для
//    компьютера»): base64url, декодируется в целевой адрес;
//  - to (away.php) — Uri уже URL-декодирует значение, показывается как есть.
//
// Дедупликация: один адрес приходит из нескольких колбэков WebView
// (клик -> запрос -> история навигации). Повторы одного и того же адреса
// в пределах DEDUP_WINDOW_MS гасятся; клики логируются всегда. Каждая
// запись показывает предыдущий адрес («было:») — цепочки редиректов
// читаются как стенограмма.
//
// Приватность: журнал живёт только в logcat устройства и никуда не
// отправляется; параметры адресов могут содержать токены (oauth code и
// т.п.), поэтому снифер можно выключить в настройках («Диагностика»).
//
// Явность (1.3.1): без `?:` и `!!`; любая ошибка разбора — показываем
// сырой адрес (fail-open).
object LinkSniffer {

    // Тег журнала: фильтр logcat — `adb logcat -s PikoVK-Link`.
    const val TAG: String = "PikoVK-Link"

    // Переход по ссылке, инициированный страницей.
    const val EVENT_CLICK: String = "клик"

    // Запрос главного фрейма (в т.ч. каждый хоп цепочки серверных 302).
    const val EVENT_REQUEST: String = "запрос"

    // Внутренняя навигация VK без перезагрузки (pushState).
    const val EVENT_SPA: String = "спа-переход"

    // Явная перезагрузка той же страницы.
    const val EVENT_RELOAD: String = "перезагрузка"

    // Файл, отданный системному загрузчику.
    const val EVENT_DOWNLOAD: String = "скачивание"

    // Окно дедупликации: повторы того же адреса гасятся.
    private const val DEDUP_WINDOW_MS = 2500L

    // Максимальная длина значения параметра в строке журнала.
    private const val MAX_VALUE_LEN = 300

    private var lastUrl: String? = null
    private var lastAtMs = 0L

    // Записать адрес в журнал. Вызовы из главного потока и из потока
    // shouldInterceptRequest — поэтому @Synchronized.
    @Synchronized
    fun sniff(event: String, url: String) {
        if (!Prefs.snifferEnabled) return
        val now: Long = SystemClock.elapsedRealtime()
        val prev: String? = lastUrl
        val isRepeat: Boolean = event != EVENT_CLICK &&
            url == lastUrl && (now - lastAtMs) <= DEDUP_WINDOW_MS
        lastUrl = url
        lastAtMs = now
        if (isRepeat) return

        log("▸ $event: $url")
        parseAndLog(url, prev)
    }

    // ------------------------------------------------------------------
    // Разбор адреса
    // ------------------------------------------------------------------

    private fun parseAndLog(url: String, prev: String?) {
        val parsed: Uri? = try {
            Uri.parse(url.trim())
        } catch (_: Exception) {
            null
        }
        if (parsed == null) {
            log("  разбор не удался — показан только сырой адрес")
            logWas(prev)
            return
        }
        val rawScheme: String? = parsed.scheme
        if (rawScheme != "http" && rawScheme != "https") {
            // intent:, tel:, mailto: и прочее — параметров в нашем смысле нет
            log("  схема $rawScheme — параметры не разбираю")
            logWas(prev)
            return
        }
        val rawHost: String? = parsed.host
        val host: String
        if (rawHost == null) {
            host = "(хост не указан)"
        } else {
            host = rawHost
        }
        var path: String? = parsed.path
        if (path == null) {
            path = ""
        }
        if (path.isEmpty()) {
            path = "/"
        }
        log("  хост: $host   путь: $path")

        val names: Set<String>? = try {
            parsed.queryParameterNames
        } catch (_: Exception) {
            null
        }
        if (names == null || names.isEmpty()) {
            log("  параметры: нет")
        } else {
            for (name in names) {
                val value: String? = try {
                    parsed.getQueryParameter(name)
                } catch (_: Exception) {
                    null
                }
                if (value == null) {
                    log("  ? $name (значение не разобрано)")
                } else {
                    logParam(name, value)
                }
            }
        }

        val fragment: String? = parsed.fragment
        if (fragment != null && fragment.contains('=')) {
            // Параметры во фрагменте (oauth возвращает токены после #)
            logFragmentParams(fragment)
        } else if (fragment != null && fragment.isNotBlank()) {
            log("  # фрагмент: $fragment")
        }
        logWas(prev)
    }

    // Один параметр query; для _to дополнительно декодируем base64url.
    private fun logParam(name: String, value: String, prefix: String = "  ? ") {
        if (name == "_to") {
            val decoded: String? = decodeBase64Url(value)
            if (decoded != null) {
                log("$prefix$name = ${abbreviate(value)}")
                log("      ↳ base64 → $decoded")
                return
            }
        }
        log("$prefix$name = ${abbreviate(value)}")
    }

    // Параметры из фрагмента URL: access_token=...&expires_in=...
    private fun logFragmentParams(fragment: String) {
        val pairs: List<String> = fragment.split('&')
        for (pair in pairs) {
            val idx: Int = pair.indexOf('=')
            if (idx <= 0) continue
            val name: String = Uri.decode(pair.substring(0, idx))
            val value: String = Uri.decode(pair.substring(idx + 1))
            logParam(name, value, "  # ")
        }
    }

    // base64url -> текст; осмысленным считаем только результат-адрес
    // http(s) (так VK кодирует цель в client_url_proxy?_to=...). Алфавит
    // нормализуем сами, поэтому принимаются и стандартный base64, и
    // url-safe, с паддингом или без. Любая неудача — null (fail-open).
    private fun decodeBase64Url(raw: String): String? {
        return try {
            val standard: String = raw.replace('-', '+').replace('_', '/')
            val padded: String = when (standard.length % 4) {
                2 -> "$standard=="
                3 -> "$standard="
                else -> standard
            }
            val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            if (decoded.startsWith("https://") || decoded.startsWith("http://")) decoded else null
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Вывод
    // ------------------------------------------------------------------

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private fun logWas(prev: String?) {
        if (prev == null) {
            log("  было: —")
        } else {
            log("  было: $prev")
        }
    }

    // Длинные значения укорачиваем: logcat всё равно режет строки ~4 КБ.
    private fun abbreviate(v: String): String {
        if (v.length <= MAX_VALUE_LEN) return v
        return v.substring(0, MAX_VALUE_LEN) + "… (всего ${v.length} симв.)"
    }
}
