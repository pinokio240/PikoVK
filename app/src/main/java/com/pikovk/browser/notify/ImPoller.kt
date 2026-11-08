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

    // Имя собеседника для заголовка — лучший совпавший паттерн в окне.
    private fun findName(seg: String): String? {
        for (p in NAME_PATTERNS) {
            val m = p.find(seg)
            if (m != null) {
                val name = m.groupValues[1].trim()
                if (name.length >= 2) return name
            }
        }
        return null
    }

    // Окно текста вокруг диалога без HTML-тегов: имя + превью последнего
    // сообщения, если VK отдал их в разметке страницы.
    private fun snippetFor(peer: String, body: String): String {
        val idx = body.indexOf(peer)
        if (idx < 0) return ""
        val from = maxOf(0, idx - 250)
        val to = minOf(body.length, idx + 250)
        return body.substring(from, to)
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
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
        val seg = snippetFor(peer, body)
        val name = findName(seg)
        var title = "Новое сообщение"
        if (name != null) title = name
        var text = "Непрочитанных: " + count
        if (seg.length >= 10) text = seg
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(400)))
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
            Regex("""class="[^"]*(?:di_name|_name|Name)[^"]*"[^>]*>([^<]{2,64})<"""),
            Regex("""title"\s*:\s*"([^"]{2,64})""")
        )
    }
}
