package com.pikovk.browser.profile

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebStorage

// Мультиаккаунт: до 3 независимых сессий VK.
//
// Реализация — снимки cookie-хранилища (CookieManager). Каждый слот хранит
// свои cookie-наборы для vk.ru и id.vk.ru (VK ID). Переключение мгновенное,
// без перелогина: сессия текущего слота сохраняется, cookie-хранилище
// очищается, восстанавливается снимок выбранного слота, страница
// перезагружается.
//
// Почему не androidx.webkit ProfileStore: этому API нужен очень свежий
// системный WebView и однозначной привязки профиля к WebView-инстансу нет;
// снимки cookie дают тот же пользовательский результат (3 слота, быстрое
// переключение) и работают на любом устройстве с Android 8+.
object ProfileManager {

    const val MAX_PROFILES = 3

    private const val FILE = "vkb_profiles"
    private lateinit var sp: SharedPreferences

    // URL, для которых снимаем cookie-набор.
    private val COOKIE_URLS = linkedMapOf(
        "vk" to "https://vk.ru",
        "id" to "https://id.vk.ru"
    )

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun activeSlot(): Int = sp.getInt("active_slot", 0).coerceIn(0, MAX_PROFILES - 1)

    fun slotHasSession(slot: Int): Boolean =
        !sp.getString("s${slot}_vk", null).isNullOrEmpty()

    fun slotLabel(slot: Int): String = "Профиль ${slot + 1}"

    // Сохранить текущие cookie-сессии в указанный слот (одной записью).
    fun saveCurrentSession(slot: Int) {
        val cm = CookieManager.getInstance()
        val vkCookies: String? = cm.getCookie("https://vk.ru")
        val hasVkSession: Boolean = vkCookies != null && vkCookies.isNotEmpty()
        val e = sp.edit()
        for ((key, url) in COOKIE_URLS) {
            e.putString("s${slot}_$key", cm.getCookie(url))
        }
        e.putBoolean("has_$slot", hasVkSession)
        e.apply()
    }

    // Переключиться на слот. Возвращает URL для перезагрузки страницы.
    // Вызывается только с UI-потока.
    fun switchTo(slot: Int) {
        val target = slot.coerceIn(0, MAX_PROFILES - 1)
        val old = activeSlot()
        if (target == old) return
        saveCurrentSession(old)

        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        try {
            // localStorage тоже изолируем: VK хранит в нём состояние плеера и ленты
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
        }

        restore(target)
        sp.edit().putInt("active_slot", target).apply()
    }

    private fun restore(slot: Int) {
        val cm = CookieManager.getInstance()
        for ((key, url) in COOKIE_URLS) {
            val raw: String? = sp.getString("s${slot}_$key", null)
            if (raw == null) continue // для слота нет снимка — пропускаем набор
            for (pair in raw.split("; ")) {
                if (pair.isBlank() || !pair.contains("=")) continue
                // vk.ru: доменная cookie на .vk.ru — её видят и m.vk.ru, и www;
                // id.vk.ru: host-only cookie VK ID
                val attrs =
                    if (key == "vk") "; Domain=.vk.ru; Path=/; Secure"
                    else "; Path=/; Secure"
                runCatching { cm.setCookie(url, pair + attrs) }
            }
        }
        cm.flush()
    }

    // Выйти из аккаунта в слоте: очистить и снимок, и (если он активен) текущие cookie.
    fun logout(slot: Int) {
        if (slot == activeSlot()) {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            try {
                WebStorage.getInstance().deleteAllData()
            } catch (_: Exception) {
            }
        }
        val e = sp.edit()
        for (key in COOKIE_URLS.keys) e.putString("s${slot}_$key", null)
        e.putBoolean("has_$slot", false)
        e.apply()
    }

    // Cookie-заголовок активной сессии (для фонового опроса уведомлений).
    fun activeCookieHeader(cm: CookieManager): String? = cm.getCookie("https://vk.ru")
}
