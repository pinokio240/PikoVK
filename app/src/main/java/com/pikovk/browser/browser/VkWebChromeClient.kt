package com.pikovk.browser.browser

import android.app.AlertDialog
import android.net.Uri
import android.view.View
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.pikovk.browser.ui.MainActivity

// Chrome-клиент: прогресс, диалоги confirm/alert (нужны расширению
// «скачивание медиа»), выбор файлов (загрузка аватара и т.п.),
// полноэкранное видео (кнопка «развернуть» в плеере VK видна, только
// когда WebView умеет onShowCustomView — иначе fullscreenEnabled=false
// и VK прячет кнопку). Камера и микрофон запрещены.
class VkWebChromeClient(private val activity: MainActivity) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        activity.showProgress(newProgress)
    }

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton("Да") { _, _ -> result.confirm() }
            .setNegativeButton("Отмена") { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onShowFileChooser(
        view: WebView,
        callback: android.webkit.ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean = activity.startFileChooser(callback, params)

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    // 1.3.23: полноэкранное видео. Без этих двух методов WebView сообщает
    // странице «полный экран недоступен», и плеер VK не показывает кнопку
    // разворачивания. Показ/скрытие delegated в MainActivity.
    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        activity.enterFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        activity.exitFullscreen()
    }
}
