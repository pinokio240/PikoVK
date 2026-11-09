package com.pikovk.browser.browser

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.pikovk.browser.ext.ExtensionEngine
import com.pikovk.browser.ui.MainActivity
import com.pikovk.browser.util.Prefs
import com.pikovk.browser.browser.VkVersionCookies
import java.io.ByteArrayInputStream

// Клиент WebView PikoVK.
//
// Никаких принудительных блокировок: страница сама решает, какие ресурсы
// загружать — VK Mini Apps, внешние картинки, видео и аудио работают без
// ограничений.
//
// Единственное исключение — расширение «Блокировка рекламы» (с 1.3.16
// включено по умолчанию, выключается тумблером). Заимствовано у PinoK (GPL-3.0):
//  1) блоклист известных рекламных/счётчиковых доменов (не белый список!)
//     с честным HTTP 451 — как в их AdBlockInterceptor;
//  2) разворот трекинг-редиректа away.php?to=... напрямую в цель
//     (как в их AwayBypassInterceptor). Fail-open: если разворот не удался —
//     страница грузится как обычно, через штатный редирект VK.
//
// Ещё одна функция — согласование версии сайта. Источник истины — кнопка
// на нижней панели (Prefs.desktopMode): при положении «полная» страницы
// m.vk.ru автоматически переносятся на vk.ru (там работают плейлисты),
// причём User-Agent и хост всегда переключаются вместе. Выбор кнопкой
// мобильной версии уважается даже при включённой настройке «Всегда полная
// версия» — она действует только при запуске (см. MainActivity.onCreate).
//
// Защита от «бесконечного обновления страницы» (1.3.2): если VK в ответ на
// перенесённый vk.* раз за разом отдаёт редирект обратно на m.vk.* (так
// бывает из-за cookie «мобильной версии», оставшейся в профиле после
// переключений версий, или из цепочек oauth), перенос зацикливался — страница
// бесконечно мигала и перезагружалась. Теперь серия быстрых отскоков на ТОТ
// ЖЕ адрес ограничена (MAX_FORCE_BOUNCES), после чего браузер разрешает
// мобильную версию и цикл обрывается.
//
// 1.3.3: причину отскоков устранили — MainActivity перед загрузкой согласует
// «версионные» cookie VK с положением кнопки (VkVersionCookies: десктопные
// remixscreen_* + remixforce_full, стирание мобильного маркера remixmdevice).
// Если VK всё же упорствует, кнопка честно возвращается в «мобильная»
// (MainActivity.onDesktopGiveUp) — состояние UI больше не расходится с
// реальностью («переключение не срабатывает»). Диагностика — фильтр logcat
// "PikoVK-Nav": там виден каждый старт загрузки, каждый перенос версии и
// текущие версионные куки.
//
// 1.3.7 — причина «мобильный преобладает» найдена в логе владельца: мы сами
// добавляли к целям vk.* параметры официального переключателя (_fm=1&_fm2=1),
// но на хосте vk.* они означают «идёт переход из мобильной версии», и VK
// серверно (302) возвращал страницу на m.vk.* с теми же параметрами; наш
// повторный перенос замыкал цикл, защита сдавалась за ~100 мс и кнопка
// откатывалась в «мобильная». Теперь: (1) штатную ссылку переключателя
// (m.vk.* с _fm) пропускаем как есть — VK сам ставит свои куки и переносит
// на чистый vk.*; (2) собственные переносы m.vk.* -> vk.* идут БЕЗ _fm;
// (3) ошибки загрузки главного фрейма (включая TLS, как net_error -202 из
// того же лога) видны в PikoVK-Nav с адресом.
//
// 1.3.8 — устранена гонка записи кук (симптом владельца: «свернул и
// развернул приложение — переход срабатывает»). CookieManager.setCookie
// асинхронный: загрузка после переключения кнопкой уходила в сеть со
// СТАРЫМ набором кук, VK возвращал мобильную версию; после сворачивания
// приложения процесс пересоздавался, куки к тому моменту были записаны —
// и «всё работало». Теперь загрузка стартует только после фактической
// записи кук (CookieWriter), а кнопка версии пересобирает WebView целиком
// (MainActivity.toggleDesktop) — ровно то, что раньше делало сворачивание.
//
// 1.3.11 — переключение переведено на ШТАТНЫЙ механизм VK. Владелец: даже
// после 1.3.8 VK трижды возвращал страницу на m.vk.ru/feed (кука
// remixforce_full=2 была записана и видна в CookieManager), но переключение
// ВНУТРИ страницы VK работало. Проверка ответов сервера (08.11.2026):
// официальный переключатель ставит ПАРУ кук (remixforce_full=2 +
// remixshow_fvbar=1) — одной первой VK уже недостаточно. Поэтому и кнопка
// версии, и «лечение» отскоков теперь ведут страницу на официальный адрес
// переключателя m.vk.…/?_fm=1&_fm2=1 (см. VkLinks.toDesktopSwitchUrl):
// VK сам выставляет свои куки серверно и возвращает страницу на чистый
// vk.* — ровно то же самое происходит, когда владелец переключает версию
// «в настройках на странице». Защита от цикла сохранена: если VK и после
// официального переключателя возвращает мобильную, счётчик отскоков
// растёт и после MAX_FORCE_BOUNCES кнопка честно откатывается
// (MainActivity.onDesktopGiveUp). Наш собственный заход на адрес _fm
// счётчик НЕ сбрасывает (иначе цикл стал бы бесконечным), а клик по
// настоящей ссылке «Версия для компьютера» внутри страницы — сбрасывает,
// как и в 1.3.7.
//
// 1.3.13 — переключение ведётся по ПАТТЕРНУ ВЛАДЕЛЬЦА: полный хост
// vk.ru / vk.com с параметром open_in_web=1 (например,
// https://vk.ru/feed?open_in_web=1). Лог владельца 08.11.2026 16:22:
// даже штатный переключатель VK (адрес с _fm при записанной паре
// десктопных кук и десктопном UA) обслужен МОБИЛЬНЫМИ ассетами
// (st.vk.ru/dist/mobile/), а «полный хост + open_in_web=1» полную версию
// держит. Поэтому: (1) VkLinks.toDesktopSwitchUrl строит полный хост
// с open_in_web=1 и выбрасывает служебные _fm/_fm2; (2) отдельный пропуск
// _fm-адресов отменён — клик по ссылке «Версия для компьютера» внутри
// страницы теперь ведёт туда же, по общему правилу; (3) флаг
// selfSwitchActive больше не нужен: цель переноса — полный хост, на нём
// handleDesktopForce не срабатывает. Защита от пинг-понга
// (MAX_FORCE_BOUNCES, честная сдача onDesktopGiveUp) — без изменений.
//
// 1.3.16 — «Блокировка рекламы» добита по лид-формам (второй пример
// владельца: m.vk.ru/app51478948#?type=leadform&d=…): переход на рекламный
// редиректор (rv.mail.ru / adfox.ru, в т.ч. завёрнутый в away.php) и на
// страницу лид-формы (путь /app… + фрагмент type=leadform) отменяется до
// загрузки с коротким уведомлением, а сетевой запрос к ОБЩЕМУ рендереру
// лид-форм app51478948 получает HTTP 451 (фрагмент до сети не доходит —
// режем по пути, см. ExtensionEngine.isLeadAppRequest/isAdNavigation).
// Расширение с 1.3.16 включено по умолчанию (прямое решение владельца).
//
// 1.3.17 — третий пример владельца (rv.mail.ru с новым banner_id):
// «ну и почему я снова вижу рекламу?». Разбор слабых мест и усиление —
// в ExtensionEngine и README (штатные флаги VK noAds, маркеры карточек,
// рекламный CDN mradx.net, рекламные эндпоинты VK в isAdTracker,
// расширены навигационные блоки isAdNavigation).
class VkWebViewClient(private val activity: MainActivity) : WebViewClient() {

    // Адрес vk.*, на который мы последними перенесли страницу.
    private var lastForcedDesktopUrl: String? = null

    // Момент последнего переноса (SystemClock.elapsedRealtime).
    private var lastForcedAtMs = 0L

    // Сколько раз подряд VK вернул нас на тот же m.vk.* (отскоки).
    private var forceBounces = 0

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        // Снифер (1.3.5): каждый запрос главного фрейма — в журнал
        // "PikoVK-Link". Промежуточные 302-редиректы видны покадрово.
        if (request.isForMainFrame) {
            LinkSniffer.sniff(LinkSniffer.EVENT_REQUEST, request.url.toString())
        }
        // Уровень 2 «Блокировки рекламы» (включена по умолчанию): режем
        // известные счётчики и — с 1.3.17 — рекламные эндпоинты VK.
        // Диагностика — фильтр logcat "PikoVK-AdBlock".
        if (Prefs.extAdBlock && ExtensionEngine.isAdTracker(request.url.toString())) {
            Log.d("PikoVK-AdBlock", "tracker blocked: ${request.url}")
            return emptyResponse()
        }
        // 1.3.16: сетевой блок ОБЩЕГО рендерера лид-форм (апп 51478948).
        // iframe рекламной карточки грузит документ m.vk.ru/app51478948,
        // при этом фрагмент type=leadform в сетевой запрос не попадает —
        // режем по пути. Диагностика — тот же фильтр "PikoVK-AdBlock".
        if (Prefs.extAdBlock && ExtensionEngine.isLeadAppRequest(request.url.toString())) {
            Log.d("PikoVK-AdBlock", "lead-app blocked: ${request.url}")
            return emptyResponse()
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        // Снифер (1.3.5): переход по ссылке — в журнал "PikoVK-Link"
        // (клики логируются всегда, дедупликация их не глушит).
        LinkSniffer.sniff(LinkSniffer.EVENT_CLICK, url.toString())
        return when (url.scheme) {
            "http", "https" -> {
                val s = url.toString()
                // 1.3.16: рекламный переход (rv.mail.ru / adfox.ru или
                // страница лид-формы type=leadform, в т.ч. завёрнутая в
                // away.php) отменяется до загрузки — с коротким
                // уведомлением. Фрагмент #?… входит в URI навигации,
                // поэтому здесь признак лид-формы виден целиком (сетевому
                // перехвату он недоступен). Проверка идёт ПЕРЕД разворотом
                // away, иначе рекламная цель ушла бы в загрузку напрямую.
                if (Prefs.extAdBlock && ExtensionEngine.isAdNavigation(s)) {
                    Log.i("PikoVK-AdBlock", "переход на рекламу отменён: $s")
                    notifyAdBlocked()
                    return true
                }
                val awayTarget = if (Prefs.extAdBlock) unwrapAwayRedirect(url) else null
                when {
                    // PinoK AwayBypass: трекинг-редирект развёрнут — грузим цель напрямую
                    awayTarget != null -> {
                        Log.d("PikoVK-AdBlock", "away bypass: $s -> $awayTarget")
                        view.loadUrl(awayTarget)
                        true
                    }
                    Prefs.desktopMode && VkLinks.isMobileHost(s) -> handleDesktopForce(view, s)
                    else -> false // загружаем как обычно, ничего не запрещаем
                }
            }
            "tel", "sms", "mailto" -> {
                activity.openExternal(url.toString())
                true
            }
            else -> {
                // intent:, market: и прочее — отдаём системе: если есть
                // приложение-обработчик, оно откроется; нет — просто ничего
                // не произойдёт
                activity.openRaw(url.toString())
                true
            }
        }
    }

    // Кнопка в положении «полная версия»: страница сама пришла/ушла на
    // m.vk.* — переводим её на полный хост по паттерну владельца
    // (1.3.13: vk.ru / vk.com + open_in_web=1, см. шапку класса).
    // Хост и десктопный UA согласованы. Если кнопка в «мобильной» —
    // ничего не переносим (правило autoDesktop больше не перебивает
    // явный выбор кнопки).
    //
    // Сюда попадает и ссылка «Версия для компьютера» VK (адрес с _fm):
    // штатный переключатель больше не держит (лог 08.11.2026 16:22),
    // поэтому его адрес конвертируется в тот же паттерн.
    //
    // Защита от цикла («бесконечное обновление страницы», 1.3.2): если после
    // нашего переключения VK тут же снова отдаёт m.vk.*, прежний код
    // переключал снова и снова — бесконечный пинг-понг. Отскоком считается
    // повторный перенос ТОГО ЖЕ адреса в пределах BOUNCE_WINDOW_MS; после
    // MAX_FORCE_BOUNCES отскоков подряд перенос отменяется — мобильная
    // страница грузится как есть, цикл обрывается, а кнопка честно
    // переводится в «мобильная» (1.3.3, MainActivity.onDesktopGiveUp).
    // Навигация по другим адресам и клики с паузами сбрасывают счётчик:
    // обычную работу защита не задевает.
    //
    // true — сами запускаем загрузку (view.loadUrl), false — отдаём
    // навигацию WebView как есть.
    private fun handleDesktopForce(view: WebView, mobileUrl: String): Boolean {
        // 1.3.13: полный хост + open_in_web=1 (паттерн владельца).
        // Версионные куки и десктопный UA согласованы заранее
        // (VkVersionCookies + updateUserAgent).
        val target: String = VkLinks.toDesktopSwitchUrl(mobileUrl)
        if (target.isBlank()) {
            // Не-VK адрес (fail-open): переносить нечего — грузим как есть.
            return false
        }
        val now: Long = SystemClock.elapsedRealtime()
        val prev: String? = lastForcedDesktopUrl
        val isBounce: Boolean = prev != null && prev == target &&
            (now - lastForcedAtMs) <= BOUNCE_WINDOW_MS
        if (isBounce) {
            forceBounces += 1
        } else {
            forceBounces = 1
        }
        lastForcedDesktopUrl = target
        lastForcedAtMs = now
        if (forceBounces == 1) {
            Log.i("PikoVK-Nav", "версионные куки VK: ${VkVersionCookies.diagnose()}")
        }
        if (forceBounces > MAX_FORCE_BOUNCES) {
            Log.w(
                "PikoVK-Nav",
                "Цикл редиректов vk.<->m.vk.: VK $forceBounces раз(а) подряд вернул " +
                    "мобильную версию для $mobileUrl — перестаю переносить, страница " +
                    "остаётся мобильной (кнопку можно переключить снова)"
            )
            forceBounces = 0
            lastForcedDesktopUrl = null
            // 1.3.3: честный откат — кнопка и UA возвращаются в «мобильная»,
            // чтобы положение кнопки совпадало с реально открытой версией.
            activity.onDesktopGiveUp(mobileUrl)
            return false
        }
        Log.i(
            "PikoVK-Nav",
            "кнопка=полная: $mobileUrl -> полная версия по паттерну владельца: $target " +
                "(отскок $forceBounces/$MAX_FORCE_BOUNCES)"
        )
        view.loadUrl(target)
        return true
    }

    // Заимствовано у PinoK (NetworkInterceptors.AwayBypassInterceptor, GPL-3.0).
    // away.php — трекинг-редирект VK: адрес цели передаётся параметром to=
    // (URL-encoded). Разворачиваем его, пропуская лишний хоп через счётчик VK.
    // Fail-open: не away.php, нет параметра, не http(s) — возвращаем null,
    // страница грузится штатно.
    private fun unwrapAwayRedirect(url: Uri): String? {
        return try {
            val rawHost: String? = url.host
            if (rawHost == null) return null
            val host = rawHost.lowercase()
            val isVk = host == "vk.ru" || host.endsWith(".vk.ru") ||
                host == "vk.com" || host.endsWith(".vk.com")
            if (!isVk) return null
            val path: String? = url.path
            if (path == null) return null
            if (!path.endsWith("/away.php")) return null
            val target: String? = url.getQueryParameter("to")
            if (target == null) return null
            if (target.startsWith("https://") || target.startsWith("http://")) target else null
        } catch (_: Exception) {
            null
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // Диагностика навигации (1.3.2): каждый старт загрузки виден в logcat
        // по фильтру "PikoVK-Nav" — по логу видно, кто кого перенаправляет.
        Log.i("PikoVK-Nav", "onPageStarted: $url")
        activity.onPageAddress(url)
        // 1.3.6: серверные редиректы (302) НЕ проходят через
        // shouldOverrideUrlLoading — раньше VK мог вернуть нас на m.vk.*
        // уже после переключения кнопкой (или пользователь сам открыл
        // m.vk.* по ссылке/из адресной строки), и страница оставалась
        // мобильной с десктопным UA при кнопке «полная» — «мобильный
        // преобладает». HAR-дамп (08.11.2026) подтверждает: m.vk.ru отдаёт
        // мобильную версию даже с десктопным UA — решает хост. Теперь
        // посадку на m.vk.* при кнопке «полная» переносим так же, как
        // клики, с общей защитой от пинг-понга и честной сдачей.
        if (Prefs.desktopMode && VkLinks.isMobileHost(url)) {
            val forced: Boolean = handleDesktopForce(view, url)
            // true — мы сами запустили загрузку полного хоста, до showProgress
            // не доходим; false — перенос не нужен, страница грузится как есть.
            if (forced) return
        }
        activity.showProgress(10)
    }

    // 1.3.7: ошибки главного фрейма — в журнал с адресом. Из лога владельца
    // (08.11.2026) виден сбой TLS (net_error -202, «Trust anchor not found»)
    // без указания адреса — теперь хоп, на котором сеть сорвалась, виден
    // сразу. Подресурсы не логируются (шум); поведение WebView не меняем:
    // ошибка сертификата, как и раньше, отменяет загрузку.
    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame) {
            Log.w(
                "PikoVK-Nav",
                "ошибка загрузки (main frame) ${request.url}: ${error.description} " +
                    "(код ${error.errorCode})"
            )
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        activity.onPageAddress(url)
        activity.showProgress(100)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        // Снифер (1.3.5): внутренняя навигация VK (pushState) приходит
        // только сюда — без этого обычные клики по ленте в журнал не попадали.
        if (isReload) {
            LinkSniffer.sniff(LinkSniffer.EVENT_RELOAD, url)
        } else {
            LinkSniffer.sniff(LinkSniffer.EVENT_SPA, url)
        }
        activity.onPageAddress(url)
    }

    // Падение процесса рендера — тихо пересобираем WebView.
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        activity.onRenderGone()
        return true
    }

    // 1.3.16: короткое уведомление об отменённом рекламном переходе.
    // Падение тоста не должно ломать навигацию — переход всё равно отменён.
    private fun notifyAdBlocked() {
        try {
            Toast.makeText(activity, "Рекламная ссылка заблокирована", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // нет доступа к UI — просто остаёмся на текущей странице
        }
    }

    // Заимствовано у PinoK (AdBlockInterceptor): счётчику отдаём честный
    //  HTTP 451 с пустым телом — JS VK уходит в fallback, а не ждёт пустой 200.
    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8", 451,
            "Blocked by PikoVK AdBlock",
            null, ByteArrayInputStream(ByteArray(0))
        )

    private companion object {
        // Окно (мс), внутри которого повторный перенос того же адреса — отскок VK.
        const val BOUNCE_WINDOW_MS = 4000L

        // Столько быстрых отскоков терпим, дальше разрешаем мобильную версию.
        const val MAX_FORCE_BOUNCES = 2
    }
}
