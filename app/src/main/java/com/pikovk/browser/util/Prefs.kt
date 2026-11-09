package com.pikovk.browser.util

import android.content.Context
import android.content.SharedPreferences
import com.pikovk.browser.browser.UserAgents

// Единое хранилище настроек браузера.
//
// Правила явности (1.3.1): в коде запрещены `!!` и `?:` — каждый nullable
// проверяется явным `if (x == null)`, значение по умолчанию возвращается
// отдельной веткой. `lateinit` для sp — осознанно: init() вызывается
// в App.onCreate до любого доступа к настройкам.
//
// uiDirty — флаг «настройки, влияющие на WebView (расширения, UA), изменились».
// MainActivity при возврате на экран пересоздаёт WebView, чтобы всё применилось.
object Prefs {
    private const val FILE = "vkb_prefs"

    const val UI_MINIMAL = "minimal"
    const val UI_FULL = "full"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun e(): SharedPreferences.Editor = sp.edit()

    // ---------- Интерфейс ----------
    // Минимализм (только нижняя панель) или полный (адресная строка сверху).
    var uiMode: String
        get() {
            val stored: String? = sp.getString("ui_mode", UI_MINIMAL)
            if (stored == null) return UI_MINIMAL
            return stored
        }
        set(v) {
            e().putString("ui_mode", v).apply()
        }

    // ---------- Расширения (движок ExtensionEngine) ----------
    // Блокировка рекламы: точные селекторы рекламных контейнеров VK,
    // ссылочный уровень (rv.mail.ru/adfox.ru), лид-формы (type=leadform,
    // сетевой блок общего рендерера app51478948) и сторонние счётчики.
    // 1.3.16: включена по умолчанию — владелец дважды за день прислал
    // примеры рекламы в ленте и прямо попросил блокировать. Выключается
    // одним тумблером; на установках, где тумблер переключали вручную,
    // сохранённый выбор сильнее дефолта.
    // 1.3.17: усилено после третьего примера (rv.mail.ru с новым banner_id,
    // «ну и почему я снова вижу рекламу?»): маркеры непомеченных карточек
    // (.ads_mark, ad_info.php, adsite_, data-mt-video-hls), штатные флаги
    // VK noAds/hide_ad, сетевой блок рекламного CDN mradx.net и
    // рекламных эндпоинтов VK.
    var extAdBlock: Boolean
        get() = sp.getBoolean("ext_ad_block", true)
        set(v) {
            e().putBoolean("ext_ad_block", v).apply()
        }

    var extDark: Boolean
        get() = sp.getBoolean("ext_dark", true)
        set(v) {
            e().putBoolean("ext_dark", v).apply()
        }

    var extMedia: Boolean
        get() = sp.getBoolean("ext_media", true)
        set(v) {
            e().putBoolean("ext_media", v).apply()
        }

    var extTweaks: Boolean
        get() = sp.getBoolean("ext_tweaks", false)
        set(v) {
            e().putBoolean("ext_tweaks", v).apply()
        }

    // ---------- Экран «Музыка» (1.3.23) ----------
    // web-токен VK (login.vk.ru?act=web_token, app_id 6287487) — как в
    // VK Music Player: минтится из cookie веб-сессии, кэшируется до expires.
    var musicToken: String
        get() {
            val s: String? = sp.getString("music_token", "")
            if (s == null) return ""
            return s
        }
        set(v) {
            e().putString("music_token", v).apply()
        }

    var musicTokenExp: Long
        get() = sp.getLong("music_token_exp", 0)
        set(v) {
            e().putLong("music_token_exp", v).apply()
        }

    var musicUserId: Long
        get() = sp.getLong("music_user_id", 0)
        set(v) {
            e().putLong("music_user_id", v).apply()
        }

    // ---------- Версия сайта и User-Agent ----------
    // Положение кнопки на нижней панели: полная (vk.ru + десктопный UA) или
    //  мобильная (m.vk.ru + мобильный UA) версия. UA и хост переключаются
    //  ТОЛЬКО вместе — иначе VK возвращает редиректом на «свою» версию.
    var desktopMode: Boolean
        get() = sp.getBoolean("desktop_mode", false)
        set(v) {
            e().putBoolean("desktop_mode", v).apply()
        }

    // «Всегда полная версия»: при каждом запуске кнопка синхронизируется
    //  в положение «полная» (MainActivity.onCreate). После запуска кнопку
    //  можно переключить на мобильную — её выбор действует до следующего
    //  запуска (раньше клиент перебивал выбор кнопки — переключение
    //  «не работало»).
    var autoDesktop: Boolean
        get() = sp.getBoolean("auto_desktop", false)
        set(v) {
            e().putBoolean("auto_desktop", v).apply()
        }

    // Ключ пресета User-Agent для мобильного режима.
    var uaPreset: String
        get() {
            val stored: String? = sp.getString("ua_preset", UserAgents.PRESET_REAL)
            if (stored == null) return UserAgents.PRESET_REAL
            return stored
        }
        set(v) {
            e().putString("ua_preset", v).apply()
        }

    // Ручной ввод User-Agent (используется, если пресет = custom).
    var customUa: String?
        get() = sp.getString("custom_ua", null)
        set(v) {
            e().putString("custom_ua", v).apply()
        }

    // Итоговый UA мобильного режима: явный разбор nullable-результата.
    fun effectiveMobileUa(): String {
        val resolved: String? = UserAgents.uaFor(uaPreset, customUa)
        if (resolved == null) return UserAgents.realAndroidUa()
        return resolved
    }

    // ---------- Тёмная тема VK (родные куки, 1.3.7) ----------
    // Куки тёмной темы VK (remixdark_color_scheme / remixcolor_scheme_mode,
    // официальная политика vk.ru/privacy/cookies) выставляются ОДИН РАЗ на
    // cookie-слот — после этого собственный переключатель темы в интерфейсе
    // VK остаётся главным, мы с ним не спорим. Повторное включение тумблера
    // «Тёмная тема» в настройках сбрасывает флаг и применяет куки заново
    // (см. VkThemeCookies.reapply).
    fun vkDarkCookiesDone(slot: Int): Boolean {
        return sp.getBoolean("vk_dark_done_$slot", false)
    }

    fun setVkDarkCookiesDone(slot: Int, done: Boolean) {
        e().putBoolean("vk_dark_done_$slot", done).apply()
    }

    // ---------- Диагностика ----------
    // Снифер ссылок (1.3.5): каждый открытый адрес с разбором параметров —
    // в logcat (тег PikoVK-Link, см. LinkSniffer). Включён по умолчанию;
    // журнал остаётся только на устройстве, но адреса могут содержать
    // токены — при необходимости выключается в настройках.
    var snifferEnabled: Boolean
        get() = sp.getBoolean("sniffer_enabled", true)
        set(v) {
            e().putBoolean("sniffer_enabled", v).apply()
        }

    // ---------- Фоновые уведомления ----------
    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify_enabled", false)
        set(v) {
            e().putBoolean("notify_enabled", v).apply()
        }
    // 1.3.20: отдельный тумблер уведомлений о личных сообщениях (мессенджер).
    // Служба общая с «Ответами»: живёт, пока включён хотя бы один тумблер.
    var imNotifyEnabled: Boolean
        get() = sp.getBoolean("im_notify_enabled", false)
        set(v) {
            e().putBoolean("im_notify_enabled", v).apply()
        }
    var notifyIntervalSec: Int
        get() = sp.getInt("notify_interval_sec", 60)
        set(v) {
            e().putInt("notify_interval_sec", v).apply()
        }

    // ---------- Фоновое воспроизведение (1.3.10) ----------
    // Пока приложение не на экране, служба mediaPlayback держит процесс
    // живым — WebView продолжает аудио/видео (см. PlaybackService).
    // Включено по умолчанию: обрыв звука при сворачивании — заявленный
    // владельцем дефект, служба его устраняет и ничего не блокирует.
    var backgroundPlayback: Boolean
        get() = sp.getBoolean("background_playback", true)
        set(v) {
            e().putBoolean("background_playback", v).apply()
        }

    // ---------- Флаг пересборки WebView ----------
    var uiDirty: Boolean
        get() = sp.getBoolean("ui_dirty", false)
        set(v) {
            e().putBoolean("ui_dirty", v).apply()
        }

    fun markDirty() {
        uiDirty = true
    }

    // Прочитать и сбросить флаг.
    fun consumeDirty(): Boolean {
        val d = uiDirty
        if (d) uiDirty = false
        return d
    }
}
