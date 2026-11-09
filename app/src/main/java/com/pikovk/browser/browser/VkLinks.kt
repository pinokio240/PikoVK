package com.pikovk.browser.browser

import android.net.Uri

// Утилиты ссылок VK: переключение мобильной (m.vk.ru / m.vk.com) и полной
// (vk.ru / vk.com) версии.
//
// Переключается ТОЛЬКО хост, разбор через Uri:
//  - поддомены не трогаются: id.vk.ru, oauth.vk.ru, vkvideo.ru и т.д. остаются
//    как есть (их версионная пара не существует);
//  - ссылки на vk.ru/m.vk.ru внутри query-параметров внешней страницы
//    не искажаются (старая строковая замена "://vk." могла их задеть);
//  - путь, query и фрагмент сохраняются один в один.
//
// Никаких блокировок: браузер загружает всё, что запросила страница
// (VK Mini Apps, CDN, внешние ресурсы).
object VkLinks {

    // m.vk.ru -> vk.ru (переключение на полную версию, работают плейлисты).
    fun toDesktopUrl(url: String): String = switchHost(url) { host ->
        when (host) {
            "m.vk.ru", "www.vk.ru" -> "vk.ru"
            "m.vk.com", "www.vk.com" -> "vk.com"
            else -> null
        }
    }

    // vk.ru -> m.vk.ru (обратно на мобильную версию).
    fun toMobileUrl(url: String): String = switchHost(url) { host ->
        when (host) {
            "vk.ru", "www.vk.ru" -> "m.vk.ru"
            "vk.com", "www.vk.com" -> "m.vk.com"
            else -> null
        }
    }

    // Цель «полной версии» (1.3.13): полный хост vk.ru / vk.com с параметром
    // open_in_web=1 — паттерн владельца (например, https://vk.ru/feed?open_in_web=1).
    //
    // ПРИЧИНА (лог владельца 08.11.2026 16:22): заход штатным переключателем
    // VK (хост m.vk., параметры _fm=1&_fm2=1, обе десктопные куки записаны,
    // UA десктопный) VK обслужил МОБИЛЬНЫМИ ассетами (st.vk.ru/dist/mobile/).
    // Паттерн владельца «полный хост + open_in_web=1» полную версию держит.
    //
    // Правила:
    //  - хосты m. поддомен, «голый» и www. полного домена -> vk.ru / vk.com;
    //  - прочие поддомены (id.vk.ru, oauth.vk.ru, vkvideo.ru) не трогаем — "";
    //  - путь и фрагмент сохраняются; параметры _fm и _fm2 выбрасываются
    //    (на полном хосте они означают «переход в процессе» и провоцируют
    //    отскок, см. 1.3.7), остальные параметры сохраняются как были;
    //  - open_in_web=1 ставится всегда в конец, дублей не создаём;
    //  - любая ошибка разбора — "" (fail-open: вызывающий код оставляет
    //    адрес как есть).
    fun toDesktopSwitchUrl(url: String): String {
        val parsed: Uri? = try {
            Uri.parse(url.trim())
        } catch (_: Exception) {
            null
        }
        if (parsed == null) return ""
        val rawHost: String? = parsed.host
        if (rawHost == null) return ""
        val scheme: String? = parsed.scheme
        if (scheme == null) return ""
        val desktopHost: String = when (rawHost.lowercase()) {
            "m.vk.ru", "vk.ru", "www.vk.ru" -> "vk.ru"
            "m.vk.com", "vk.com", "www.vk.com" -> "vk.com"
            else -> return ""
        }
        val builder: Uri.Builder = Uri.Builder()
            .scheme(scheme)
            .encodedAuthority(desktopHost)
            .encodedPath(parsed.encodedPath)
        val fragment: String? = parsed.encodedFragment
        if (fragment != null) builder.encodedFragment(fragment)
        // Чужие параметры сохраняем как были; служебные _fm/_fm2 выбрасываем.
        val names: Set<String> = try {
            parsed.getQueryParameterNames()
        } catch (_: Exception) {
            emptySet()
        }
        for (name in names) {
            if (name == "_fm" || name == "_fm2" || name == "open_in_web") continue
            val value: String = try {
                val v: String? = parsed.getQueryParameter(name)
                if (v == null) "" else v
            } catch (_: Exception) {
                ""
            }
            builder.appendQueryParameter(name, value)
        }
        builder.appendQueryParameter("open_in_web", "1")
        return try {
            builder.build().toString()
        } catch (_: Exception) {
            ""
        }
    }

    // Хост страницы — мобильная версия VK (m.vk.ru / m.vk.com)?
    // Проверяется строго по хосту, поэтому ссылка на m.vk.ru внутри
    // query-параметра чужого домена не даст ложного срабатывания.
    fun isMobileHost(url: String): Boolean {
        val host: String? = try {
            val parsed: Uri = Uri.parse(url.trim())
            val rawHost: String? = parsed.host
            if (rawHost == null) null else rawHost.lowercase()
        } catch (_: Exception) {
            null
        }
        return host == "m.vk.ru" || host == "m.vk.com"
    }

    // Пересобрать URL, заменив хост по правилу mapHost. Любая неудача —
    // вернуть исходный URL без изменений (fail-open). Явно (1.3.1):
    // без `?:` и `!!`, каждый nullable проверяется if-ом.
    private fun switchHost(url: String, mapHost: (String) -> String?): String {
        val parsed: Uri? = try {
            Uri.parse(url.trim())
        } catch (_: Exception) {
            null
        }
        if (parsed == null) return url
        val rawHost: String? = parsed.host
        if (rawHost == null) return url
        val host = rawHost.lowercase()
        val newHost = mapHost(host)
        if (newHost == null) return url
        return try {
            parsed.buildUpon().encodedAuthority(newHost).build().toString()
        } catch (_: Exception) {
            url
        }
    }
}
