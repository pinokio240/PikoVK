package com.pikovk.browser.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pikovk.browser.R
import com.pikovk.browser.bridge.VkbBridge
import com.pikovk.browser.browser.LinkSniffer
import com.pikovk.browser.browser.UserAgents
import com.pikovk.browser.browser.VkWebChromeClient
import com.pikovk.browser.browser.VkLinks
import com.pikovk.browser.browser.VkThemeCookies
import com.pikovk.browser.browser.VkVersionCookies
import com.pikovk.browser.browser.VkWebViewClient
import com.pikovk.browser.ext.ExtensionEngine
import com.pikovk.browser.media.PlaybackService
import com.pikovk.browser.media.PlaybackWakeLock
import com.pikovk.browser.notify.NotifyService
import com.pikovk.browser.profile.ProfileManager
import com.pikovk.browser.util.Prefs

// Главный экран: WebView + минималистичная нижняя панель.
//
// Пересборка WebView (buildWebView) выполняется при смене профиля,
// изменении расширений/UA и переключении версии кнопкой (1.3.8) — при этом
// URL сохраняется. Загрузка стартует только после фактической записи
// версионных кук (CookieWriter) — раньше запрос уходил в сеть со старыми
// куками, и переключение «срабатывало» лишь после сворачивания приложения.
class MainActivity : AppCompatActivity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var urlBar: View
    private lateinit var urlInput: EditText
    private lateinit var btnDesktop: ImageButton

    private var webView: WebView? = null

    // 1.3.23: полноэкранное видео (onShowCustomView). Кастом-вью плеера
    // кладётся поверх всего содержимого окна; пока она показана — «назад»
    // сначала гасит полный экран, а пересборка WebView невозможна.
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // Поколение WebView (1.3.8): куки пишутся асинхронно, и если пока они
    //  записывались WebView успели пересобрать (быстрое переключение кнопок,
    //  профиль), колбэк устаревшей пачки не должен грузить страницу в
    //  уничтоженном экземпляре.
    private var webViewGen = 0

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val bridge by lazy { VkbBridge(this) }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback
            filePathCallback = null
            if (cb != null) {
                cb.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                )
            }
        }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && (Prefs.notifyEnabled || Prefs.imNotifyEnabled)) NotifyService.start(this)
        }

    // Домашняя страница всегда соответствует положению кнопки версии:
    //  хост и User-Agent согласованы (см. updateUserAgent) — VK не гоняет
    //  нас редиректами m.vk.ru <-> vk.ru. В полной версии (1.3.13) —
    //  паттерн владельца: vk.ru/feed с open_in_web=1 — он держит полную
    //  версию (штатный переключатель VK с _fm перестал держать).
    private val homeUrl: String
        get() = if (Prefs.desktopMode) "https://vk.ru/feed?open_in_web=1" else "https://m.vk.ru/feed"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webContainer = findViewById(R.id.webContainer)
        progressBar = findViewById(R.id.progress)
        urlBar = findViewById(R.id.urlBar)
        urlInput = findViewById(R.id.urlInput)
        btnDesktop = findViewById(R.id.btnDesktop)

        CookieManager.getInstance().setAcceptCookie(true)

        // «Всегда полная версия» синхронизирует кнопку при запуске. Дальше
        // источник истины — кнопка: если пользователь явно ушёл в мобильную
        // версию, её выбор уважается до следующего запуска (раньше клиент
        // перебрасывал обратно на vk.ru — переключение «не работало»).
        if (Prefs.autoDesktop) Prefs.desktopMode = true

        urlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadFromAddressBar()
                true
            } else {
                false
            }
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            val wv = webView
            if (wv != null) wv.goBack()
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            val wv = webView
            if (wv != null) wv.goForward()
        }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener {
            val wv = webView
            if (wv != null) wv.reload()
        }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { loadUrl(homeUrl) }
        // 1.3.23: отдельная вкладка-плеер «Музыка» (по образцу VK Music Player)
        findViewById<ImageButton>(R.id.btnMusic).setOnClickListener {
            startActivity(Intent(this, com.pikovk.browser.music.MusicActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnProfiles).setOnClickListener { showProfilesDialog() }
        findViewById<ImageButton>(R.id.btnProfiles).setOnLongClickListener { confirmLogoutCurrent(); true }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnDesktop.setOnClickListener { toggleDesktop() }

        onBackPressedDispatcher.addCallback(this) {
            // 1.3.23: в полноэкранном видео «назад» — выход из полного экрана
            val cv = customView
            if (cv != null) {
                exitFullscreen()
                return@addCallback
            }
            val wv = webView
            if (wv != null && wv.canGoBack()) wv.goBack() else finish()
        }

        btnDesktop.setImageResource(
            if (Prefs.desktopMode) R.drawable.ic_mobile else R.drawable.ic_desktop
        )
        urlBar.visibility = if (Prefs.uiMode == Prefs.UI_FULL) View.VISIBLE else View.GONE

        val savedUrl: String? = if (savedInstanceState == null) null else savedInstanceState.getString(KEY_URL)
        var startUrl: String = homeUrl
        if (savedUrl == null) {
            // 1.3.20: тап по уведомлению о личном сообщении несёт адрес,
            // который нужно открыть сразу (мессенджер).
            val openUrl = intent.getStringExtra(EXTRA_OPEN_URL)
            if (openUrl != null && openUrl.isNotEmpty()) startUrl = openUrl
        } else {
            startUrl = savedUrl
        }
        buildWebView(startUrl)

        if (Prefs.notifyEnabled || Prefs.imNotifyEnabled) {
            requestNotificationPermissionIfNeeded()
            NotifyService.start(this)
        }
        // 1.3.21: медиашторка — тоже уведомление; на Android 13+ спрашиваем
        // разрешение и для неё, даже если опрос уведомлений выключен
        if (Prefs.backgroundPlayback) {
            requestNotificationPermissionIfNeeded()
        }
    }

    // ------------------------------------------------------------------
    // Сборка WebView
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(url: String) {
        // 1.3.23: если было полноэкранное видео — гасим его до уничтожения
        // старого WebView (кастом-вью принадлежит его плееру).
        val cvOld = customView
        if (cvOld != null) exitFullscreen()
        val old: WebView? = webView
        if (old != null) {
            webContainer.removeView(old)
            old.destroy()
        }
        // 1.3.18: PlaybackWebView вместо голого WebView — пока включено
        // «Фоновое воспроизведение», страница не узнаёт, что она скрыта:
        // Chromium не троттлит её таймеры и не замораживает, и цепочка
        // автоперехода «трек за треком» (снапшот vk.ru/audio: 4-6 сетевых
        // вызовов по setTimeout в момент окончания трека) выполняется
        // в реальном времени. Подробности — ui/PlaybackWebView.kt.
        val wv = PlaybackWebView(this)
        applySettings(wv)

        // Расширения: один общий скрипт, внедряется до кода страницы.
        // На старых системных WebView (в логе эмулятора — Chrome 69) документ-start
        // скрипты недоступны и расширения молча не работали; теперь это видно:
        // фильтр logcat "PikoVK" покажет подсказку обновить Android System WebView.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                wv, ExtensionEngine.buildScript(), ExtensionEngine.ALLOWED_ORIGINS
            )
        } else {
            Log.w(
                "PikoVK",
                "Системный WebView слишком стар для расширений (нет addDocumentStartJavaScript) — обновите Android System WebView в Play Market"
            )
        }

        wv.webViewClient = VkWebViewClient(this)
        wv.webChromeClient = VkWebChromeClient(this)
        wv.setDownloadListener { url_, _, _, _, _ ->
            if (url_ != null) {
                // Снифер (1.3.5): скачивание — в журнал "PikoVK-Link".
                LinkSniffer.sniff(LinkSniffer.EVENT_DOWNLOAD, url_)
                bridge.download(url_)
            }
        }
        wv.addJavascriptInterface(bridge, "VkbBridge")
        // 1.3.21: медиашторка командует этим WebView.
        // 1.3.23: но НЕ отбираем ссылку, если играет вкладка «Музыка» —
        // пересоздание MainActivity поверх играющей вкладки (ROM, память)
        // не должно уводить команды шторки в браузер.
        if (!PlaybackService.fromMusicTab) {
            PlaybackService.attach(wv)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        webContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webView = wv

        // 1.3.8: загрузка стартует ТОЛЬКО после фактической записи кук в
        // хранилище WebView. CookieManager.setCookie асинхронный: прежний
        // немедленный loadUrl уходил в сеть со СТАРЫМ набором кук — VK
        // возвращал мобильную версию; после сворачивания/разворачивания
        // приложения куки успевали записаться, и «переход срабатывал».
        // Теперь порядок гарантирован для любой пересборки: старт,
        // переключение версии, профиль, настройки, падение рендера.
        // 1.3.3: согласование версионных cookie VK с положением кнопки
        // (remixforce_full, десктопный экран, стирание мобильных маркеров).
        // 1.3.7: родная тёмная тема VK через её собственные куки (пока
        // тумблер «Тёмная тема» включён; см. VkThemeCookies).
        webViewGen += 1
        val gen = webViewGen
        VkVersionCookies.apply(Prefs.desktopMode) {
            VkThemeCookies.apply {
                if (gen == webViewGen) {
                    wv.loadUrl(url)
                } else {
                    Log.i(
                        "PikoVK-Nav",
                        "загрузка отменена: WebView пересобрали, пока записывались куки"
                    )
                }
            }
        }
    }

    private fun applySettings(wv: WebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        // 1.3.9: автозапуск медиа разрешён. Причина изменения: эта настройка
        // требует пользовательский жест перед запуском КАЖДОГО нового
        // <audio>-элемента. Плеер VK запускает следующий трек программно из
        // JavaScript (трек закончился — JS стартует следующий), тапа в этот
        // момент нет: WebView блокировал play(), и музыка обрывалась между
        // треками, пока не тапнёшь play ещё раз. Первый трек всегда работал —
        // его запускал клик владельца. Цена (осознанная): страница
        // теоретически может сама запустить видео со звуком без клика —
        // в веб-версии VK таких сценариев практически нет; если помешает,
        // вернём жест отдельным тумблером в настройках.
        s.mediaPlaybackRequiresUserGesture = false
        // 1.3.14: приоритет процесса рендерера. По умолчанию действует
        // политика RENDERER_PRIORITY_BOUND: когда активность уходит с экрана,
        // Chromium вправе приглушать и убивать рендерер — текущий трек доигрывает
        // (аудиотракт отдельный), но в момент его окончания JS-обработчик
        // «ended» выполняется в уже усиленном/убитом рендерере, и следующий
        // трек не стартует — «прерывается при переходе от трека к треку».
        // RENDERER_PRIORITY_IMPORTANT держит рендерер полноценным и в фоне;
        // renderedPriorityRaised=true поднимает приоритет, когда WebView виден.
        // minSdk 26 — метод доступен без проверок версии.
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        updateUserAgent(wv)
    }

    // UA всегда согласован с версией сайта (кнопка = Prefs.desktopMode).
    //  Раньше при включённой «Всегда полной версии» страница открывалась на
    //  vk.ru с МОБИЛЬНЫМ UA — VK по UA возвращал на m.vk.ru, получалась
    //  «борьба» редиректов и мигание. Теперь UA и хост переключаются вместе.
    private fun updateUserAgent(wv: WebView) {
        wv.settings.userAgentString =
            if (Prefs.desktopMode) UserAgents.DESKTOP_UA else Prefs.effectiveMobileUa()
    }

    // ------------------------------------------------------------------
    // Переключение мобильной/полной версии (плейлисты в полной версии)
    // ------------------------------------------------------------------

    // Кнопка «полная/мобильная»: меняем desktopMode, затем пересобираем
    //  WebView — хост, UA и версионные куки всегда согласованы (1.3.8).
    //  m.vk.ru и vk.ru — разные документы, поэтому страница перезагружается
    //  (позиция скролла не сохраняется — ограничение самого VK).
    //  Если текущая страница — не VK, просто перезагружаем её с новым UA.
    //  Явно: без `?:` — WebView и его URL проверяются через if.
    //
    //  1.3.11: попытка уводить страницу на штатный переключатель VK.
    //  1.3.13: переключаем по ПАТТЕРНУ ВЛАДЕЛЬЦА — полный хост vk.ru /
    //  vk.com + open_in_web=1 (лог 08.11.2026 16:22: штатный переключатель
    //  с _fm даже при записанных десктопных куках отдал мобильные ассеты,
    //  а «vk.ru/feed?open_in_web=1» полную версию держит). Для не-VK
    //  страниц — прежний перенос хоста. В «мобильную» — как раньше:
    //  замена хоста на m.vk.*, VK этот переход никогда не оспаривает.
    private fun toggleDesktop() {
        Prefs.desktopMode = !Prefs.desktopMode
        btnDesktop.setImageResource(
            if (Prefs.desktopMode) R.drawable.ic_mobile else R.drawable.ic_desktop
        )
        val wv: WebView? = webView
        val currentUrl: String?
        if (wv == null) {
            currentUrl = null
        } else {
            currentUrl = wv.url
        }
        val cur: String
        if (currentUrl == null) {
            cur = homeUrl
        } else {
            cur = currentUrl
        }
        val next: String = if (Prefs.desktopMode) {
            // 1.3.13: полный хост + open_in_web=1 (паттерн владельца); для
            // не-VK страниц (переключение неприменимо) — прежний перенос хоста.
            val viaVk: String = VkLinks.toDesktopSwitchUrl(cur)
            if (viaVk.isNotBlank()) {
                viaVk
            } else {
                val direct: String = VkLinks.toDesktopUrl(cur)
                if (direct.isBlank()) homeUrl else direct
            }
        } else {
            val mob: String = VkLinks.toMobileUrl(cur)
            if (mob.isBlank()) homeUrl else mob
        }
        val target: String = next
        // 1.3.8: переключение = ПЕРЕСБОРКА WebView, а не loadUrl в живом.
        // Владелец подметил точный симптом: «свернул и развернул — переход
        // срабатывает». Сворачивание убивает процесс: при разворачивании
        // WebView создаётся заново, а куки к тому моменту уже записаны.
        // Прежний toggle менял UA у живого WebView и сразу запускал
        // loadUrl: запрос уходил в сеть раньше асинхронной записи
        // версионных кук (CookieManager.setCookie), VK по старым кукам
        // возвращал мобильную версию. Теперь кнопка делает ровно то же,
        // что «свернул/развернул», но сразу: новый WebView (UA задаётся
        // до первого запроса), куки записываются ДО старта загрузки
        // (см. buildWebView и CookieWriter).
        buildWebView(target)
    }

    // ------------------------------------------------------------------
    // Навигация
    // ------------------------------------------------------------------

    private fun loadUrl(url: String) {
        urlInput.setText(url)
        val wv = webView
        if (wv != null) wv.loadUrl(url)
    }

    private fun loadFromAddressBar() {
        val q = urlInput.text.toString().trim()
        if (q.isEmpty()) return
        val url = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            q.contains('.') && !q.contains(' ') -> "https://$q"
            else -> "https://vk.ru/search?c%5Bsection%5D=people&c%5Bq%5D=${Uri.encode(q)}"
        }
        // Без проверок: открываем любой адрес, который ввёл пользователь
        val wv = webView
        if (wv != null) wv.loadUrl(url)
    }

    // ------------------------------------------------------------------
    // Профили
    // ------------------------------------------------------------------

    private fun showProfilesDialog() {
        val labels = (0 until ProfileManager.MAX_PROFILES).map { sl ->
            val state = when {
                sl == ProfileManager.activeSlot() -> " — активен"
                ProfileManager.slotHasSession(sl) -> " — есть сессия"
                else -> " — пусто"
            }
            ProfileManager.slotLabel(sl) + state
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.profiles)
            .setSingleChoiceItems(labels, ProfileManager.activeSlot()) { d, which ->
                d.dismiss()
                if (which != ProfileManager.activeSlot()) {
                    ProfileManager.switchTo(which)
                    buildWebView(homeUrl)
                }
            }
            .setNeutralButton("Закрыть", null)
            .show()
    }

    private fun confirmLogoutCurrent(): Boolean {
        val slot = ProfileManager.activeSlot()
        AlertDialog.Builder(this)
            .setMessage("Выйти из аккаунта в «${ProfileManager.slotLabel(slot)}»?")
            .setPositiveButton("Выйти") { _, _ ->
                ProfileManager.logout(slot)
                buildWebView(homeUrl)
            }
            .setNegativeButton("Отмена", null)
            .show()
        return true
    }

    // ------------------------------------------------------------------
    // Колбэки из клиентов WebView
    // ------------------------------------------------------------------

    fun onPageAddress(url: String) {
        urlInput.setText(url)
    }

    fun showProgress(p: Int) {
        progressBar.progress = p
        progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
    }

    fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    fun openExternal(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // Открыть нестандартную схему (intent:, market:…) через систему.
    fun openRaw(url: String) {
        runCatching {
            startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME))
        }
    }

    fun onRenderGone() {
        val wv: WebView? = webView
        val currentUrl: String?
        if (wv == null) {
            currentUrl = null
        } else {
            currentUrl = wv.url
        }
        // Диагностика (1.3.2): если процесс рендера падает циклически, каждый
        // перезапуск виден в logcat по фильтру "PikoVK-Nav".
        Log.w("PikoVK-Nav", "Процесс рендера WebView пропал — пересобираю WebView на $currentUrl")
        if (currentUrl == null) {
            buildWebView(homeUrl)
        } else {
            buildWebView(currentUrl)
        }
    }

    // 1.3.3: VK упорно возвращает мобильную версию (исчерпаны отскоки в
    // VkWebViewClient.handleDesktopForce). Честно возвращаем кнопку в
    // «мобильная», чтобы состояние интерфейса совпадало с реальностью —
    // раньше кнопка оставалась в «полной», и переключение выглядело
    // неработающим.
    fun onDesktopGiveUp(mobileUrl: String) {
        if (!Prefs.desktopMode) return
        Prefs.desktopMode = false
        btnDesktop.setImageResource(R.drawable.ic_desktop)
        val wv: WebView? = webView
        if (wv != null) updateUserAgent(wv)
        Log.w(
            "PikoVK-Nav",
            "кнопка возвращена в «мобильная» — VK не пустил в полную версию: $mobileUrl"
        )
        Toast.makeText(this, R.string.desktop_give_up, Toast.LENGTH_SHORT).show()
    }

    fun startFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        val pending = filePathCallback
        if (pending != null) {
            pending.onReceiveValue(null) // предыдущий выбор отменяем
        }
        filePathCallback = callback
        return try {
            fileChooser.launch(params.createIntent())
            true
        } catch (_: Exception) {
            filePathCallback = null
            false
        }
    }

    // ------------------------------------------------------------------
    // 1.3.23: полноэкранное видео (кнопка «развернуть» в плеере VK)
    // ------------------------------------------------------------------

    // Вызывается из VkWebChromeClient.onShowCustomView. Повторный показ
    // (страница не дождалась onHide) — просто подтверждаем скрытие.
    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        val existing = customView
        if (existing != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        // Прячем свой интерфейс: экран полностью отдан плееру
        urlBar.visibility = View.GONE
        progressBar.visibility = View.GONE
        webContainer.visibility = View.GONE
        view.keepScreenOn = true
        val content = findViewById<ViewGroup>(android.R.id.content)
        content.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        applyImmersive(true)
    }

    // Вызывается из VkWebChromeClient.onHideCustomView, кнопки «назад» и
    // перед любой пересборкой WebView (toggleDesktop, профиль, рендер).
    fun exitFullscreen() {
        val view = customView
        if (view == null) return
        customView = null
        val cb = customViewCallback
        customViewCallback = null
        view.keepScreenOn = false
        val content = findViewById<ViewGroup>(android.R.id.content)
        content.removeView(view)
        webContainer.visibility = View.VISIBLE
        urlBar.visibility = if (Prefs.uiMode == Prefs.UI_FULL) View.VISIBLE else View.GONE
        applyImmersive(false)
        if (cb != null) cb.onCustomViewHidden()
    }

    // Полный экран = системные панели спрятаны (возврат — свайпом).
    private fun applyImmersive(on: Boolean) {
        val win = window
        WindowCompat.setDecorFitsSystemWindows(win, !on)
        val controller = WindowInsetsControllerCompat(win, win.decorView)
        if (on) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        // 1.3.14: приложение снова на экране — wake-lock прослушивания не нужен
        // (аудиотракт и сам будит процессор, пока играет звук)
        PlaybackWakeLock.release()
        // 1.3.10: приложение снова на экране — служба воспроизведения не нужна.
        // 1.3.23: НО глушить её можно только когда ничего не играет. Вкладка
        // «Музыка» лежит в задаче ПОД нами: тап по медиа-карточке (шторка,
        // локскрин) или пересоздание активити ROM-ом поднимает MainActivity
        // НАД играющей вкладкой — раньше onStart безусловно убивал службу,
        // процесс падал в кэш и замораживался (Android 12+), музыка
        // обрывалась, вкладка сбрасывалась. Если играет страница VK — служба
        // останется и на экране: это дешевле, чем терять звук; погаснет на
        // ближайшем onStart, когда воспроизведение остановится.
        // nativeActive — нативный трек службы (даже на паузе): его тоже
        // не глушим, карточка умеет продолжить.
        if (!PlaybackService.isPlaying() && !PlaybackService.nativeActive) {
            PlaybackService.stop(this)
        }
        // 1.3.23: возвращаем шторке ссылку на браузерный WebView — если только
        // что закрыли вкладку «Музыка», команды шторки снова идут в страницу VK.
        // Отбираем ссылку ТОЛЬКО когда источником звука не играет вкладка
        // «Музыка»: иначе пересоздание MainActivity поверх играющей вкладки
        // уводит команды шторки в браузер и сбрасывает флаг источника.
        if (!PlaybackService.fromMusicTab) {
            val wvNow = webView
            if (wvNow != null) {
                PlaybackService.attach(wvNow)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 1.3.10: приложение ушло с экрана (свернули, погас экран, открыт
        // другой экран). WebView играет звук, только пока процесс жив и не
        // заморожен: кэшированный процесс Android 12+ замораживается, а
        // «фантомный» менеджер убивает рендерер WebView — звук обрывался.
        // Держим процесс foreground-службой mediaPlayback. Если уже работает
        // опрос уведомлений, его foreground достаточно — не дублируем.
        // 1.3.22: служба теперь обычно УЖЕ живёт — она запускается упреждающе
        // в момент старта трека (VkbBridge.playerState, приложение ещё на
        // экране; требование Android 17 «Background audio hardening»: FGS
        // mediaPlayback стартовать видимым). Здесь — только добор, если трек
        // пошёл без репорта или упреждение выключено. Повторный
        // startForegroundService для живой службы безвреден (onStartCommand).
        if (Prefs.backgroundPlayback && !NotifyService.isRunning) {
            PlaybackService.start(this)
        }
        // 1.3.14: wake-lock на время фоновой прослушивания. Служба (выше) держит
        // ПРОЦЕСС, но не процессор: в паузе между треками звук молчит, своего
        // пробуждения у аудиотракта нет — процессор засыпал посреди перехода,
        // и следующий трек не стартовал. Держим независимо от того, какая
        // служба живёт (PlaybackService или NotifyService): см. PlaybackWakeLock.
        if (Prefs.backgroundPlayback) {
            PlaybackWakeLock.acquire(this)
        }
    }

    override fun onResume() {
        super.onResume()
        urlBar.visibility = if (Prefs.uiMode == Prefs.UI_FULL) View.VISIBLE else View.GONE
        if (Prefs.consumeDirty()) {
            val wv: WebView? = webView
            val currentUrl: String?
            if (wv == null) {
                currentUrl = null
            } else {
                currentUrl = wv.url
            }
            if (currentUrl == null) {
                buildWebView(homeUrl)
            } else {
                buildWebView(currentUrl)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // держим снимок активного слота актуальным для мгновенного переключения
        ProfileManager.saveCurrentSession(ProfileManager.activeSlot())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val wv: WebView? = webView
        val currentUrl: String? = if (wv == null) null else wv.url
        outState.putString(KEY_URL, currentUrl) // null до первой загрузки — так и задумано
    }

    override fun onDestroy() {
        // 1.3.23: если окно умирает в полноэкранном видео — чистим ссылку
        // (кастом-вью уйдёт вместе с окном, колбэк уже не нужен)
        customView = null
        customViewCallback = null
        // 1.3.23: вкладка «Музыка» лежит в задаче ПОД нами, и систему ничего
        // не обязывает хранить остановленную активити: при блокировке экрана
        // или сворачивании прошивка/память УНИЧТОЖАЮТ MainActivity, ПОКА
        // музыка играет. Раньше onDestroy безусловно гасил службу: процесс
        // падал в кэш, замораживался (Android 12+), фантомный киллер добивал
        // рендерер — звук обрывался, вкладка сбрасывалась. Теперь глушим
        // только если звук — НЕ вкладка «Музыка» и НЕ нативный движок
        // службы; свою смерть вкладка отработает сама в своём onDestroy.
        val musicOwnsSound = PlaybackService.nativeActive ||
            (PlaybackService.fromMusicTab && PlaybackService.isPlaying())
        if (!musicOwnsSound) {
            // 1.3.14: wake-lock больше не нужен — WebView (и звук) уничтожается
            PlaybackWakeLock.release()
            // 1.3.21: ссылки на WebView больше нет — команды плееру некуда слать
            PlaybackService.attach(null)
            // 1.3.10: приложение закрывается — WebView будет уничтожен, звука
            // больше нет; убираем уведомление службы воспроизведения
            PlaybackService.stop(this)
        }
        val wv = webView
        if (wv != null) {
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val KEY_URL = "vkb_url"
        // 1.3.20: экстра для тапа по уведомлению — открыть конкретный адрес
        // (мессенджер) сразу после запуска/пересоздания активности.
        const val EXTRA_OPEN_URL = "vkb_open_url"
    }
}
