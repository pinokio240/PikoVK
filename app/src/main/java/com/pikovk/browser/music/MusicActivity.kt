package com.pikovk.browser.music

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.pikovk.browser.R
import com.pikovk.browser.media.PlaybackService
import com.pikovk.browser.media.PlaybackWakeLock
import com.pikovk.browser.ui.PlaybackWebView
import com.pikovk.browser.util.Prefs
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)

        container = findViewById(R.id.musicContainer)
        buildPlayerWebView()

        // ktx-версия addCallback — как в MainActivity (нужен импорт
        // androidx.activity.addCallback, без него компилятор ждёт
        // OnBackPressedCallback и падает с Type mismatch)
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
    }

    // Сборка WebView вкладки. Единый метод для запуска и для пересборки
    // после смерти рендерера (MusicWebClient.onRenderProcessGone) — как
    // buildWebView в MainActivity.
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildPlayerWebView() {
        // старый WebView (если был) снимаем и уничтожаем до сборки нового
        val old = web
        if (old != null) {
            container.removeView(old)
            old.destroy()
        }
        val wv = PlaybackWebView(this)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        // приоритет рендерера — как в MainActivity.applySettings (1.3.14).
        // 1.3.23: второй аргумент false — рендереру вкладки НЕ разрешаем
        // «списывание» в невидимости: при сворачивании/блокировке Chromium
        // иначе может прибить рендерер — звук обрывается, вкладка сбрасывается
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
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
        // 1.3.23: медиашторка командует плеером музыки; карточка открывает вкладку
        PlaybackService.attachMusicTab(wv)
        wv.loadUrl("file:///android_asset/music.html")
    }

    override fun onStart() {
        super.onStart()
        // wake-lock здесь НЕ трогаем (в отличие от MainActivity): вкладка
        // «Музыка» всегда лежит ПОВЕРХ MainActivity — её onStop/onStart
        // корректно держат и отпускают блокировку за нас
    }

    override fun onStop() {
        super.onStop()
        // 1.3.23: страховка фона. Упреждающий запуск службы делает мост в
        // момент старта трека, но MainActivity к этому моменту может быть
        // уже уничтожена системой (см. MainActivity.onDestroy), и добор из
        // её onStop не сработает. Если играет, а службы нет — поднимаем
        // здесь: onStop вкладки наступает ровно при уходе с экрана, внутри
        // grace-окна перехода из видимого состояния. Wake-lock держим
        // независимо от службы — переход трек-к-треку в паузе звука.
        if (PlaybackService.isPlaying() && Prefs.backgroundPlayback) {
            val alive = PlaybackService.isRunning()
            if (!alive) {
                PlaybackService.start(this)
            }
            PlaybackWakeLock.acquire(this)
        }
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

        // 1.3.23: смерть процесса рендера без обработчика валит ВЕСЬ процесс
        // приложения (документированное поведение WebView) — у владельца это
        // выглядело как «плеер скинулся» при блокировке/сворачивании: под
        // давлением памяти систему первой убивает именно дочерний рендерер.
        // Возвращаем true и тихо пересобираем вкладку — как в браузере
        // (VkWebViewClient.onRenderProcessGone). Очередь плеера живёт в JS
        // страницы и не восстанавливается — зато приложение остаётся живым,
        // а шторка честно гаснет (звука больше нет).
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w("PikoVK-Music", "Процесс рендера вкладки «Музыка» пропал — пересобираю WebView")
            // флаг «играет» в службе теперь ложь — иначе карточка висит
            // с кнопками в никуда, а страховка onStop не пускает службу
            PlaybackService.updateMeta("", "", false)
            buildPlayerWebView()
            return true
        }

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
