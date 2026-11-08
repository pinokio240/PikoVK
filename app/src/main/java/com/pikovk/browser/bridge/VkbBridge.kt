package com.pikovk.browser.bridge

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.widget.Toast

// JS-мост расширений: window.VkbBridge.
//
// Единственный метод download(url): файл уходит в системный DownloadManager
// (без лишних разрешений — они запрашиваются только по факту скачивания).
// Скачивание всегда подтверждает пользователь в диалоге, домен не проверяется.
//
// Явность (1.3.1): nullable-значения проверяются через if, без `?:` и `!!`;
// недоступный DownloadManager даёт IllegalStateException, которую ловит
// download() и показывает тост — пользователь видит отказ, а не падение.
class VkbBridge(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun download(url: String) {
        runCatching { enqueue(url) }
            .onFailure { toast("Не удалось начать скачивание") }
    }

    private fun enqueue(url: String) {
        val dmService: Any? = context.getSystemService(Context.DOWNLOAD_SERVICE)
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
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName(url))
        dmService.enqueue(req)
        toast("Скачивание началось")
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
