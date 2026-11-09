package com.pikovk.browser.music

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.pikovk.browser.R
import com.pikovk.browser.media.PlaybackService
import com.pikovk.browser.ui.PlaybackWebView
import java.net.HttpURLConnection
import java.net.URL

// Отдельная вкладка-плеер «Музыка» (1.3.23) — по образцу VK Music Player
// (VK Next): музыка живёт НЕ на странице VK, а в собственном UI на
// assets/music.html. Данные и ссылки — MusicApi (web_token + audio.*),
// воспроизведение — <audio> внутри нашего WebView (HLS — через
// hls.js из assets), медиашторка — тот же PlaybackService.
//
// Почему звук не пропадает при блокировке экрана: страница наша, она не
// получает сигналов «скрыта» (PlaybackWebView подменяет видимость окна,
// как и в 1.3.18), служба запускается мостом в момент старта трека
// (упреждение 1.3.22), wake-lock держится, пока экран не на переднем плане.
//
// Клики CORS: hls.js качает сегменты XHR'ом с origin file:// — CDN VK
// (vkuser.net и родственные) не отдаёт Access-Control-Allow-Origin, поэтому
// shouldInterceptRequest добавляет ACAO:* ТОЛЬКО к ответам аудио-CDN
// (эквивалент DNR-правила 8 расширения). Всё остальное проходит насквозь.
class MusicActivity : AppCompatActivity() {

    private var web: WebView? = null
    private lateinit var container: android.widget.FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)

        container = findViewById(R.id.musicContainer)

        val wv = PlaybackWebView(this)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        // приоритет рендерера — как в MainActivity.applySettings (1.3.14)
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        wv.webViewClient = MusicWebClient()
        wv.addJavascriptInterface(
            MusicBridge(this) { web },
            "VkbMusic"
        )
        CookieManager.getInstance().setAcceptCookie(true)

        container.addView(
            wv,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        web = wv
        // 1.3.23: медиашторка командует плеером музыки
        PlaybackService.attach(wv)
        wv.loadUrl("file:///android_asset/music.html")

        // ktx-версия addCallback — как в MainActivity (нужен импорт
        // androidx.activity.addCallback, без него компилятор ждёт
        // OnBackPressedCallback и падает с Type mismatch)
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        // wake-lock здесь НЕ трогаем (в отличие от MainActivity): вкладка
        // «Музыка» всегда лежит ПОВЕРХ MainActivity — её onStop/onStart
        // корректно держат и отпускают блокировку за нас
    }

    override fun onStop() {
        super.onStop()
        // если звук играет — служба уже поднята мостом в момент старта
        // трека (упреждение 1.3.22), процесс держится foreground-службой
    }

    override fun onDestroy() {
        // вкладка закрыта — WebView уничтожается, звука больше нет:
        // гасим медиашторку, чтобы не висела с мёртвыми кнопками
        PlaybackService.attach(null)
        PlaybackService.stop(this)
        val wv = web
        if (wv != null) {
            container.removeView(wv)
            wv.destroy()
        }
        web = null
        super.onDestroy()
    }

    // См. класс: CORS только для аудио-CDN, остальное — насквозь (null).
    private inner class MusicWebClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (!isAudioCdn(url)) return null
            var conn: HttpURLConnection? = null
            try {
                val opened = URL(url).openConnection()
                if (opened !is HttpURLConnection) return null
                conn = opened
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    return null
                }
                val mime = conn.contentType
                val resp = WebResourceResponse(
                    if (mime == null) "application/octet-stream" else mime,
                    conn.contentEncoding,
                    conn.inputStream
                )
                val headers = HashMap<String, String>()
                headers["Access-Control-Allow-Origin"] = "*"
                headers["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS"
                resp.responseHeaders = headers
                return resp
            } catch (_: Exception) {
                val c = conn
                if (c != null) c.disconnect()
                return null
            }
        }
    }

    companion object {
        // Аудио-CDN VK (по правилу 8 расширения + известные хосты VK музыки).
        fun isAudioCdn(url: String): Boolean {
            val hosts = arrayOf(
                ".vkuser.net", ".vkuseraudio.ru", ".userapi.com",
                ".okcdn.ru", ".vk-cdn.net", ".mycdn.me"
            )
            for (i in hosts.indices) {
                if (url.contains(hosts[i])) return true
            }
            return false
        }

    }
}
