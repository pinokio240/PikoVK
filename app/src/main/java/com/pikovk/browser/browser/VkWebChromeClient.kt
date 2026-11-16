package com.pikovk.browser.browser

import android.app.AlertDialog
import android.net.Uri
import android.view.View
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import com.pikovk.browser.ui.MainActivity
import com.pikovk.browser.util.DiagLog

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

    // 1.3.30 (доработка-20): консоль страницы в logcat — диагностика
    // прикрепления файлов. Ошибки JS VK и загрузки на upload-сервер раньше
    // шли в общий «chromium»-поток и терялись среди шума; теперь они видны
    // по фильтру «PikoVK-Console», рядом с метками [PikoVK] из DIAG_JS
    // (ExtensionEngine): клик по file-input и число файлов, полученных
    // страницей. Возврат true — сообщение обработано, поведение страницы
    // не меняется.
    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
        android.util.Log.i(
            "PikoVK-Console",
            "${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}"
        )
        // 1.3.31 (доработка-21): метки [PikoVK] из DIAG_JS (ExtensionEngine) —
        // звенья «страница открыла выбор файла» и «страница получила файлы» —
        // пишем в журнал настроек и показываем тостом: владелец видит, где
        // рвётся цепочка прикрепления, без adb. Остальная консоль VK — только
        // logcat (шум страницы в журнал не попадает).
        if (message.message().contains("[PikoVK]")) {
            val diag = message.message().substringAfter("[PikoVK] ").trim()
            DiagLog.log(activity, "страница: $diag")
            Toast.makeText(activity, "PikoVK: $diag", Toast.LENGTH_SHORT).show()
        }
        return true
    }

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

    // 1.3.23: окна VK («Поделиться» у видео открывает share.php через
    // window.open, часть диалогов — target=_blank). Раньше onCreateWindow
    // не было и setSupportMultipleWindows не включался — такие окна молча
    // игнорировались (репорт владельца: «не могу поделиться видео»).
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message
    ): Boolean = activity.openPopupWindow(resultMsg)

    // окно закрыло само себя (window.close)
    override fun onCloseWindow(window: WebView) {
        activity.closePopup()
    }
}
