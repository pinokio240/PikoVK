package com.pikovk.browser.bridge

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.pikovk.browser.media.PlaybackService
import com.pikovk.browser.util.Prefs

// JS-мост расширений: window.VkbBridge.
//
// download(url): файл уходит в системный DownloadManager (без лишних
// разрешений — они запрашиваются только по факту скачивания). Скачивание
// всегда подтверждает пользователь в диалоге, домен не проверяется.
//
// 1.3.21: playerState(title, artist, playing) — репорт плеера, питает
//       медиашторку PlaybackService (MediaSession).
//
// Явность (1.3.1): nullable-значения проверяются через if, без `?:` и `!!`;
// недоступный DownloadManager даёт IllegalStateException, которую ловит
// download() и показывает тост — пользователь видит отказ, а не падение.
class VkbBridge(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun download(url: String) {
        runCatching { VkbBridge.enqueueNamed(context, url, fileName(url)) }
            .onFailure { toast("Не удалось начать скачивание") }
    }

    // 1.3.23: системный диалог «Поделиться». Мост для шима navigator.share
    // (SHARE_JS в ExtensionEngine): Android WebView не реализует Web Share
    // API, и VK по его отсутствию молча ломал кнопку «Поделиться» у видео.
    // Открытие chooser — через main-хендлер (вызов приходит из JS-потока).
    @JavascriptInterface
    fun share(text: String, url: String) {
        main.post {
            runCatching {
                val send = Intent(Intent.ACTION_SEND)
                send.type = "text/plain"
                // текст и ссылка вместе; ссылка главная (VK шлёт url отдельным полем)
                val body: String = when {
                    url.isNotBlank() && text.isNotBlank() && text != url -> text + " " + url
                    url.isNotBlank() -> url
                    else -> text
                }
                send.putExtra(Intent.EXTRA_TEXT, body)
                context.startActivity(Intent.createChooser(send, "Поделиться"))
            }.onFailure { toast("Не удалось открыть общий доступ") }
        }
    }

    // 1.3.21: состояние плеера VK (вызывается из PLAYER_JS раз в 3 с).
    @JavascriptInterface
    fun playerState(title: String, artist: String, playing: String) {
        PlaybackService.updateMeta(title, artist, playing == "1")
        // 1.3.22: упреждающий запуск службы, ПОКА приложение на экране.
        // Официальная рекомендация Android 17 («Background audio hardening»,
        // developer.android.com/about/versions/17/changes/bg-audio): FGS типа
        // mediaPlayback нужно запускать, пока приложение видимо, — служба,
        // стартованная уже при погашенном экране (старое поведение: запуск
        // только в onStop), теряет статус while-in-use, и блокировка экрана
        // молча глушит звук. Репорт playing прилетает сразу после тапа
        // пользователя — это и есть момент «приложение видимо».
        if (playing == "1" && Prefs.backgroundPlayback) {
            val alive = PlaybackService.isRunning()
            if (!alive) {
                PlaybackService.start(context)
            }
        }
    }

    // Общая точка входа для скачиваний (используется и экраном «Музыка»).
    // Ошибка доступности DownloadManager
    // бросается наружу — вызывающий показывает тост.
    companion object {
        fun enqueueNamed(ctx: Context, url: String, name: String) {
            val dmService: Any? = ctx.getSystemService(Context.DOWNLOAD_SERVICE)
            if (dmService !is DownloadManager) {
                // Явный отказ вместо тихого ClassCastException: поймается выше
                throw IllegalStateException("DownloadManager недоступен в системе")
            }
            val req = DownloadManager.Request(Uri.parse(url))
            val agent: String? = System.getProperty("http.agent")
            if (agent == null) {
                req.addRequestHeader("User-Agent", "PikoVK/1.0")
            } else {
                req.addRequestHeader("User-Agent", agent)
            }
            val cookie: String? = CookieManager.getInstance().getCookie(url)
            if (cookie != null) {
                req.addRequestHeader("Cookie", cookie)
            }
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            dmService.enqueue(req)
            val ui = Handler(Looper.getMainLooper())
            ui.post { Toast.makeText(ctx, "Скачивание началось: $name", Toast.LENGTH_SHORT).show() }
        }
    }

    // Имя файла из последнего сегмента пути; пустое/отсутствующее — «vk_file».
    private fun fileName(url: String): String {
        val parsed: Uri = Uri.parse(url)
        val segment: String? = parsed.lastPathSegment
        if (segment == null) return "vk_file"
        if (segment.isBlank()) return "vk_file"
        return segment
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}
