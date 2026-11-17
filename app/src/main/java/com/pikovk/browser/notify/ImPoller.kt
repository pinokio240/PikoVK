package com.pikovk.browser.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import com.pikovk.browser.R
import com.pikovk.browser.browser.UserAgents
import com.pikovk.browser.profile.ProfileManager
import com.pikovk.browser.ui.MainActivity
import com.pikovk.browser.util.SimpleHttp

// Опрос личных сообщений (1.3.20). Работает в общей службе NotifyService:
// раз в интервал опроса скачивает страницу «Сообщения» (m.vk.ru/im) с
// cookie-сессией АКТИВНОГО профиля, находит диалоги с непрочитанными и
// показывает уведомления в канале «Сообщения» (важность DEFAULT —
// появляется и в шторке, и на экране блокировки).
//
// КАК ОТЛИЧАЕМ «новое» от «уже показанного»: в настройках хранится
// последний известный счётчик непрочитанных по каждому диалогу.
// Уведомляем только когда счётчик ВЫРОС; прочитали в приложении —
// счётчик упал, состояние обновляется молча, без спама.
//
// ЧЕСТНОЕ ОГРАНИЧЕНИЕ (как и у «Ответов»): у VK нет публичного API для
// веб-уведомлений, разметка страницы может меняться — парсер эвристический,
// URL-кандидаты и регулярки вынесены в константы ниже (CANDIDATE_URLS,
// PEER_PATTERNS, COUNT_PATTERNS, NAME_PATTERNS) — их легко поправить.
// Постоянное соединение мессенджера (long-poll) сознательно не используется:
// периодический лёгкий GET дешевле и предсказуемее для батареи.
// Запрашивается ТОЛЬКО собственная сессия пользователя, ничего не собирается.
//
// 1.3.39 (доработка-28): имя и превью сообщения добываются СТРУКТУРНО —
// разметка диалога m.vk.ru (nim-dialog--name / nim-dialog--preview) и
// JSON-состояние страницы; сырое окно ±250 осталось только фоллбэком и
// чистится через общий конвейер (script/style целиком, все сущности HTML,
// схлопывание пробелов). Раньше имя искали в тексте УЖЕ БЕЗ тегов —
// паттерн class="…" там не встречался, поэтому заголовок почти всегда был
// «Новое сообщение», а «текст» — мусором из окна. Оформление — как у
// мобильных браузеров: источник «vk.ru» под телом, категория MESSAGE,
// приоритет DEFAULT, счётчик непрочитанных setNumber.
class ImPoller(private val context: Context) {

    // Диалог с непрочитанными: идентификатор peer (как в ссылке /im?sel=X)
    // и счётчик непрочитанных сообщений.
    private class PeerUnread(val peer: String, val count: Int)

    fun poll(cm: CookieManager) {
        val cookie = ProfileManager.activeCookieHeader(cm)
        if (cookie == null) return // нет активной сессии — нечем опрашивать
        for (url in CANDIDATE_URLS) {
            val body = SimpleHttp.get(url, cookie, UserAgents.POLLER_UA, MAX_BYTES)
            if (body == null) continue // не ответило/ошибка — пробуем следующий URL
            processDialogs(parseDialogs(body), body)
            break // страница получена — остальные кандидаты не нужны
        }
    }

    // ---------- разбор страницы ----------

    private fun parseDialogs(body: String): List<PeerUnread> {
        val found = LinkedHashMap<String, Int>()
        for (p in PEER_PATTERNS) {
            for (m in p.findAll(body)) {
                val peer = m.groupValues[1]
                // Счётчик непрочитанных ищем В ОКНЕ ПОСЛЕ совпадения —
                // он относится к тому же блоку диалога в разметке.
                val from = m.range.last
                val to = minOf(body.length, from + COUNT_WINDOW)
                val seg = body.substring(from, to)
                val count = firstCount(seg)
                if (count <= 0) continue // диалог прочитан — не наш случай
                val prev = found[peer]
                if (prev == null || count > prev) found[peer] = count
            }
        }
        val list = ArrayList<PeerUnread>()
        for ((peer, count) in found) {
            list.add(PeerUnread(peer, count))
        }
        return list
    }

    private fun firstCount(seg: String): Int {
        for (p in COUNT_PATTERNS) {
            val m = p.find(seg)
            if (m != null) {
                val n = m.groupValues[1].toIntOrNull()
                if (n != null) return n
            }
        }
        return -1 // счётчика рядом нет — диалог не считаем непрочитанным
    }

    // ---------- 1.3.39 (доработка-28): добыча ЧИСТОГО текста ----------

    // 1.3.39 (доработка-28): имя собеседника — СТРУКТУРНО, в сыром окне
    // вокруг peer (старый вариант искал class="…" в тексте уже БЕЗ тегов и
    // потому почти никогда не срабатывал). Берём совпадение, ближайшее к
    // peer, — оно относится к этому диалогу, а не к соседнему.
    private fun findName(peer: String, body: String): String? {
        val idx = body.indexOf(peer)
        if (idx < 0) return null
        val from = maxOf(0, idx - NAME_WINDOW)
        val to = minOf(body.length, idx + NAME_WINDOW)
        val seg = body.substring(from, to)
        val center = idx - from
        for (p in NAME_PATTERNS) {
            val m = nearestIn(p, seg, center) ?: continue
            val name = cleanText(m.groupValues[1])
            if (name.length >= 2) return name
        }
        return null
    }

    // 1.3.39 (доработка-28): превью последнего сообщения — сначала
    // разметка диалога (nim-dialog--preview), затем JSON-состояние страницы.
    // Ничего не совпало — null, вызывающий уходит в окно-фоллбэк.
    private fun findPreview(peer: String, body: String): String? {
        val idx = body.indexOf(peer)
        if (idx < 0) return null
        val from = maxOf(0, idx - PREVIEW_WINDOW)
        val to = minOf(body.length, idx + PREVIEW_WINDOW)
        val seg = body.substring(from, to)
        val center = idx - from
        for (p in PREVIEW_PATTERNS) { // HTML-разметка: обычный текст
            val m = nearestIn(p, seg, center) ?: continue
            val preview = cleanText(m.groupValues[1])
            if (preview.length >= 2) return preview
        }
        for (p in PREVIEW_JSON_PATTERNS) { // JSON: значение — JSON-строка
            val m = nearestIn(p, seg, center) ?: continue
            var raw = m.groupValues[1]
            if (raw.contains('\\')) raw = jsonUnescape(raw)
            val preview = cleanText(raw)
            if (preview.length >= 2) return preview
        }
        return null
    }

    // 1.3.39 (доработка-28): фоллбэк — окно вокруг peer, но теперь через
    // cleanWindow (script/style целиком, сущности, пробелы). Раньше отсюда
    // же искали имя — теперь имя добывается структурно (findName).
    private fun snippetFor(peer: String, body: String): String {
        val idx = body.indexOf(peer)
        if (idx < 0) return ""
        val from = maxOf(0, idx - SNIPPET_WINDOW)
        val to = minOf(body.length, idx + SNIPPET_WINDOW)
        return cleanWindow(body, from, to)
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
    // сшиваются в один символ — эмодзи в сообщениях обычное дело).
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
    // не взять поле СОСЕДНЕГО диалога, когда в окне их несколько.
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

    // ---------- состояние и уведомления ----------

    private fun processDialogs(dialogs: List<PeerUnread>, body: String) {
        val stored = loadCounts()
        val next = HashMap<String, Int>()
        var shown = 0
        for (d in dialogs) {
            next[d.peer] = d.count // состояние храним для всех, даже если не показываем
            if (shown >= MAX_PER_CYCLE) continue // не спамим: не более 3 за цикл
            val prevStored = stored[d.peer]
            var prev = 0
            if (prevStored != null) prev = prevStored
            if (d.count > prev) {
                notifyPeer(d.peer, d.count, body)
                shown++
            }
        }
        saveCounts(next)
    }

    private fun notifyPeer(peer: String, count: Int, body: String) {
        // 1.3.39 (доработка-28): имя и превью — СТРУКТУРНО; сырое окно —
        // только фоллбэк, если разметка/JSON не совпали.
        val title = findName(peer, body) ?: "Новое сообщение"
        var text = "Непрочитанных: " + count
        var bigText = text
        val preview = findPreview(peer, body)
        if (preview != null) {
            text = preview
            // Большой текст: количество и превью (как у мессенджеров);
            // для одного сообщения префикс не нужен.
            bigText = if (count > 1) "Сообщений ($count): $preview" else preview
        } else {
            val seg = snippetFor(peer, body)
            if (seg.length >= 10) {
                text = seg
                bigText = seg
            }
        }
        val open = Intent(context, MainActivity::class.java)
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Тап по уведомлению открывает сразу мессенджер, а не ленту.
        open.putExtra(MainActivity.EXTRA_OPEN_URL, MESSAGES_URL)
        val pi = PendingIntent.getActivity(
            context, PI_REQUEST_CODE, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(context, NotifyService.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText.take(400)))
            // 1.3.39 (доработка-28): оформление «как у мобильных браузеров» —
            // источник под телом, категория, приоритет, счётчик непрочитанных.
            .setSubText("vk.ru")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setNumber(count)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val nmService: Any? = context.getSystemService(Context.NOTIFICATION_SERVICE)
        if (nmService !is NotificationManager) return // службы уведомлений нет — молча выходим
        nmService.notify(("im_" + peer).hashCode(), n)
    }

    // ---------- хранение счётчиков (SharedPreferences, пары peer=счётчик) ----------

    private fun loadCounts(): Map<String, Int> {
        val out = HashMap<String, Int>()
        val stored: Set<String>? = context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
            .getStringSet("counts", emptySet())
        if (stored == null) return out
        for (entry in stored) {
            val i = entry.indexOf('=')
            if (i <= 0) continue
            val n = entry.substring(i + 1).toIntOrNull()
            if (n != null) out[entry.substring(0, i)] = n
        }
        return out
    }

    private fun saveCounts(counts: Map<String, Int>) {
        val capped = HashSet<String>()
        var added = 0
        for (peer in counts.keys) {
            if (added >= MAX_TRACKED) break
            val c = counts[peer]
            if (c != null) {
                capped.add(peer + "=" + c)
                added++
            }
        }
        context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
            .edit().clear().putStringSet("counts", capped).apply()
    }

    companion object {
        private const val STATE_FILE = "vkb_im_counts"
        private const val COUNT_WINDOW = 600
        private const val MAX_PER_CYCLE = 3
        private const val MAX_TRACKED = 200
        private const val MAX_BYTES = 2_000_000
        private const val PI_REQUEST_CODE = 2001 // отличный от «Ответов», интенты разные

        // Куда ведёт тап по уведомлению (и что парсим в первую очередь).
        const val MESSAGES_URL = "https://m.vk.ru/im"

        // Кандидаты страницы «Сообщений»: сначала мобильная, затем полная.
        val CANDIDATE_URLS = listOf(
            "https://m.vk.ru/im",
            "https://vk.ru/im"
        )

        // Где искать peer диалога: ссылки списка диалогов (sel=123 —
        // пользователь/сообщество, sel=c123 — беседа) и JSON-состояние
        // страницы (peer_id / peerId). Кавычку перед ключом в паттерн
        // не включаем — raw-строки не начинаются и не кончаются кавычкой.
        private val PEER_PATTERNS = listOf(
            Regex("""href="/im\?sel=(c?-?\d+)"""),
            Regex("""peer_id"\s*:\s*(-?\d+)"""),
            Regex("""peerId"\s*:\s*(-?\d+)""")
        )

        // Счётчик непрочитанных рядом с диалогом: плашка класса *unread*,
        // поля JSON unread_count/unread, атрибут data-count.
        private val COUNT_PATTERNS = listOf(
            Regex("""class="[^"]*[uU]nread[^"]*"[^>]*>\s{0,20}(\d{1,3})\s{0,20}<"""),
            Regex("""unread_count"\s*:\s*(\d{1,3})"""),
            Regex("""unread"\s*:\s*(\d{1,3})"""),
            Regex("""data-count="(\d{1,3})""")
        )

        // Имя собеседника — только для заголовка уведомления; не совпало —
        // будет нейтральное «Новое сообщение».
        private val NAME_PATTERNS = listOf(
            // 1.3.39 (доработка-28): актуальная разметка списка диалогов
            // m.vk.ru (nim-dialog--name) — самая надёжная, стоит первой.
            Regex("""class="[^"]*nim-dialog--name[^"]*"[^>]*>([^<]{2,64})<"""),
            Regex("""class="[^"]*(?:di_name|_name|Name)[^"]*"[^>]*>([^<]{2,64})<"""),
            Regex("""title"\s*:\s*"([^"]{2,64})""")
        )

        // ---------- 1.3.39 (доработка-28): паттерны текста и очистка ----------

        // Радиусы окон: структурный поиск имени/превью вокруг peer и
        // текстовый фоллбэк (как раньше ±250, вынесено).
        private const val NAME_WINDOW = 500
        private const val PREVIEW_WINDOW = 600
        private const val SNIPPET_WINDOW = 250

        // 1.3.39 (доработка-28): превью последнего сообщения. Сначала
        // разметка m.vk.ru (nim-dialog--preview), затем JSON-состояние
        // страницы (значение — JSON-строка, после извлечения jsonUnescape).
        private val PREVIEW_PATTERNS = listOf(
            Regex("""class="[^"]*nim-dialog--preview[^"]*"[^>]*>([^<]{2,200})<""")
        )

        private val PREVIEW_JSON_PATTERNS = listOf(
            Regex(""""(?:preview|snippet|last_msg_text|message_text)"\s*:\s*"((?:[^"\\]|\\.){5,300})"""")
        )

        // Блоки, убираемые ЦЕЛИКОМ (с телом) ДО снятия тегов; сами теги;
        // пробельные последовательности.
        private val SCRIPT_BLOCK = Regex("""(?is)<script\b[^>]*>.*?</script>""")
        private val STYLE_BLOCK = Regex("""(?is)<style\b[^>]*>.*?</style>""")
        private val TAGS = Regex("""<[^>]+>""")
        private val WHITESPACE = Regex("""\s+""")

        // HTML-сущности. Именованные перечислены ЯВНО (жадный
        // «&[a-zA-Z]+;» захватил бы «&amp;amp;» целиком как неизвестное имя
        // «ampamp» и не декодировал бы его вовсе); числовые — десятичные
        // и шестнадцатеричные.
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
    }
}
