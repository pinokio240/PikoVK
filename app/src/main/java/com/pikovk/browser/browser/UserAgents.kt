package com.pikovk.browser.browser

import android.os.Build

// Пресеты User-Agent + константы для переключателя версии сайта.
//
// Ключевой сценарий: в мобильной веб-версии VK (m.vk.ru) нельзя создать
// или сохранить плейлист — VK урезал мобильный веб. Полная версия (vk.ru)
// умеет и то и другое. Кнопка на нижней панели переключает UA на десктопный
// и переносит открытую страницу с m.vk.ru на vk.ru.
object UserAgents {

    const val PRESET_ANDROID = "android"
    const val PRESET_REAL = "real"
    const val PRESET_IPHONE = "iphone"
    const val PRESET_WIN = "windows"
    const val PRESET_MAC = "mac"
    const val PRESET_LINUX = "linux"
    const val PRESET_CUSTOM = "custom"

    val PRESET_LABELS: Map<String, String> = linkedMapOf(
        PRESET_REAL to "Android (как реальное устройство)",
        PRESET_ANDROID to "Android (Chrome 130, Pixel)",
        PRESET_IPHONE to "iPhone (Safari)",
        PRESET_WIN to "Windows (Chrome, полная версия)",
        PRESET_MAC to "macOS (Safari)",
        PRESET_LINUX to "Linux (Firefox)",
        PRESET_CUSTOM to "Свой вариант (ручной ввод)"
    )

    fun uaFor(preset: String, custom: String?): String? = when (preset) {
        PRESET_ANDROID ->
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36"
        PRESET_REAL -> realAndroidUa()
        PRESET_IPHONE ->
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        PRESET_WIN ->
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"
        PRESET_MAC ->
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        PRESET_LINUX ->
            "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
        PRESET_CUSTOM -> {
            val raw: String? = custom
            if (raw == null) {
                null
            } else if (raw.isNotBlank()) {
                raw
            } else {
                null
            }
        }
        else -> null
    }

    fun realAndroidUa(): String =
        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36"

    // UA полной версии для кнопки на нижней панели. Chrome 156 — как в
    //  HAR-дампе владельца (реальный десктопный Chrome 08.11.2026): VK
    //  эвристики по свежести UA не задействуют «устаревший браузер».
    const val DESKTOP_UA: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

    // Мобильный UA для фонового опроса уведомлений.
    const val POLLER_UA: String =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36"
}
