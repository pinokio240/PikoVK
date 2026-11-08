package com.pikovk.browser.browser

import android.util.Log
import android.webkit.CookieManager
import com.pikovk.browser.profile.ProfileManager
import com.pikovk.browser.util.Prefs

// Родная тёмная тема VK через её собственные куки (1.3.7).
//
// ПРИЧИНА («по умолчанию светлая тема, а не чёрная»): расширение «Тёмная
// тема» красит страницу CSS-инверсией (filter: invert) — это запасной
// механизм; если скрипт не успел примениться или VK изменил разметку,
// страница остаётся светлой. Сам VK хранит выбор темы в куках
// remixdark_color_scheme и remixcolor_scheme_mode (официальная политика
// vk.ru/privacy/cookies): без них VK открывается светлым независимо от
// системной темы.
//
// РЕШЕНИЕ: пока включён тумблер «Тёмная тема», выставляем эти куки ДО
// первой загрузки страницы — VK рендерит тёмную тему сам, нативно, вместе
// со своими реальными цветами (не инверсией). Куки ставятся ОДИН РАЗ на
// cookie-слот: дальше собственный переключатель темы в интерфейсе VK
// остаётся главным — мы не спорим с выбором владельца внутри VK. Повторное
// включение тумблера в настройках PikoVK применяет куки заново.
//
// ЗНАЧЕНИЯ эмпирические: VK публикует только имена кук, не формат значений.
// Fail-open: если VK значение не примет — останется как было (светлая тема
// и CSS-инверсия расширения продолжают работать как раньше).
//
// 1.3.8: куки записываются через CookieWriter — загрузка страницы стартует
// только после их фактической записи (CookieManager.setCookie асинхронный,
// иначе запрос мог уйти в сеть без кук темы — «по умолчанию светлая»).
object VkThemeCookies {

    private const val TAG = "PikoVK-Nav"

    // Куки тёмной цветовой схемы — имя из политики cookie vk.ru.
    private const val DARK_SCHEME = "remixdark_color_scheme"

    // Куки режима цветовой схемы — имя из политики cookie vk.ru.
    private const val SCHEME_MODE = "remixcolor_scheme_mode"

    // Сайты VK: кука на домен с точкой покрывает и vk.*, и его поддомен m.vk.*.
    private val SITES = listOf("https://vk.ru" to ".vk.ru", "https://vk.com" to ".vk.com")

    // Год — «запомнить выбор»; при смене темы VK сам обновит куку.
    private const val YEAR_SEC = 31536000

    // Выставить куки тёмной темы, если тумблер включён и слот ещё не настроен.
    // @param onApplied вызывается на главном потоке в любом случае — и когда
    //   куки записаны, и когда применять не нужно (тумблер выключен, слот уже
    //   настроен): загрузка страницы не должна ждать того, чего не будет.
    fun apply(onApplied: (() -> Unit)? = null) {
        val cb = onApplied
        if (!Prefs.extDark) {
            if (cb != null) cb()
            return
        }
        val slot = ProfileManager.activeSlot()
        if (Prefs.vkDarkCookiesDone(slot)) {
            if (cb != null) cb()
            return
        }
        setDarkCookies {
            Prefs.setVkDarkCookiesDone(slot, true)
            if (cb != null) cb()
        }
    }

    // Применить принудительно: тумблер «Тёмная тема» включён заново
    // (например, владелец стёр данные VK или хочет вернуть тёмную тему
    // после смены слота). Флаг слота обновляется, чтобы больше не повторять.
    fun reapply() {
        if (!Prefs.extDark) return
        setDarkCookies {
            Prefs.setVkDarkCookiesDone(ProfileManager.activeSlot(), true)
        }
    }

    private fun setDarkCookies(onCommitted: () -> Unit) {
        val writes = ArrayList<CookieWriter.Write>()
        for (site in SITES) {
            val url: String = site.first
            val domain: String = site.second
            addSet(writes, url, domain, DARK_SCHEME, "1")
            addSet(writes, url, domain, SCHEME_MODE, "dark")
        }
        CookieWriter.writeAll(CookieManager.getInstance(), writes) {
            Log.i(TAG, "куки тёмной темы VK записаны ($DARK_SCHEME=1, $SCHEME_MODE=dark)")
            onCommitted()
        }
    }

    // Добавить в пачку запись именованной куки на домен VK-сайта.
    private fun addSet(
        writes: MutableList<CookieWriter.Write>,
        site: String,
        domain: String,
        name: String,
        value: String
    ) {
        writes.add(
            CookieWriter.Write(site, "$name=$value; Domain=$domain; Path=/; Secure; Max-Age=$YEAR_SEC")
        )
    }
}
