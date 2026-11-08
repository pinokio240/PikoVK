package com.pikovk.browser.browser

import android.util.Log
import android.webkit.CookieManager

// Согласование «версионных» cookie VK с положением кнопки версии (1.3.3).
//
// ПРИЧИНА («переключение не срабатывает», лог от 07.11.2026): VK хранит в
// cookie-хранилище информацию об устройстве и версии сайта — remixmdevice
// («информация о мобильном устройстве»), remixscreen_width/height/dpr и др.
// (перечислены в политике cookie vk.ru/privacy/cookies). После сессии на
// m.vk.ru эти куки описывают ТЕЛЕФОН. Тогда при загрузке vk.ru/feed VK
// сервером (302) возвращает страницу на m.vk.ru/feed — даже при десктопном
// User-Agent. Раньше это давало бесконечный цикл «бесконечного обновления»,
// а после ограничения цикла (1.3.2) браузер «сдавался» и страница оставалась
// мобильной при кнопке в положении «полная» — переключение выглядело
// неработающим.
//
// РЕШЕНИЕ: при переключении/старте в положение «полная» выставляем десктопные
// значения и стираем мобильные маркеры; в положение «мобильная» — стираем все
// версионные куки, VK сам выставит реальные значения своей JS-строкой.
//
// 1.3.4: значение куки remixforce_full уточнено эмпирически — VK сам ставит
// ЕЁ при переходе по официальной ссылке «Версия для компьютера»
// (vk.ru/?_fm=1&_fm2=1; с 1.3.7 сами мы _fm к адресам vk.* НЕ добавляем —
// на десктопном хосте они вызывают отскок на m.vk.*, история — в README,
// раздел 1.3.7), и значение именно 2 со сроком ~3,5 часа.
// Повторяем VK один в один: значение 2, Max-Age 12600. Авторизационные
// куки (remixsid, remixttpid и т.п.) НЕ трогаются — сессии всех профилей
// остаются живыми.
//
// Все операции — через CookieManager, идемпотентны и безопасно повторяются
// перед каждой загрузкой страницы.
//
// 1.3.8 — устранена гонка записи кук: CookieManager.setCookie асинхронный,
// и загрузка, запущенная сразу после apply(), уходила в сеть со СТАРЫМ
// набором кук — VK возвращал мобильную версию; после сворачивания/разворачивания
// приложения куки успевали записаться, поэтому «переход срабатывал».
// Теперь apply() принимает колбэк onApplied: он вызывается (главный поток)
// только когда все куки пачки ФАКТИЧЕСКИ записаны в хранилище WebView
// (или по страховочному таймауту — см. CookieWriter). Вызывающий код
// стартует загрузку страницы только в этом колбэке.
//
// 1.3.11 — найдена ВТОРАЯ кука версии. Владелец (лог 08.11.2026): кука
// remixforce_full=2 записана и видна в CookieManager, UA десктопный, но
// VK трижды серверно возвращал страницу на m.vk.ru/feed и кнопка
// откатывалась в «мобильная». При этом переключение ВНУТРИ страницы VK
// работало. Проверка ответов сервера VK (08.11.2026): официальный
// переключатель m.vk.…/?_fm=1&_fm2=1 ставит ПАРУ кук —
//   remixforce_full=2  (срок ~3,5 часа) и
//   remixshow_fvbar=1  (срок ~1 год),
// а мы писали только первую. Теперь пишем обе; кроме того, само
// переключение идёт через штатный адрес переключателя VK — см.
// VkLinks.toDesktopSwitchUrl и VkWebViewClient (основной механизм —
// VK выставляет свои куки серверно, мы лишь дублируем их заранее).
//
// 1.3.13 — переключение версии ведётся по ПАТТЕРНУ ВЛАДЕЛЬЦА: полный хост
// vk.ru / vk.com с параметром open_in_web=1 (см. VkLinks.toDesktopSwitchUrl;
// лог владельца 08.11.2026 16:22: штатный переключатель с _fm отдал
// мобильные ассеты, паттерн владельца полную версию держит). Пара
// десктопных кук (remixforce_full=2 + remixshow_fvbar=1) по-прежнему
// записывается заранее: она соответствует полной версии, которую VK
// отдаёт на этом паттерне.
object VkVersionCookies {

    private const val TAG = "PikoVK-Nav"

    // Сайты, для которых согласуем версию (десктопные домены VK).
    private val SITES = listOf("https://vk.ru", "https://vk.com")

    // Мобильные хосты: на них VK может держать host-only кукисы версии,
    //  которые стирание по Domain=.vk.ru НЕ задевает (запись хранится с
    //  другим доменом). Стираем и там (1.3.6, перестраховка).
    private val MOBILE_SITES = listOf("https://m.vk.ru", "https://m.vk.com")

    // Куки «принудительной полной версии» (политика cookie VK; значение 2
    //  и срок ~3,5 часа — один в один как ставит сам VK официальная ссылка
    //  «Версия для компьютера», проверено запросами 07.11.2026).
    private const val FORCE_FULL = "remixforce_full"

    // 1.3.11: вторая кука версии — «показывать панель полной версии».
    //  Официальный переключатель VK ставит её ВМЕСТЕ с remixforce_full
    //  (проверено ответами сервера 08.11.2026), срок ~1 год. Без неё
    //  одной remixforce_full=2 VK недостаточно: страницу всё равно
    //  возвращает на m.vk.* (лог владельца от 08.11.2026).
    private const val SHOW_FVBAR = "remixshow_fvbar"

    // Куки параметров экрана: на десктопе выставляем «монитор», иначе стираем.
    private val SCREEN_COOKIES = linkedMapOf(
        "remixscreen_width" to "1920",
        "remixscreen_height" to "1080",
        "remixscreen_dpr" to "1",
        "remixscreen_depth" to "24",
        "remixscreen_orient" to "1",
        "remixscreen_winzoom" to "1"
    )

    // Мобильные маркеры: стираем в ОБЕИХ версиях, чтобы VK выставил свежие
    //  значения под текущий контекст (remixua — сохранённый User-Agent).
    private val MOBILE_MARKERS = listOf("remixmdevice", "remixmobile_app_r", "remixua")

    // Срок жизни десктопных значений экрана (короткий — VK обновляет их JS-ом).
    private const val YEAR_SEC = 31536000

    // Срок жизни кук «полной версии» — как у самого VK (~3,5 часа).
    private const val FORCE_FULL_SEC = 12600

    // Привести версионные куки VK к положению кнопки.
    // @param desktop true — полная версия (vk.*), false — мобильная (m.vk.*).
    // @param onApplied вызывается на главном потоке, когда все куки пачки
    //   ФАКТИЧЕСКИ записаны (или по страховочному таймауту CookieWriter).
    //   Загрузку страницы стартуем только в нём — иначе запрос может уйти
    //   в сеть со старыми куками (гонка 1.3.8, см. шапку класса).
    fun apply(desktop: Boolean, onApplied: (() -> Unit)? = null) {
        val writes = ArrayList<CookieWriter.Write>()
        for (site in SITES) {
            val domain: String
            if (site == "https://vk.ru") {
                domain = ".vk.ru"
            } else {
                domain = ".vk.com"
            }
            if (desktop) {
                addSet(writes, site, domain, FORCE_FULL, "2", FORCE_FULL_SEC)
                addSet(writes, site, domain, SHOW_FVBAR, "1", YEAR_SEC)
                for ((name, value) in SCREEN_COOKIES) {
                    addSet(writes, site, domain, name, value, FORCE_FULL_SEC)
                }
            } else {
                addClear(writes, site, domain, FORCE_FULL)
                addClear(writes, site, domain, SHOW_FVBAR)
                for (name in SCREEN_COOKIES.keys) {
                    addClear(writes, site, domain, name)
                }
            }
            for (marker in MOBILE_MARKERS) {
                addClear(writes, site, domain, marker)
            }
        }
        // 1.3.6: host-only кукисы мобильных хостов — стирание по .vk.ru
        // их не задевает, а VK может выставить remixmdevice именно так.
        for (mobileSite in MOBILE_SITES) {
            val mobileDomain: String
            if (mobileSite == "https://m.vk.ru") {
                mobileDomain = "m.vk.ru"
            } else {
                mobileDomain = "m.vk.com"
            }
            for (marker in MOBILE_MARKERS) {
                addClear(writes, mobileSite, mobileDomain, marker)
            }
            if (!desktop) {
                // в мобильную версию версионные кукисы VK выставит сам
                addClear(writes, mobileSite, mobileDomain, FORCE_FULL)
                addClear(writes, mobileSite, mobileDomain, SHOW_FVBAR)
                for (name in SCREEN_COOKIES.keys) {
                    addClear(writes, mobileSite, mobileDomain, name)
                }
            }
        }
        CookieWriter.writeAll(CookieManager.getInstance(), writes) {
            if (desktop) {
                Log.i(
                    TAG,
                    "версионные cookie VK записаны: десктопные " +
                        "(remixforce_full=2, remixshow_fvbar=1, экран 1920x1080)"
                )
            } else {
                Log.i(TAG, "версионные cookie VK стёрты: мобильная версия выставит свои")
            }
            val cb = onApplied
            if (cb != null) cb()
        }
    }

    // Имена версионных кук для диагностики (без сессионных — приватность).
    private val DIAG_NAMES = listOf(FORCE_FULL, SHOW_FVBAR, "remixmdevice", "remixscreen_width")

    // Короткая диагностическая строка по версионным кукам vk.ru и m.vk.ru —
    // видна в logcat по фильтру "PikoVK-Nav". Авторизационные куки НЕ логируются.
    fun diagnose(): String {
        val cm = CookieManager.getInstance()
        val desktop: String = diagnoseSite(cm, "https://vk.ru")
        val mobile: String = diagnoseSite(cm, "https://m.vk.ru")
        return "vk.ru: " + desktop + "; m.vk.ru: " + mobile
    }

    private fun diagnoseSite(cm: CookieManager, site: String): String {
        val raw: String? = cm.getCookie(site)
        if (raw == null) {
            return "нет кук"
        }
        val parts = StringBuilder()
        for (pair in raw.split("; ")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val name = pair.substring(0, idx)
            if (DIAG_NAMES.contains(name)) {
                if (parts.isNotEmpty()) parts.append(", ")
                parts.append(pair)
            }
        }
        if (parts.isEmpty()) {
            return "версионных кук нет"
        }
        return parts.toString()
    }

    // Добавить в пачку запись «выставить (или заменить) именованную куку».
    private fun addSet(
        writes: MutableList<CookieWriter.Write>,
        site: String,
        domain: String,
        name: String,
        value: String,
        maxAgeSec: Int
    ) {
        writes.add(
            CookieWriter.Write(site, "$name=$value; Domain=$domain; Path=/; Secure; Max-Age=$maxAgeSec")
        )
    }

    // Добавить в пачку запись «стереть именованную куку» (Max-Age=0).
    private fun addClear(
        writes: MutableList<CookieWriter.Write>,
        site: String,
        domain: String,
        name: String
    ) {
        writes.add(CookieWriter.Write(site, "$name=; Domain=$domain; Path=/; Secure; Max-Age=0"))
    }
}
