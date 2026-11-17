package com.pikovk.browser.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pikovk.browser.R
import com.pikovk.browser.browser.UserAgents
import com.pikovk.browser.profile.ProfileManager
import com.pikovk.browser.ui.MainActivity
import com.pikovk.browser.util.Prefs
import com.pikovk.browser.util.SimpleHttp
import kotlin.concurrent.thread

// Foreground-служба фонового опроса VK.
//
// Раз в интервал (по умолчанию 60 с) делает два запроса с cookie-сессией
// АКТИВНОГО профиля:
// 1) страницу «Ответы» VK — реакции / комментарии / ответы (тумблер
//    «Реакции, комментарии, ответы»);
// 2) страницу «Сообщения» (m.vk.ru/im) — личные сообщения (тумблер
//    «Личные сообщения (мессенджер)», 1.3.20, парсер — ImPoller).
// Служба живёт, пока включён хотя бы один тумблер. ЧЕСТНОЕ ОГРАНИЧЕНИЕ:
// у VK нет публичного API для веб-уведомлений, разметку страницы VK иногда
// меняет, поэтому парсеры эвристические. URL-кандидаты и регулярки вынесены
// в константы — их легко поправить. Запрашивается ТОЛЬКО собственная
// сессия пользователя, ничего не собирается.
//
// 1.3.39 (доработка-28): текст уведомлений больше не берётся «как есть»
// из сырого окна ±250. HTML-сущности раскрываются полностью (именованные
// и числовые, с повторными проходами против двойных сущностей), блоки
// <script>/<style> удаляются вместе с телом до снятия тегов; у «Ответов»
// текст сначала ищется СТРУКТУРНО — в JSON-полях рядом с id записи, —
// и только потом берётся окно-фоллбэк, из которого вычищаются служебные
// слова и тайм-штампы страницы («Ответить», «Подписаться», «Сейчас»,
// «2 ч назад» и т.п.). Оформление — как у мобильных браузеров: источник
// «vk.ru» под телом, категория SOCIAL, приоритет DEFAULT.
class NotifyService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null
    private lateinit var imPoller: ImPoller

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        imPoller = ImPoller(this)
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, SERVICE_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(SERVICE_ID, notification)
        }
        startWorker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWorker() // идемпотентно: если поток уже живёт, ничего не делает
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        super.onDestroy()
    }

    private fun startWorker() {
        if (running) return
        running = true
        // CookieManager.getInstance() берём на главном потоке, дальше
        // ссылку безопасно использовать из рабочего потока
        val cm = CookieManager.getInstance()
        worker = thread(name = "vkb-poller", isDaemon = true) { loop(cm) }
    }

    private fun loop(cm: CookieManager) {
        while (running) {
            try {
                if (Prefs.notifyEnabled && hasNetwork()) poll(cm)
                if (Prefs.imNotifyEnabled && hasNetwork()) imPoller.poll(cm)
            } catch (_: Exception) {
                // сеть/разметка могут меняться — молча ждём следующего цикла
            }
            val intervalMs = Prefs.notifyIntervalSec.coerceIn(30, 600) * 1000L
            var slept = 0L
            while (running && slept < intervalMs) {
                Thread.sleep(1000)
                slept += 1000
            }
        }
    }

    private fun hasNetwork(): Boolean {
        val sysService: Any? = getSystemService(Context.CONNECTIVITY_SERVICE)
        if (sysService !is ConnectivityManager) return false // службы нет — сети нет
        val net = sysService.activeNetwork
        if (net == null) return false
        val caps = sysService.getNetworkCapabilities(net)
        if (caps == null) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun poll(cm: CookieManager) {
        val cookie = ProfileManager.activeCookieHeader(cm)
        if (cookie == null) return // нет активной сессии — нечем опрашивать
        for (url in CANDIDATE_URLS) {
            val body = SimpleHttp.get(url, cookie, UserAgents.POLLER_UA, POLL_MAX_BYTES)
            if (body == null) continue // не ответило/ошибка — пробуем следующий URL
            val ids = extractIds(body)
            if (ids.isEmpty()) continue
            processNew(ids, body)
            break
        }
    }

    private fun extractIds(body: String): List<String> {
        val ids = LinkedHashSet<String>()
        for (p in ID_PATTERNS) {
            for (m in p.findAll(body)) ids.add(m.groupValues[1])
        }
        return ids.toList()
    }

    private fun processNew(ids: List<String>, body: String) {
        val seen = loadSeen()
        val fresh = ids.filter { it !in seen }
        if (fresh.isEmpty()) return
        saveSeen((fresh + seen).take(200).toSet())
        var shown = 0
        for (id in fresh) {
            if (shown >= 3) break
            val text = snippetFor(id, body)
            if (text == null) continue // для этого id нет сниппета
            notifyItem(id, text)
            shown++
        }
    }

    // ---------- 1.3.39 (доработка-28): добыча ЧИСТОГО текста ----------

    // 1.3.39 (доработка-28): текст уведомления «Ответов». Сначала СТРУКТУРНО:
    // JSON-поля text/title/desc/body/summary в окне вокруг id записи
    // (значение — JSON-строка). Структурные поля не дали результата —
    // фоллбэк: прежнее окно ±250, но уже через cleanWindow и вычистку
    // NOISE (служебные слова, тайм-штампы). Короче 10 символов после
    // чистки — null, не показываем (как раньше).
    private fun snippetFor(id: String, body: String): String? {
        val idx = body.indexOf(id)
        if (idx < 0) return null
        for (p in JSON_TEXT_PATTERNS) {
            val from = maxOf(0, idx - JSON_TEXT_WINDOW)
            val to = minOf(body.length, idx + JSON_TEXT_WINDOW)
            val seg = body.substring(from, to)
            val m = nearestIn(p, seg, idx - from) ?: continue
            var raw = m.groupValues[1]
            if (raw.contains('\\')) raw = jsonUnescape(raw)
            val text = cleanText(raw)
            if (text.length >= 10) return text
            // короткое/мусорное поле — пробуем следующий паттерн, затем окно
        }
        val wFrom = maxOf(0, idx - SNIPPET_WINDOW)
        val wTo = minOf(body.length, idx + SNIPPET_WINDOW)
        val cleaned = cleanNoise(cleanWindow(body, wFrom, wTo))
        return if (cleaned.length < 10) null else cleaned
    }

    // 1.3.39 (доработка-28): вычистка NOISE из окна-фоллбэка — на границах
    // слов. Lookaround вместо \b: в Java-регулярках кириллица по умолчанию
    // не «словесный» символ, и \b вокруг русских слов не срабатывает.
    // После удаления склеиваем пробелы и «висячую» пунктуацию.
    private fun cleanNoise(s: String): String {
        var t = NOISE.replace(s, " ")
        t = WHITESPACE.replace(t, " ").trim()
        t = HANGING_PUNCT.replace(t) { m -> m.groupValues[1] }
        return t.trim()
    }

    // 1.3.39 (доработка-28): окно сырого HTML → чистый текст. Сначала
    // <script>/<style> убираются ВМЕСТЕ С СОДЕРЖИМЫМ (иначе JS/CSS попадали
    // в «текст» уведомления), и только потом снимаются теги, раскрываются
    // сущности, схлопываются пробелы.
    private fun cleanWindow(body: String, from: Int, to: Int): String {
        var s = body.substring(from, to)
        s = SCRIPT_BLOCK.replace(s, " ")
        s = STYLE_BLOCK.replace(s, " ")
        s = TAGS.replace(s, " ")
        s = htmlUnescape(s)
        return WHITESPACE.replace(s, " ").trim()
    }

    // 1.3.39 (доработка-28): финальная чистка добытого значения — снять
    // теги (JSON-значения могут содержать <b> и т.п.), раскрыть сущности,
    // схлопнуть пробелы.
    private fun cleanText(s: String): String {
        var t = TAGS.replace(s, " ")
        t = htmlUnescape(t)
        return WHITESPACE.replace(t, " ").trim()
    }

    // 1.3.39 (доработка-28): раскрытие ВСЕХ распространённых HTML-сущностей —
    // именованных и числовых (&#NNN; / &#xHHH;). До 3 проходов, чтобы
    // «съедать» двойные сущности: «&amp;amp;» → «&amp;» → «&». Ограничение
    // числа проходов — страховка от вечного цикла на мусорной строке.
    private fun htmlUnescape(s: String): String {
        var out = s
        repeat(3) {
            val next = ENTITY_RE.replace(out) { m -> decodeEntity(m.value) ?: m.value }
            if (next == out) return out // нечего декодировать — выходим раньше
            out = next
        }
        return out
    }

    // 1.3.39 (доработка-28): одна сущность «&…;» → символ(ы). Числовые
    // (десятичные и шестнадцатеричные) и именованные из таблицы; неизвестное
    // возвращается как есть (null = без замены).
    private fun decodeEntity(e: String): String? {
        if (e.startsWith("&#") && e.endsWith(";")) {
            val hex = e.length > 4 && (e[2] == 'x' || e[2] == 'X')
            val digits = e.substring(if (hex) 3 else 2, e.length - 1)
            val cp = if (hex) digits.toIntOrNull(16) else digits.toIntOrNull(10)
            if (cp == null || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return null
            // управляющие символы (кроме \t \n \r) → пробел, а не невидимый мусор
            if (cp < 0x20 && cp != 9 && cp != 10 && cp != 13) return " "
            return try {
                String(Character.toChars(cp))
            } catch (_: Exception) {
                null
            }
        }
        return NAMED_ENTITIES[e]
    }

    // 1.3.39 (доработка-28): JSON-раскавычка значения из JSON-состояния:
    // \n/\r/\t → пробел, \" → ", \\ → \, \uXXXX → символ (суррогатные пары
    // сшиваются в один символ — эмодзи в текстах обычное дело).
    private fun jsonUnescape(s: String): String {
        if (!s.contains('\\')) return s // экранирования нет — быстрее выйти
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            if (i + 1 >= s.length) {
                sb.append(c) // «\» в самом конце — мусор, оставляем
                break
            }
            when (s[i + 1]) {
                'n', 'r', 't' -> { sb.append(' '); i += 2 } // переводы строки — в пробел
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'u' -> i = appendUnicodeEscape(s, i, sb)
                else -> { sb.append(s[i + 1]); i += 2 } // \b, \f и прочее — по-простому
            }
        }
        return sb.toString()
    }

    // 1.3.39 (доработка-28): обработка \uXXXX для jsonUnescape (возвращает
    // новую позицию в строке).
    private fun appendUnicodeEscape(s: String, start: Int, sb: StringBuilder): Int {
        val cp1 = if (start + 6 <= s.length) s.substring(start + 2, start + 6).toIntOrNull(16) else null
        // старшая половина суррогатной пары + сразу \uDC00… — сшиваем
        if (cp1 != null && cp1 in 0xD800..0xDBFF && start + 12 <= s.length &&
            s[start + 6] == '\\' && s[start + 7] == 'u'
        ) {
            val cp2 = s.substring(start + 8, start + 12).toIntOrNull(16)
            if (cp2 != null && cp2 in 0xDC00..0xDFFF) {
                sb.appendCodePoint(0x10000 + ((cp1 - 0xD800) shl 10) + (cp2 - 0xDC00))
                return start + 12
            }
        }
        if (cp1 != null) {
            try {
                sb.append(String(Character.toChars(cp1)))
            } catch (_: Exception) {
                sb.append(' ') // «рваная» суррогатная пара — пробел вместо неё
            }
            return start + 6
        }
        sb.append('u') // не шестнадцатеричное — оставляем букву как было
        return start + 2
    }

    // 1.3.39 (доработка-28): ближайшее к center совпадение в окне — чтобы
    // не взять поле СОСЕДНЕЙ записи, когда в окне их несколько.
    private fun nearestIn(re: Regex, seg: String, center: Int): MatchResult? {
        var best: MatchResult? = null
        var bestDist = Int.MAX_VALUE
        for (m in re.findAll(seg)) {
            val d = Math.abs(m.range.first - center)
            if (d < bestDist) {
                best = m
                bestDist = d
            }
        }
        return best
    }

    private fun classify(text: String): Pair<String, String> = when {
        text.contains("оценил", true) || text.contains("оценила", true) ||
            text.contains("реакц", true) || text.contains("нравится", true) ->
            CHANNEL_REACTIONS to "Новая реакция"
        text.contains("коммент", true) ->
            CHANNEL_COMMENTS to "Новый комментарий"
        text.contains("ответил", true) || text.contains("ответила", true) ||
            text.contains("упомянул", true) || text.contains("упомянула", true) ->
            CHANNEL_REPLIES to "Новый ответ"
        else -> CHANNEL_REPLIES to "Новое уведомление VK"
    }

    private fun notifyItem(id: String, text: String) {
        val (channel, title) = classify(text)
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400)))
            // 1.3.39 (доработка-28): оформление «как у мобильных браузеров» —
            // Chrome показывает под телом уведомления сайт-источник.
            .setSubText("vk.ru")
            .setCategory(NotificationCompat.CATEGORY_SOCIAL) // реакции/комментарии/ответы
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nmService: Any? = getSystemService(Context.NOTIFICATION_SERVICE)
        if (nmService !is NotificationManager) return // службы уведомлений нет — молча выходим
        nmService.notify(id.hashCode(), n)
    }

    private fun buildServiceNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Текст служебного уведомления честно перечисляет, за чем следим:
        // тумблеров два (1.3.20), служба живёт пока включён хотя бы один.
        val watch = ArrayList<String>()
        if (Prefs.notifyEnabled) watch.add(getString(R.string.notif_watch_answers))
        if (Prefs.imNotifyEnabled) watch.add(getString(R.string.notif_watch_messages))
        if (watch.isEmpty()) watch.add(getString(R.string.notif_watch_answers)) // fail-open
        val text = getString(R.string.notif_service_prefix) + " " + watch.joinToString(", ")
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun loadSeen(): Set<String> {
        val stored: Set<String>? = getSharedPreferences(SEEN_FILE, MODE_PRIVATE)
            .getStringSet("seen", emptySet())
        if (stored == null) return emptySet()
        return stored
    }

    private fun saveSeen(s: Set<String>) {
        getSharedPreferences(SEEN_FILE, MODE_PRIVATE)
            .edit().clear().putStringSet("seen", s).apply()
    }

    companion object {
        const val SERVICE_ID = 42
        const val CH_SERVICE = "vkb_service"
        const val CHANNEL_REACTIONS = "vkb_reactions"
        const val CHANNEL_COMMENTS = "vkb_comments"
        const val CHANNEL_REPLIES = "vkb_replies"
        const val CHANNEL_MESSAGES = "vkb_messages" // 1.3.20: личные сообщения
        private const val SEEN_FILE = "vkb_notify_seen"
        private const val POLL_MAX_BYTES = 2_000_000

        // Жива ли служба сейчас (1.3.10): её foreground уже держит процесс,
        //  и отдельная служба воспроизведения не нужна.
        @Volatile
        var isRunning: Boolean = false
            private set

        // Кандидаты страницы «Ответы»: берём первый, отдающий элементы.
        val CANDIDATE_URLS = listOf(
            "https://m.vk.ru/notifications",
            "https://m.vk.ru/feed?section=notifications",
            "https://vk.ru/notifications"
        )

        private val ID_PATTERNS = listOf(
            Regex("""data-notif-id="(\d+)"""),
            Regex("""id="notif_(\d+)"""),
            Regex("""notification_id["']?\s*[:=]\s*["']?(\d+)""")
        )

        // ---------- 1.3.39 (доработка-28): паттерны текста и очистка ----------

        // Радиус окна-фоллбэка вокруг id записи (как раньше ±250, вынесено).
        private const val SNIPPET_WINDOW = 250

        // Радиус JSON-поиска вокруг id — JSON-блок записи может быть
        // заметно длиннее текстового окна.
        private const val JSON_TEXT_WINDOW = 600

        // 1.3.39 (доработка-28): JSON-поля с текстом уведомления в состоянии
        // записи/страницы (ищутся рядом с id). Значение — JSON-строка:
        // после извлечения нужна jsonUnescape.
        private val JSON_TEXT_PATTERNS = listOf(
            Regex(""""(?:text|title|desc|body|summary)"\s*:\s*"((?:[^"\\]|\\.){5,300})"""")
        )

        // 1.3.39 (доработка-28): служебные слова и тайм-штампы страницы
        // «Ответов», попадающие в окно-фоллбэк (пункты меню, кнопки,
        // «Сейчас», «2 ч назад» и т.п.). Разметка VK меняется — список
        // вынесен в константу, дополнять легко. Фразы («Показать ещё»)
        // стоят РАНЬШЕ своих частей («Ещё»), иначе от фразы остаётся огрызок.
        // 1.3.39 (фикс сборки): RegexOption.UNICODE_CASE в Kotlin этого
        // проекта нет — регистронезависимость ключевых слов задаём сами:
        // каждая буква оборачивается в класс из двух регистров («[сС]»),
        // см. ci() ниже (для кириллицы и латиницы пара ровно из двух букв).
        private val NOISE_WORDS = listOf(
            "Сейчас", "Показать ещё", "Показать перевод", "Подписаться",
            "Отписаться", "Ответить", "Перевести", "Написать", "Жалоба",
            "Скрыть", "Ещё"
        )

        // Тайм-штампы: «2 минуты назад», «5 ч назад», «1 мин. назад», а
        // также даты «17.11.2026». Слова — через ci() (регистронезависимо).
        // Падежные суффиксы — КАЖДЫЙ вариант отдельной веткой: «часа» —
        // [аА], «часов» — [оО][вВ] (квантор на группе (?:[аА][оО][вВ])?
        // не покрыл бы одиночное «а»: ? относится ко всей группе),
        // «дня» — [яЯ], «дней» — [еЕ][йЙ].
        private val NOISE_TIME =
            "\\d{1,2}\\s?(?:" + ci("минут") + "(?:[уы])?|" + ci("мин") + "|" +
            ci("секунд") + "(?:[уы])?|" + ci("сек") + "|" + ci("час") +
            "(?:[аоАО][вВ]|[аА])?|" + ci("ч") + "|" + ci("дн") + "(?:[яЯ]|[еЕ][йЙ])?)" +
            "\\.?\\s?" + ci("назад") + "|" + "(?:\\d{1,2}\\.){2}\\d{2,4}"

        // 1.3.39 (доработка-28): вычистка шума — на границах слов. Lookaround
        // вместо \b: в Java-регулярках кириллица по умолчанию не «словесный»
        // символ, и \b вокруг русских слов не срабатывает. В классы границ
        // входят ОБА регистра — слова в списке регистронезависимы.
        // val, не private: тем же списком шума пользуется ImPoller
        // (фоллбэк его окна чистится так же — доработка-28).
        val NOISE = Regex(
            "(?<![а-яёa-zА-ЯЁA-Z0-9_])(" +
                NOISE_WORDS.joinToString("|") { ci(it) } + "|" + NOISE_TIME +
                ")(?![а-яёa-zА-ЯЁA-Z0-9_])"
        )

        // 1.3.39 (фикс сборки): слово → регистронезависимый фрагмент шаблона:
        // каждая буква с обоими регистрами оборачивается в класс «[сС]»,
        // остальные символы литеральны (метасимволов в списке нет, но
        // экранируем на всякий случай). Так обходится отсутствие
        // RegexOption.UNICODE_CASE: регистр задаётся классами, а не флагом.
        private fun ci(word: String): String = buildString {
            for (c in word) {
                val lo = Character.toLowerCase(c)
                val up = Character.toUpperCase(c)
                if (lo != up) {
                    append('[').append(lo).append(up).append(']')
                } else if (c in "\\^$.|?*+()[]{}") {
                    append('\\').append(c)
                } else {
                    append(c)
                }
            }
        }

        // «Висячая» пунктуация после удалённых слов: «запись ,» → «запись,».
        private val HANGING_PUNCT = Regex("""\s+([,.:;!?])""")

        // 1.3.39 (доработка-28): блоки, убираемые ЦЕЛИКОМ (с телом) ДО снятия
        // тегов; сами теги; пробельные последовательности.
        private val SCRIPT_BLOCK = Regex("""(?is)<script\b[^>]*>.*?</script>""")
        private val STYLE_BLOCK = Regex("""(?is)<style\b[^>]*>.*?</style>""")
        private val TAGS = Regex("""<[^>]+>""")
        private val WHITESPACE = Regex("""\s+""")

        // 1.3.39 (доработка-28): HTML-сущности. Именованные перечислены
        // ЯВНО (жадный «&[a-zA-Z]+;» захватил бы «&amp;amp;» целиком как
        // неизвестное имя «ampamp» и не декодировал бы его вовсе); числовые —
        // десятичные и шестнадцатеричные.
        private val ENTITY_RE = Regex(
            """&#(x[0-9a-fA-F]{1,6}|\d{1,7});|&(?:lt|gt|quot|apos|nbsp|laquo|raquo|mdash|ndash|hellip|amp);"""
        )

        // Таблица именованных сущностей (остальные — числовые). &nbsp;
        // → обычный пробел, чтобы схлопываться с остальными пробелами.
        private val NAMED_ENTITIES = mapOf(
            "&lt;" to "<",
            "&gt;" to ">",
            "&quot;" to "\"",
            "&apos;" to "'",
            "&nbsp;" to " ",
            "&laquo;" to "«",
            "&raquo;" to "»",
            "&mdash;" to "—",
            "&ndash;" to "–",
            "&hellip;" to "…",
            "&amp;" to "&"
        )

        fun ensureChannels(ctx: Context) {
            val nmService: Any? = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
            if (nmService !is NotificationManager) return // не на чем создавать каналы
            fun channel(id: String, name: String, importance: Int) =
                nmService.createNotificationChannel(NotificationChannel(id, name, importance))
            channel(CH_SERVICE, ctx.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_MIN)
            channel(CHANNEL_REACTIONS, ctx.getString(R.string.notif_channel_reactions), NotificationManager.IMPORTANCE_DEFAULT)
            channel(CHANNEL_COMMENTS, ctx.getString(R.string.notif_channel_comments), NotificationManager.IMPORTANCE_DEFAULT)
            channel(CHANNEL_REPLIES, ctx.getString(R.string.notif_channel_replies), NotificationManager.IMPORTANCE_DEFAULT)
            channel(CHANNEL_MESSAGES, ctx.getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_DEFAULT)
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, NotifyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NotifyService::class.java))
        }
    }
}
