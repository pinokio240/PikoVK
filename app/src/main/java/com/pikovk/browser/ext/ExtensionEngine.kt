package com.pikovk.browser.ext

import android.net.Uri
import com.pikovk.browser.util.Prefs

// Движок расширений PikoVK.
//
// Все включённые расширения собираются в ОДИН скрипт, который внедряется
// в каждую страницу ДО выполнения кода самой страницы
// (WebViewCompat.addDocumentStartJavaScript). Это быстрее и надёжнее,
// чем инъекция «после загрузки»: расширение видит страницу с самого начала.
//
// Все расширения только ДОБАВЛЯют функции и переключаются тумблерами.
// Единственное скрывающее расширение — «Блокировка рекламы» (расширение 4):
// с 1.3.16 включено по умолчанию — владелец дважды за день прислал примеры
// рекламы в ленте и прямо попросил блокировать («проанализируй, как
// блокировать подобную рекламу»). Уровни: 1) точные селекторы рекламных
// контейнеров VK; 2) сторонние счётчики; 3) ссылочный (rv.mail.ru/adfox.ru);
// 4) лид-формы type=leadform; 5) с 1.3.17 — штатные флаги VK noAds/hide_ad,
// маркеры непомеченных карточек (.ads_mark, ad_info.php, adsite_,
// data-mt-video-hls), рекламные эндпоинты VK (ads_rotate,
// al_feed.php?ads_section, al_video.php?act=ads_stat) и рекламный CDN
// mradx.net (третий пример владельца: «ну и почему я снова вижу рекламу?»).
// 1.3.19: видеохаки PinoK (заглушка Adman, flashvars, лимиты показов)
// ограничены видеостраницами — на аудио заглушка зависала старт следующего
// трека («трек переходит, а воспроизведение не начинается»).
// Принцип fail-open: признак не совпал — реклама просто остаётся, контент
// не затрагивается.
//
// Производительность (1.3.0): страница ленты постоянно мутирует (бесконечная
// подгрузка), поэтому всё, что сидит в MutationObserver, обязано быть
// дешёвым: дебаунс, скан только добавленных узлов, WeakSet вместо записи
// атрибутов. Подробности — у TWEAKS_JS и COMMON_JS.
//
// Добавить новое расширение:
//   1) тумблер в Prefs;
//   2) секция сборки здесь (CSS/JS);
//   3) переключатель в SettingsActivity.
//
// ВАЖНО: внутри JS-секций запрещены шаблонные литералы и символ '$' —
// они конфликтуют со строками Kotlin.
object ExtensionEngine {

    // Origin-правила для внедрения скрипта (сами поддомены vk тоже покрыты).
    //  vkvideo.ru добавлен ради Adman-заглушки в плеере VK Видео (заимствовано
    //  у PinoK): без него плеер живёт в другом origin и скрипт туда не попадает.
    //  С 1.3.19 заглушка работает только на видеостраницах (хост vkvideo.ru
    //  или путь /video*, /clip*) — на аудио она мешала старту следующего
    //  трека, см. AD_BLOCK_JS и README, раздел 1.3.19.
    val ALLOWED_ORIGINS = setOf(
        "https://vk.ru", "https://*.vk.ru",
        "https://vk.com", "https://*.vk.com",
        "https://vkvideo.ru", "https://*.vkvideo.ru"
    )

    fun buildScript(): String {
        val sb = StringBuilder()
        sb.append("(function(){'use strict';")
        sb.append("var F={dark:").append(Prefs.extDark)
            .append(",media:").append(Prefs.extMedia)
            .append(",tweaks:").append(Prefs.extTweaks)
            .append(",adblock:").append(Prefs.extAdBlock).append("};")
        sb.append(COMMON_JS)
        if (Prefs.extDark) sb.append(DARK_JS)
        if (Prefs.extTweaks) sb.append(TWEAKS_JS)
        if (Prefs.extMedia) sb.append(MEDIA_JS)
        if (Prefs.extAdBlock) sb.append(AD_BLOCK_JS)
        sb.append("})();")
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Сетевая часть «Блокировки рекламы» (уровень 2): сторонние счётчики.
    // Вызывается из VkWebViewClient.shouldInterceptRequest ТОЛЬКО при
    // включённом расширении (Prefs.extAdBlock).
    //
    // Это БЛОКЛИСТ, а не белый список: блокируются только перечисленные
    // домены чистой аналитики/рекламы, всё остальное (включая все контентные
    // домены VK, CDN и Mini Apps) грузится без ограничений.
    //
    // Источники: vtosters/adblock (фильтр для vk.ru/vk.com), ruadlist,
    // PinoK (github.com/pinokio240/PinoK_1, GPL-3.0) — их NetworkMods.AD_DOMAINS
    // (ad.vk.com, ads.vk.com, rs.mail.ru, targ.mail.ru — выделенные рекламные
    // хосты, контент VK с них не грузится).
    //
    // Уроки PinoK учтены: vk.cc НЕ блокируем (официальный сокращатель VK —
    // их issue #62: блокировка ломала обычные ссылки из ленты/чатов),
    // голые vk.ru/vk.com НЕ трогаем (убили бы api/oauth).
    // ------------------------------------------------------------------
    private val TRACKER_HOSTS = setOf(
        "ad.mail.ru",              // рекламная сеть Mail.ru (PinoK + vtosters)
        "rs.mail.ru",              // баннерный ротатор Mail.ru (PinoK)
        "targ.mail.ru",            // таргетинг Mail.ru (PinoK)
        "mradx.net",               // рекламный CDN Mail.ru/myTarget (1.3.17):
                                   // картинки и видео рекламных карточек
                                   // (vtosters: img[src^="https://r0.mradx.net"]);
                                   // контент VK живёт на userapi.com, этот домен
                                   // только для рекламы
        "rv.mail.ru",              // редиректор рекламы Mail.ru: рекламная карточка
                                   // ленты VK ведёт через него на рекламируемый мини-апп
                                   // (в адресе banner_id и vk_bid; из лога владельца
                                   // 08.11.2026). Обычный контент VK через него
                                   // не грузится никогда — не белый список, точный домен
        "adfox.ru",                // рекламная платформа VK (vtosters/adblock)
        "ad.vk.com",               // рекламный API VK (PinoK)
        "ads.vk.com",              // рекламный сервис VK (PinoK)
        "top-fwz1.mail.ru",        // счётчик Mail.ru
        "tns-counter.ru",          // Mediascope (статистика аудитории)
        "mc.yandex.ru",            // Яндекс.Метрика
        "an.yandex.ru",            // рекламная аналитика Яндекса
        "scorecardresearch.com",   // comScore
        "stats.vk-portal.net"      // статистика VK (не влияет на контент)
    )

    private val VK_HOSTS = setOf("vk.ru", "vk.com")

    // True для сторонних счётчиков, ретаргетинг-пикселя VK /rtrg (1x1-gif,
    // на контент не влияет) и — с 1.3.17 — выделенных рекламных эндпоинтов
    // самого VK (ротатор рекламы /ads_rotate, рекламная секция ленты
    // al_feed.php с ads_section, статистика видеорекламы al_video.php с
    // act=ads_stat; источник — vtosters/adblock). Всё остальное — false,
    // запрос пропускается.
    fun isAdTracker(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val u = Uri.parse(url.trim())
            val rawHost: String? = u.host
            if (rawHost == null) return false
            val host = rawHost.lowercase()
            if (TRACKER_HOSTS.any { h -> host == h || host.endsWith(".$h") }) return true
            val rawPath: String? = u.path
            val path: String
            if (rawPath == null) {
                path = "" // нет пути — на /rtrg не начинается, ниже вернётся false
            } else {
                path = rawPath
            }
            val isVk: Boolean = VK_HOSTS.any { h -> host == h || host.endsWith(".$h") }
            if (!isVk) return false
            // пиксель ретаргетинга /rtrg (1x1-gif)
            if (path.startsWith("/rtrg")) return true
            // 1.3.17: рекламные эндпоинты VK. Контент через них не грузится:
            // ads_rotate — ротатор рекламных блоков, al_feed.php с параметром
            // ads_section — запрос рекламной секции ленты, al_video.php с
            // act=ads_stat — статистика видеорекламы (vtosters/adblock)
            if (path.startsWith("/ads_rotate")) return true
            if (path.endsWith("/al_feed.php") && u.getQueryParameter("ads_section") != null) return true
            val act: String? = u.getQueryParameter("act")
            if (path.endsWith("/al_video.php") && act == "ads_stat") return true
            false
        } catch (_: Exception) {
            false // не смогли разобрать URL — пропускаем (fail-open)
        }
    }

    // ------------------------------------------------------------------
    // 1.3.16: ведёт ли ссылка на рекламу? Вызывается из
    // VkWebViewClient.shouldOverrideUrlLoading при включённой блокировке —
    // переход отменяется ещё до загрузки (с коротким уведомлением).
    //
    // Признаки (fail-open: не уверены — false, страница грузится как есть):
    //   1) хост rv.mail.ru / adfox.ru — рекламный редиректор и рекламная
    //      платформа; с 1.3.17 также рекламный CDN mradx.net и ad.mail.ru;
    //      обычные ссылки VK на них не ведут (см. TRACKER_HOSTS);
    //   2) away.php?to=… — решает ЦЕЛЬ трекинг-обёртки (рекурсивно,
    //      глубина 1: внутри to= рекламный адрес). Без этого разворот away
    //      загружал бы рекламную цель напрямую, минуя отмену;
    //   3) VK-хост, путь /app…, во фрагменте type=leadform — страница
    //      лид-формы VK Рекламы (пример владельца 08.11.2026:
    //      m.vk.ru/app51478948#?type=leadform&d=…&isDarkTheme=0).
    //      Фрагмент не доходит до сети (поэтому shouldInterceptRequest
    //      его не видит), зато входит в URI навигации — здесь он виден.
    // ------------------------------------------------------------------
    fun isAdNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val u = Uri.parse(url.trim())
            val rawHost: String? = u.host
            if (rawHost == null) return false
            val host = rawHost.lowercase()
            val adRedirector: Boolean = host == "rv.mail.ru" || host.endsWith(".rv.mail.ru") ||
                host == "adfox.ru" || host.endsWith(".adfox.ru") ||
                host.endsWith(".mradx.net") || host == "mradx.net" ||
                host == "ad.mail.ru" || host.endsWith(".ad.mail.ru")
            if (adRedirector) return true
            val rawPath: String? = u.path
            if (rawPath == null) return false
            if (rawPath.endsWith("/away.php")) {
                val target: String? = u.getQueryParameter("to")
                if (target == null) return false
                return isAdNavigation(target)
            }
            val isVk: Boolean = host == "vk.ru" || host.endsWith(".vk.ru") ||
                host == "vk.com" || host.endsWith(".vk.com")
            if (!isVk) return false
            // путь строго /app<цифры>…: /apps и прочее — не мини-апп
            if (!rawPath.startsWith("/app")) return false
            if (rawPath.length < 5) return false
            val afterApp: Char = rawPath.get(4)
            if (afterApp < '0' || afterApp > '9') return false
            val frag: String? = u.fragment
            if (frag == null) return false
            frag.contains("type=leadform")
        } catch (_: Exception) {
            false // не смогли разобрать URL — переход не трогаем (fail-open)
        }
    }

    // ------------------------------------------------------------------
    // 1.3.16: сетевой блок ОБЩЕГО рендерера лид-форм VK Рекламы.
    // Вызывается из shouldInterceptRequest при включённой блокировке.
    //
    // Все лид-формы VK Рекламы рендерит один и тот же мини-апп 51478948:
    // у разных рекламодателей в адресе отличается только зашифрованная
    // нагрузка d= (оба примера владельца за 08.11.2026 — один и тот же
    // апп с разными d=; тот же идентификатор виден и в чужой рекламе).
    // Блок одного аппа закрывает все лид-формы сразу. iframe такой
    // карточки грузит документ m.vk.ru/app51478948, при этом фрагмент
    // #?type=leadform до сети не доходит — режем по пути. Игры и обычные
    // мини-аппы (другие пути /app…) не задеваются.
    // ------------------------------------------------------------------
    fun isLeadAppRequest(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val u = Uri.parse(url.trim())
            val rawHost: String? = u.host
            if (rawHost == null) return false
            val host = rawHost.lowercase()
            val isVk: Boolean = host == "vk.ru" || host.endsWith(".vk.ru") ||
                host == "vk.com" || host.endsWith(".vk.com")
            if (!isVk) return false
            val rawPath: String? = u.path
            if (rawPath == null) return false
            // точные формы пути рендерера: /app51478948, /app51478948_
            // (внутренние параметры аппа) и /app51478948/
            return rawPath == "/app51478948" ||
                rawPath.startsWith("/app51478948_") ||
                rawPath.startsWith("/app51478948/")
        } catch (_: Exception) {
            false // не смогли разобрать URL — запрос пропускаем (fail-open)
        }
    }

    // ------------------------------------------------------------------
    // Общая утилита: скрытие блоков по тексту заголовка
    // (используется только включёнными пользователем расширениями)
    //
    // Пагинация ленты = постоянные мутации DOM, поэтому утилита сделана
    // дешёвой (1.3.0):
    //   - сканирует только переданный корень (document при первом проходе,
    //     дальше — только добавленные узлы), а не весь документ;
    //   - «проверенность» хранится в WeakSet, а не в атрибуте:
    //     запись атрибута на каждый div вызывала лишние reflow при скролле;
    //   - вызывающий код (Твики) debounce-ит обсервер — см. TWEAKS_JS.
    // ------------------------------------------------------------------
    private val COMMON_JS = """
        function vkbMount(el){
          try{
            if(document.head){document.head.appendChild(el);}
            else{document.addEventListener('DOMContentLoaded',function(){document.head.appendChild(el);},{once:true});}
          }catch(e){}
        }
        function vkbHideByText(words, root, seen){
          try{
            var scope=(root&&root.querySelectorAll)?root:document;
            var list=null;
            if(scope!==document&&scope.matches&&scope.matches('div,section,li')){
              list=[scope];
              var inner=scope.querySelectorAll('div,section,li');
              for(var i=0;i<inner.length;i++){list.push(inner[i]);}
            }else{
              list=scope.querySelectorAll('div,section,li');
            }
            for(var k=0;k<list.length;k++){
              var el=list[k];
              if(seen){if(seen.has(el)){continue;}seen.add(el);}
              else if(el.getAttribute('data-vkb-checked')){continue;}
              var t=(el.textContent||'').trim();
              if(!t||t.length>400){continue;}
              for(var j=0;j<words.length;j++){
                if(t.indexOf(words[j])>=0){
                  var p=el;
                  while(p.parentElement&&p.parentElement!==document.body
                        &&(p.parentElement.textContent||'').trim().length<400){p=p.parentElement;}
                  p.style.display='none';
                  break;
                }
              }
            }
          }catch(e){}
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // Расширение 1. Тёмная тема — ВСЕГДА тёмная, когда тумблер включён
    // (1.3.6: раньше следовала системной через matchMedia — при светлой
    // системе VK оставался светлым, чего владелец не ожидал: тема
    // «по умолчанию светлая, а не чёрная»).
    // ------------------------------------------------------------------
    private val DARK_JS = """
        try{
          var st=document.createElement('style');
          st.id='vkb-dark';
          st.textContent='html.vkb-dark{filter:invert(0.90) hue-rotate(180deg);background:#111;color-scheme:dark}'
            +'html.vkb-dark img,html.vkb-dark video,html.vkb-dark iframe,html.vkb-dark canvas,'
            +'html.vkb-dark svg,html.vkb-dark [style*="background-image"]'
            +'{filter:invert(1) hue-rotate(180deg)}';
          vkbMount(st);
          function vkbDarkApply(){
            var el=document.documentElement;
            if(el){el.classList.add('vkb-dark');}
          }
          document.addEventListener('DOMContentLoaded',vkbDarkApply);
          vkbDarkApply();
        }catch(e){}
    """.trimIndent()

    // ------------------------------------------------------------------
    // Расширение 2. Твики ленты: скрыть стори, компактные посты
    // (включается только пользователем в настройках)
    //
    // Производительность (1.3.0): лента бесконечная, DOM мутирует при каждой
    // подгрузке постов. Раньше обсервер на КАЖДУЮ мутацию запускал полный
    // обход документа (querySelectorAll по всем div/section/li) — это было
    // главной причиной тормозящей пагинации при включённых твиках.
    // Теперь:
    //   1) в обсервере собираются ТОЛЬКО добавленные узлы (Script/Style мимо);
    //   2) обработка debounce 250 мс — пачки мутаций сливаются в один проход;
    //   3) сканируется только добавленное поддерево, а не весь документ;
    //   4) «проверенность» — в WeakSet, без записи атрибутов (без reflow).
    // Первый проход по всей странице остаётся (чтобы скрыть уже готовые стори).
    // ------------------------------------------------------------------
    private val TWEAKS_JS = """
        try{
          var ts=document.createElement('style');
          ts.id='vkb-tweaks';
          ts.textContent='[class*="Stories"],[id*="stories"],[data-testid*="stories"]{display:none!important}'
            +'div[class*="Post"],div[class*="post"]{margin-bottom:6px!important}'
            +'.vkuiCard{margin:4px 0!important}';
          vkbMount(ts);
          var tweakWords=['Истории'];
          var tweakSeen=new WeakSet();
          var tweakQueue=[];
          var tweakTimer=null;
          function vkbTweakScan(root){vkbHideByText(tweakWords,root,tweakSeen);}
          function vkbTweakFlush(){
            tweakTimer=null;
            try{
              var q=tweakQueue;tweakQueue=[];
              for(var i=0;i<q.length;i++){vkbTweakScan(q[i]);}
            }catch(e){}
          }
          function vkbTweakObserve(muts){
            try{
              for(var i=0;i<muts.length;i++){
                var add=muts[i].addedNodes;
                for(var j=0;j<add.length;j++){
                  var n=add[j];
                  if(n.nodeType!==1||n.tagName==='SCRIPT'||n.tagName==='STYLE'){continue;}
                  tweakQueue.push(n);
                }
              }
            }catch(e){}
            if(tweakTimer){clearTimeout(tweakTimer);}
            tweakTimer=setTimeout(vkbTweakFlush,250);
          }
          function vkbTweakStart(){
            vkbTweakScan();
            try{new MutationObserver(vkbTweakObserve).observe(document.body,{childList:true,subtree:true});}catch(e){}
          }
          if(document.body){vkbTweakStart();}
          else{document.addEventListener('DOMContentLoaded',vkbTweakStart,{once:true});}
        }catch(e){}
    """.trimIndent()

    // ------------------------------------------------------------------
    // Расширение 3. Скачивание медиа: фото (долгое нажатие -> «Скачать»),
    // прямые ссылки на mp4/mp3/pdf. Файлы уходят в системный DownloadManager.
    // ------------------------------------------------------------------
    private val MEDIA_JS = """
        try{
          function vkbDl(u){
            try{
              if(window.VkbBridge&&window.VkbBridge.download){window.VkbBridge.download(u);}
            }catch(e){}
          }
          document.addEventListener('contextmenu',function(ev){
            var t=ev.target;
            if(!t||t.tagName!=='IMG'){return;}
            var src=t.currentSrc||t.src;
            if(!src){return;}
            if(window.confirm('Скачать изображение?')){
              ev.preventDefault();
              vkbDl(src);
            }
          },false);
          document.addEventListener('click',function(ev){
            var a=ev.target&&ev.target.closest?ev.target.closest('a[href]'):null;
            if(!a){return;}
            var h=a.getAttribute('href')||'';
            if(/\.(mp4|m4v|mp3|m4a|pdf)(\?|#|$)/i.test(h)){
              if(window.confirm('Скачать файл?')){
                ev.preventDefault();
                ev.stopPropagation();
                vkbDl(a.href);
              }
            }
          },true);
        }catch(e){}
    """.trimIndent()

    // ------------------------------------------------------------------
    // Расширение 4. Блокировка рекламы (уровень 1): ТОЛЬКО точные селекторы
    // рекламных контейнеров, которые VK сам помечает в разметке.
    // ВЫКЛЮЧЕНО по умолчанию (Prefs.extAdBlock = false).
    //
    // 1.3.15: добавлен ССЫЛОЧНЫЙ уровень. VK перестал помечать часть рекламных
    // карточек ленты (лог владельца 08.11.2026: карточка m.vk.ru ведёт через
    // rv.mail.ru/redir?banner_id=...&vk_bid=... на мини-апп с leadform — и ни
    // один из прежних селекторов на ней не срабатывал). Точный признак такой
    // карточки — ссылка через рекламный редиректор rv.mail.ru / adfox.ru:
    // обычные посты на них не ссылаются никогда. Скрипт прячет ближайший
    // контейнер карточки (конец AD_BLOCK_JS), сетевой блоклист (TRACKER_HOSTS)
    // дополнен rv.mail.ru и adfox.ru — и карточка, и её запросы уходят.
    //
    // 1.3.16: лид-формы. Владелец прислал второй пример — прямую ссылку
    // m.vk.ru/app51478948#?type=leadform&d=…&isDarkTheme=0: карточка ведёт
    // на мини-апп лид-формы НАПРЯМУЮ, без редиректора, и ссылочный уровень
    // 1.3.15 её не видел. Лид-формы все рендерит один общий апп 51478948
    // (отличается только зашифрованный параметр d=). Добавлено:
    //   а) CSS: [src*="type=leadform"] — мгновенное скрытие встроенного
    //      iframe лид-формы ещё до скрипта;
    //   б) ссылочный скан расширен на iframe[src] и маркер type=leadform:
    //      карточка с прямой ссылкой или встроенным фреймом прячется
    //      целиком (фрагмент #?… не уходит в сеть, но в атрибуте DOM он
    //      есть — это точный признак рекламы);
    //   в) сетевой уровень: isLeadAppRequest — документ общего рендерера
    //      (/app51478948) получает HTTP 451;
    //   г) навигационный уровень: isAdNavigation — переход по рекламной
    //      ссылке (rv.mail.ru, adfox.ru, лид-форма, в т.ч. внутри away.php)
    //      отменяется до загрузки, с уведомлением.
    //
    // 1.3.17: третий пример владельца — снова rv.mail.ru с новым banner_id:
    // «ну и почему я снова вижу рекламу?». Разбор показал слабые места:
    // рекламная ссылка может появляться в разметке ПОЗЖЕ вставки карточки
    // или только на клик (ссылочный скан смотрел только добавляемые узлы);
    // контейнер карточки не всегда совпадает со списком классов (пряталась
    // одна ссылка); картинки/видео карточек грузятся с рекламного CDN
    // mradx.net; выдача рекламы идёт через собственные эндпоинты VK.
    // Добавлено (источник селекторов — vtosters/adblock, подробности в
    // README, раздел 1.3.17):
    //   а) штатные выключатели VK: window.noAds, noAdsAtAll, no_left_ads,
    //      no_ads, isNoAdsForce, hide_ad, ya_direct=false,
    //      yaDirectAdActive=false — код страницы сам проверяет их и не
    //      инициализирует рекламные блоки (scriptlet-набор vtosters);
    //   б) маркерный уровень: карточка прячется ЦЕЛИКОМ по признакам
    //      внутри неё — плашка .ads_mark (m.vk.ru), .wall_marked_as_ads,
    //      подпись promoted-поста, пункт меню ad_info.php, adsite_ и
    //      post_ad_data в onclick, рекламный атрибут видеорекламы
    //      data-mt-video-hls (раньше пряталась только сама плашка);
    //   в) ссылочный скан расширен: img/source/video и атрибут
    //      data-mt-video-hls, домены-признаки дополнены mradx.net и
    //      ad.mail.ru, наблюдатель отслеживает дописывание href/src/onclick,
    //      плюс три контрольных прохода (1/3/7 с);
    //   г) контейнер карточки ищется шире: data-post-id, _post, Post,
    //      feed_post (новая React-разметка);
    //
    // Расшифровка каждого селектора (источники: vtosters/adblock v3.19,
    // stolnikov/vk-ad-prune — фильтры, поддерживаемые сообществом для vk.ru):
    //   #ads_left, #left_ads               — левый баннер (полная версия)
    //   ._ads_promoted_post_data_w         — контейнер promoted-поста в ленте
    //   .ads_ads_news_wrap                 — рекламный блок в новостях
    //   div[data-ad-view]                  — VK сам помечает рекламу атрибутом
    //                                        data-ad-view (полная и мобильная)
    //   .post[data-ad]                     — пост с атрибутом рекламы
    //   .wall_marked_as_ads, .ads_mark     — плашка «Реклама» внутри поста
    //   .post_marked_as_ads, .ad_card      — маркеры рекламы (vtosters + PinoK)
    //   .wall_item[data-ad-view]           — рекламная запись (m.vk.ru)
    //   [class^="_ads_block_data_w"]       — обёртка рекламного блока (m.vk.ru)
    //   [src*="type=leadform"]             — встроенный iframe лид-формы
    //                                        VK Рекламы (1.3.16, рендерер —
    //                                        общий апп 51478948); фрагмент
    //                                        в атрибуте есть, в сеть не уходит
    //   .audio_promo,                      — промо-блоки VK Музыки
    //     .audio_subscribe_promo__content
    //   .story_feed_new_item_promo         — промо-сторис
    //   #apps_ads_wrap,                    — баннеры приложений/спецпромо
    //     #ads_special_promo_wrap
    //
    // СОЗНАТЕЛЬНО НЕ ВКЛЮЧЕНО (риск для контента):
    //   [class^="ads"] / [class*="ads"]    — слишком широко, ломает контент;
    //   широкие маски из PinoK ([class*="ad-"] совпадает с "download-...",
    //   .promo/.reklama) — НЕ переносим, это наш старый баг v1.0;
    //   сетевые блоки путей vk.ru          — тот же домен, что и контент;
    //                                        точные исключения — только
    //                                        выделенные рекламные пути:
    //                                        /app51478948 (общий рендерер
    //                                        лид-форм, isLeadAppRequest),
    //                                        /ads_rotate, /al_feed.php с
    //                                        ads_section, /al_video.php с
    //                                        act=ads_stat и пиксель /rtrg
    //                                        (1.3.16–1.3.17, контента на
    //                                        них нет);
    //   vk.cc                              — сокращатель VK (урок PinoK #62).
    //
    // Заимствовано у PinoK (GPL-3.0) — обработка рекламы в плеере:
    //   1) заглушка window.AdmanHTML (их «метод №2», самый чистый): рекламный
    //      SDK-класс заменяется так, что колбэк onCompleted срабатывает
    //      мгновенно и плеер сразу стартует видео. Инъекция на document-start
    //      (как у них после баги «12с чёрный экран» при поздней инъекции);
    //   2) flashvars.isAdvertismentsSwitchOffForced (метод №4, best-effort);
    //   3) дневной лимит показов рекламы в плеере (метод №5).
    // Не взяты: localStorage deviceId-затирание и принудительный HD.
    //
    // 1.3.19: все три приёма ограничены видеостраницами (хост vkvideo.ru
    // или путь /video*, /clip* на любом домене VK). Причина — жалоба
    // владельца на 1.3.18: «трек переходит к следующему, но
    // воспроизведение не начинается». По HAR-дампам при каждом старте
    // трека плеер проходит шаг рекламного решения adman; известное
    // рабочее состояние аудио — «adman не загрузился (script_load_fail),
    // играет музыка». Определённая на аудиостранице заглушка меняла
    // ветку на «SDK есть, крути пре-ролл», а её неполный контракт (onReady
    // никогда не срабатывает, методов реального SDK нет) молча зависал
    // цепочку старта. Внутри заглушки добавлен подстраховывающий цикл
    // «рекламы нет» на случай SPA-перехода «видео → аудио», где объект
    // остаётся в window до перезагрузки документа.
    // ------------------------------------------------------------------
    private val AD_BLOCK_JS = """
        try{
          var ab=document.createElement('style');
          ab.id='vkb-adblock';
          ab.textContent='#ads_left,#left_ads'
            +',._ads_promoted_post_data_w,.ads_ads_news_wrap'
            +',div[data-ad-view],.post[data-ad]'
            +',.wall_marked_as_ads,.ads_mark,.post_marked_as_ads,.ad_card'
            +',.wall_item[data-ad-view],[class^="_ads_block_data_w"]'
            +',.audio_promo,.audio_subscribe_promo__content'
            +',.story_feed_new_item_promo'
            +',#apps_ads_wrap,#ads_special_promo_wrap'
            +',[src*="type=leadform"]'
            // 1.3.17 (vtosters/adblock): баннеры и контейнеры рекламы,
            // которые не покрывали прежние селекторы
            +',[class^="ads_ad_"],.ads_ads_box'
            +',.ads300-thumb,.ads600x200,.ads_600x200,.trg-b-banner-block'
            +',div[data-ad],.post[data-ad-view],#_ads_block_data_w'
            +',#mailru-visibility-check,div[id^="postadsite_"]'
            +'{display:none!important}';
          vkbMount(ab);
        }catch(e){}
        try{
          // 1.3.17: штатные выключатели рекламы VK (scriptlet-набор
          // vtosters/adblock): код страницы проверяет эти флаги и сам
          // не инициализирует рекламные блоки. Скрипт внедряется
          // до кода страницы — флаги видны всем проверкам.
          window.noAds=true;
          window.noAdsAtAll=true;
          window.no_left_ads=true;
          window.no_ads=true;
          window.isNoAdsForce=true;
          window.hide_ad=true;
          window.ya_direct=false;
          window.yaDirectAdActive=false;
        }catch(e){}
        // 1.3.19: видеохаки PinoK действуют ТОЛЬКО на видеостраницах.
        // Видеостраница: хост vkvideo.ru (любой путь) или путь /video*,
        // /clip* на любом домене VK. На остальных (аудио, лента, профиль)
        // ни заглушка, ни flashvars, ни лимиты показов не создаются —
        // аудио остаётся в известном рабочем состоянии «adman не
        // загрузился — играет музыка».
        var vkbVidHost=false;
        try{
          var vh=location.hostname;
          vkbVidHost=(vh==='vkvideo.ru'||vh.indexOf('.vkvideo.ru')!==-1);
        }catch(e){}
        var vkbVidPath=false;
        try{
          var vp=location.pathname;
          vkbVidPath=(vp.indexOf('/video')===0||vp.indexOf('/clip')===0);
        }catch(e){}
        if(vkbVidHost||vkbVidPath){
          try{
            if(!window.AdmanHTML){
              window.AdmanHTML=function(o){
                var self=this;
                var c=(o&&typeof o==='object')?o:{};
                this.onReady=c.onReady||function(){};
                this.onStarted=c.onStarted||function(){};
                this.onPlayed=c.onPlayed||function(){};
                this.onPaused=c.onPaused||function(){};
                this.onCompleted=c.onCompleted||function(){};
                this.onClosed=c.onClosed||function(){};
                this.onSkipped=c.onSkipped||function(){};
                this.onClicked=c.onClicked||function(){};
                this.onTimeRemained=c.onTimeRemained||function(){};
                this.onError=c.onError||function(){};
                this.setDebug=function(){};
                this.pause=function(){};this.resume=function(){};
                this.setVolume=function(){};this.setFullscreen=function(){};
                this.setPosition=function(){};
                this.adMidrollPoint=function(cb){if(cb){cb();}};
                this.destroy=function(){};this.load=function(){};
                this.stop=function(){};
                this._video=function(){
                  var v=false;
                  try{
                    var h=location.hostname;
                    v=(h==='vkvideo.ru'||h.indexOf('.vkvideo.ru')!==-1);
                  }catch(e){}
                  if(!v){
                    try{
                      var p=location.pathname;
                      v=(p.indexOf('/video')===0||p.indexOf('/clip')===0);
                    }catch(e){}
                  }
                  return v;
                };
                this._done=function(){
                  try{self.onCompleted();}catch(e){}
                  try{self.onClosed();}catch(e){}
                };
                this.init=function(){self._done();};
                this.start=function(){self._done();};
                this.skip=function(){self._done();};
                // Подстраховка 1.3.19: объект создан НЕ на видеостранице
                // (обычно это SPA-переход «видео → аудио», где заглушка
                // осталась в window до перезагрузки документа). Мгновенно
                // отрабатываем полный цикл «рекламы нет» — включая
                // onReady, которого не хватало прежней заглушке:
                // аудиоплеер не ждёт и сразу стартует музыку.
                if(!self._video()){
                  try{self.onReady();}catch(e){}
                  try{self.onSkipped();}catch(e){}
                  self._done();
                }
              };
            }
          }catch(e){}
          try{
            var fv=window.flashvars;
            if(!fv){fv={};window.flashvars=fv;}
            fv.isAdvertismentsSwitchOffForced='1';
            fv.showAd='0';
          }catch(e){}
          try{
            localStorage.setItem('@vpl-flags',JSON.stringify({advForce:false}));
            localStorage.setItem('_vp_lastDayAdvShown','999');
            localStorage.setItem('_vp_lastDayVideoShown','999');
          }catch(e){}
        }
        try{
          // 1.3.15: карточки рекламы в ленте, которые VK НЕ помечает в
          // разметке. Точный признак — ссылка через рекламный редиректор
          // (rv.mail.ru, adfox.ru): обычные посты на них не ссылаются.
          // 1.3.16: добавлен маркер лид-формы type=leadform (прямая ссылка
          // или встроенный iframe карточки ведут на общий рендерер
          // app51478948) и скан iframe[src].
          // Прячем ближайший контейнер карточки; лента бесконечная —
          // наблюдаем добавленные узлы с дебаунсом (как в твиках).
          // Признак не найден — ничего не прячем (fail-open).
          var AD_HOSTS=['rv.mail.ru','adfox.ru','mradx.net','ad.mail.ru'];
          var AD_MARK='type=leadform';
          var AD_TOKENS=['wi','wall_item','feed_row','wall_module','pi','_post','Post','feed_post'];
          // 1.3.17: маркеры рекламы ВНУТРИ карточки — по ним карточка
          // прячется целиком даже когда ссылка на редиректор в разметке
          // не видна (появляется только на клик). Источник — vtosters:
          // рекламный пост m.vk.ru это .wall_item с плашкой .ads_mark,
          // в полном VK у рекламного поста в меню есть ad_info.php,
          // в onclick рекламных ссылок — adsite_/post_ad_data,
          // у рекламного видео — атрибут myTarget data-mt-video-hls.
          var AD_MARK_SEL='.ads_mark,.wall_marked_as_ads'
            +',.wall_text_name_explain_promoted_post'
            +',.ui_actions_menu_item[href*="ad_info.php"]'
            +',a[onclick*="adsite_"],a[onclick*="post_ad_data"]'
            +',[data-mt-video-hls]';
          function vkbAdHasMark(s){
            var i=s.indexOf(AD_MARK);
            while(i>=0){
              var prev=(i>0)?s.charAt(i-1):'&';
              if(prev==='?'||prev==='&'||prev==='#'){return true;}
              i=s.indexOf(AD_MARK,i+1);
            }
            return false;
          }
          function vkbAdIsVkHost(u){
            try{
              var h=u.hostname;
              return h==='vk.ru'||h==='vk.com'||h.slice(-6)==='.vk.ru'||h.slice(-7)==='.vk.com';
            }catch(e){return false;}
          }
          function vkbAdIsAppPath(p){
            if(p.indexOf('/app')!==0){return false;}
            if(p.length<5){return false;}
            var c=p.charAt(4);
            return c>='0'&&c<='9';
          }
          // подстрочный признак для развёрнутых строк (внутри away-обёртки
          // после декодирования '#' обрезает повторный разбор URL —
          // поэтому ищем VK-хост + /app<цифра> прямо в строке)
          function vkbAdHasVkAppLead(s){
            var hosts=['.vk.ru/app','.vk.com/app','//vk.ru/app','//vk.com/app'];
            for(var i=0;i<hosts.length;i++){
              var idx=s.indexOf(hosts[i]);
              while(idx>=0){
                var c=s.charAt(idx+hosts[i].length);
                if(c>='0'&&c<='9'){return true;}
                idx=s.indexOf(hosts[i],idx+1);
              }
            }
            return false;
          }
          function vkbAdIsAdLink(h){
            var d=h;
            if(h.indexOf('%')>=0){try{d=decodeURIComponent(h);}catch(e){d=h;}}
            for(var i=0;i<AD_HOSTS.length;i++){
              if(h.indexOf(AD_HOSTS[i])>=0){return true;}
              if(d!==h&&d.indexOf(AD_HOSTS[i])>=0){return true;}
            }
            // лид-форма: ТОЛЬКО хосты VK и путь /app<цифры>, параметр
            // type=leadform на границе (?/&#) — иначе utm_type, чужие
            // сайты и каталог /apps ловились бы ложью
            var u=null;
            try{u=new URL(h,location.href);}catch(e){u=null;}
            if(u&&vkbAdIsVkHost(u)&&vkbAdIsAppPath(u.pathname)&&vkbAdHasMark(h)){return true;}
            if(vkbAdHasVkAppLead(h)&&vkbAdHasMark(h)){return true;}
            if(d!==h&&vkbAdHasVkAppLead(d)&&vkbAdHasMark(d)){return true;}
            return false;
          }
          function vkbAdFindCard(from){
            var p=from;
            while(p&&p!==document.body){
              var ok=false;
              var cls=(p.className&&typeof p.className==='string')?p.className:'';
              if(cls){
                var parts=cls.split(/\s+/);
                for(var i=0;i<parts.length;i++){
                  for(var j=0;j<AD_TOKENS.length;j++){
                    if(parts[i]===AD_TOKENS[j]){ok=true;break;}
                  }
                  if(ok){break;}
                }
                if(!ok&&cls.lastIndexOf('_ads_block_data_w',0)===0){ok=true;}
              }
              if(!ok){var pid=p.id||'';if(pid.lastIndexOf('post',0)===0){ok=true;}}
              if(!ok&&p.hasAttribute&&p.hasAttribute('data-ad-view')){ok=true;}
              if(!ok&&p.hasAttribute&&p.hasAttribute('data-post-id')){ok=true;}
              if(ok){return p;}
              p=p.parentElement;
            }
            return null;
          }
          // 1.3.17: точная проверка onclick рекламной ссылки: adsite_ —
          // рекламный переход; связка ads_params + post_ad_data —
          // рекламная разметка VK. По одному ads_params решаем НЕ прятать
          // (встречается вне рекламы).
          function vkbAdIsAdOnclick(el){
            var oc=el.getAttribute('onclick')||'';
            if(!oc){return false;}
            if(oc.indexOf('adsite_')>=0){return true;}
            return oc.indexOf('ads_params')>=0&&oc.indexOf('post_ad_data')>=0;
          }
          function vkbAdScan(root){
            try{
              var scope=(root&&root.querySelectorAll)?root:document;
              var list=[];
              var SEL='a[href],iframe[src],img[src],source[src],video[src]'
                +',[data-mt-video-hls]';
              if(scope!==document&&scope.matches&&scope.matches(SEL)){list.push(scope);}
              var found=scope.querySelectorAll(SEL);
              for(var q=0;q<found.length;q++){list.push(found[q]);}
              for(var k=0;k<list.length;k++){
                var el=list[k];
                var h='';
                if(el.tagName==='A'){h=el.getAttribute('href')||'';}
                else if(el.tagName==='IFRAME'){h=el.getAttribute('src')||'';}
                else{
                  h=el.getAttribute('src')||'';
                  if(!h){h=el.getAttribute('data-mt-video-hls')||'';}
                }
                if(!h||!vkbAdIsAdLink(h)){continue;}
                var card=vkbAdFindCard(el);
                var t=card||el;
                t.style.display='none';
              }
            }catch(e){}
          }
          // 1.3.17: маркерный уровень — карточка с признаком рекламы внутри
          // прячется ЦЕЛИКОМ (раньше пряталась только сама плашка .ads_mark).
          // Ссылка на редиректор может появляться в разметке позже или
          // только на клик — маркеры есть всегда.
          function vkbAdScanMarkers(root){
            try{
              var scope=(root&&root.querySelectorAll)?root:document;
              var list=[];
              if(scope!==document&&scope.matches&&scope.matches(AD_MARK_SEL)){list.push(scope);}
              var found=scope.querySelectorAll(AD_MARK_SEL);
              for(var q=0;q<found.length;q++){list.push(found[q]);}
              for(var k=0;k<list.length;k++){
                var el=list[k];
                if(el.tagName==='A'&&!vkbAdIsAdOnclick(el)){continue;}
                var card=vkbAdFindCard(el);
                var t=card||el;
                t.style.display='none';
              }
            }catch(e){}
          }
          var adQueue=[];var adTimer=null;
          function vkbAdFlush(){
            adTimer=null;
            try{
              var q=adQueue;adQueue=[];
              for(var i=0;i<q.length;i++){vkbAdScan(q[i]);vkbAdScanMarkers(q[i]);}
            }catch(e){}
          }
          function vkbAdObserve(muts){
            try{
              for(var i=0;i<muts.length;i++){
                var m=muts[i];
                if(m.type==='attributes'){
                  // 1.3.17: VK может дописать href/src ПОСЛЕ вставки
                  // карточки — отслеживаем изменение атрибутов тоже
                  var tg=m.target;
                  if(tg&&tg.nodeType===1){adQueue.push(tg);}
                  continue;
                }
                var add=m.addedNodes;
                for(var j=0;j<add.length;j++){
                  var n=add[j];
                  if(n.nodeType!==1||n.tagName==='SCRIPT'||n.tagName==='STYLE'){continue;}
                  adQueue.push(n);
                }
              }
            }catch(e){}
            if(adTimer){clearTimeout(adTimer);}
            adTimer=setTimeout(vkbAdFlush,250);
          }
          // 1.3.17: контрольные проходы по всему документу — страховка от
          // поздней разметки (реклама вставляется после стартового скана
          // в обход наблюдателя).
          function vkbAdSweep(){
            try{vkbAdScan();vkbAdScanMarkers();}catch(e){}
          }
          function vkbAdStart(){
            vkbAdScan();
            vkbAdScanMarkers();
            try{new MutationObserver(vkbAdObserve).observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['href','src','onclick']});}catch(e){}
            setTimeout(vkbAdSweep,1000);
            setTimeout(vkbAdSweep,3000);
            setTimeout(vkbAdSweep,7000);
          }
          if(document.body){vkbAdStart();}
          else{document.addEventListener('DOMContentLoaded',vkbAdStart,{once:true});}
        }catch(e){}
    """.trimIndent()
}
