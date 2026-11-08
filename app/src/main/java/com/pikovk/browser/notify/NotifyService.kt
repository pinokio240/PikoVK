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

    private fun snippetFor(id: String, body: String): String? {
        val idx = body.indexOf(id)
        if (idx < 0) return null
        val from = maxOf(0, idx - 250)
        val to = minOf(body.length, idx + 250)
        val s = body.substring(from, to)
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (s.length < 10) null else s
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
