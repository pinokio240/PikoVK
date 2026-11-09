package com.pikovk.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat as MediaNotif
import com.pikovk.browser.R
import com.pikovk.browser.music.MusicActivity
import com.pikovk.browser.ui.MainActivity

// Foreground-служба фонового воспроизведения (1.3.10) + медиашторка (1.3.21).
//
// ПРИЧИНА («нет фонового воспроизведения аудио и видео»): WebView играет
// звук, только пока процесс приложения жив и не заморожен. Когда приложение
// сворачивается или гаснет экран, Android переводит процесс в кэш: начиная
// с Android 12 кэшированные процессы замораживаются, а «фантомный» менеджер
// убивает дочерние процессы приложения — включая рендерер WebView. Звук
// обрывается. Наша MainActivity не зовёт webView.onPause(), поэтому на экране
// (в foreground) всё играет — ломается именно фон.
//
// РЕШЕНИЕ: пока приложение не на экране, работает служба переднего плана
// типа mediaPlayback — процесс получает приоритет службы, не замораживается
// и не чистится, и WebView продолжает воспроизведение.
//
// 1.3.21 — переработанный плеер (по образцу VK Next): служба получила
// MediaSession и уведомление MediaStyle — в шторке и на экране блокировки
// виден трек (исполнитель — название) и работают кнопки «назад / пауза /
// вперёд». Команды уходят в страницу ВК: PlaybackService.attach хранит
// ссылку на WebView из MainActivity, колбэки зовут evaluateJavascript
// window.__vkbPlayerCmd (секция PLAYER_JS в ExtensionEngine) — та кликает
// штатные кнопки плеера VK. Метаданные идут обратно по мосту
// VkbBridge.playerState → updateMeta → MediaSession.
//
// Жизненный цикл: запуск — MainActivity.onStop (приложение ушло с экрана),
// остановка — MainActivity.onStart / onDestroy. Если уже работает опрос
// уведомлений (NotifyService), его foreground-приоритет достаточно держит
// процесс, и отдельная служба не запускается (меньше уведомлений).
class PlaybackService : Service() {

    private lateinit var session: MediaSessionCompat

    // 1.3.23: собственный частичный wake-lock службы. Раньше процессор для
    // фоновой музыки держал ТОЛЬКО MainActivity.onStop (acquire при уходе
    // с экрана), но вкладка «Музыка» живёт ПОВЕРХ MainActivity, и любой
    // возврат браузера на экран (тап по медиа-карточке в шторке/на
    // локскрине, пересоздание активити ROM-ом) отпускал ту блокировку.
    // Служба теперь держит CPU сама, ровно пока живёт, независимо от того,
    // что происходит с активити.
    private var cpuLock: PowerManager.WakeLock? = null

    // 1.3.23: страховка от тихой «демотации» службы. Полевые репорты 2025-2026:
    // часть прошивок через десятки минут сама снимает у FGS статус переднего
    // плана, ни о чём не сообщая — процесс падает в кэш, заморозка (Android
    // 12+) глушит звук, а повторный подъём из фона уже запрещён. Лечится
    // дёшево: пока играет музыка, раз в минуту ЗАНОВО утверждаем
    // startForeground — это не «старт из фона» (служба уже жива), система
    // такое разрешает. Fail-open: любая ошибка гасится — звук важнее строгости.
    private val handler = Handler(Looper.getMainLooper())

    private val keepAlive: Runnable = object : Runnable {
        override fun run() {
            val self = instance
            if (self == null) return // служба уничтожена — цикл больше не нужен
            if (lastPlaying) {
                assertForeground()
            }
            handler.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel(this)
        // wake-lock службы (см. комментарий у поля)
        val pmService: Any? = getSystemService(Context.POWER_SERVICE)
        if (pmService is PowerManager) {
            val wl = pmService.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PikoVK:playbackSvc")
            wl.setReferenceCounted(false)
            wl.acquire()
            cpuLock = wl
        }
        session = MediaSessionCompat(this, "PikoVK")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                dispatch("play")
            }

            override fun onPause() {
                dispatch("pause")
            }

            override fun onSkipToNext() {
                dispatch("next")
            }

            override fun onSkipToPrevious() {
                dispatch("prev")
            }
        })
        session.isActive = true
        applyMeta()
        assertForeground()
        handler.postDelayed(keepAlive, KEEP_ALIVE_MS)
    }

    // Заново утверждаем статус переднего плана (см. keepAlive). Ошибки —
    // например, редкий отказ системы — глушим: служба и так живёт.
    private fun assertForeground() {
        runCatching {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this, SERVICE_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(SERVICE_ID, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Кнопки уведомления: экстра cmd → в страницу ВК. Пустой intent
        // (перезапуск системой) — просто продолжаем держать foreground.
        if (intent == null) {
            return START_STICKY
        }
        val cmd: String? = intent.getStringExtra(EXTRA_CMD)
        if (cmd != null) {
            dispatch(cmd)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 1.3.23: гасим цикл страховки вместе со службой
        handler.removeCallbacks(keepAlive)
        runCatching {
            session.isActive = false
            session.release()
        }
        // свой wake-lock службы гасим вместе со службой
        val held = cpuLock
        if (held != null) {
            if (held.isHeld) held.release()
            cpuLock = null
        }
        instance = null
        super.onDestroy()
    }

    // Команда плееру: клик по штатной кнопке VK через PLAYER_JS. WebView
    // может быть уже уничтожен (закрыли приложение) — молча выходим.
    private fun dispatch(cmd: String) {
        val wv = linkedWeb
        if (wv == null) return
        wv.post {
            runCatching {
                wv.evaluateJavascript(
                    "window.__vkbPlayerCmd&&window.__vkbPlayerCmd('$cmd');",
                    null
                )
            }
        }
    }

    // Метаданные → MediaSession + обновление уведомления (иконка/действия
    // зависят от состояния «играет/пауза»).
    private fun applyMeta() {
        val display = if (lastArtist.isBlank()) lastTitle else lastArtist + " — " + lastTitle
        val title = if (display.isBlank()) getString(R.string.player_notif_default_title) else display
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lastArtist)
            .build()
        session.setMetadata(md)
        val state = if (lastPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val ps = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY
                    or PlaybackStateCompat.ACTION_PAUSE
                    or PlaybackStateCompat.ACTION_PLAY_PAUSE
                    or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, 0L, 1.0f)
            .build()
        session.setPlaybackState(ps)
    }

    private fun refreshMeta() {
        applyMeta()
        // 1.3.23: если сервис после паузы «демотировали», возврат к игре —
        // момент немедленно вернуть себе передний план, не дожидаясь минуты
        if (lastPlaying) {
            assertForeground()
        }
        val nmService: Any? = getSystemService(Context.NOTIFICATION_SERVICE)
        if (nmService !is NotificationManager) return
        nmService.notify(SERVICE_ID, buildNotification())
    }

    private fun cmdPi(cmd: String, rc: Int): PendingIntent {
        val i = Intent(this, PlaybackService::class.java)
            .setAction(ACTION_CMD)
            .putExtra(EXTRA_CMD, cmd)
        return PendingIntent.getService(
            this, rc, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): Notification {
        // 1.3.23: тап по карточке ведёт туда, кто сейчас играет: вкладка
        // «Музыка» — в плеер, страница VK — в браузер (иначе после тапа
        // браузер лежит ПОВЕРХ плеера и выглядит, будто всё сбросилось)
        val targetCls = if (fromMusicTab) MusicActivity::class.java else MainActivity::class.java
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, targetCls),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val display = if (lastArtist.isBlank()) lastTitle else lastArtist + " — " + lastTitle
        val title = if (display.isBlank()) getString(R.string.player_notif_default_title) else display
        val text = if (display.isBlank()) getString(R.string.playback_notif_text) else getString(R.string.player_notif_source)
        val toggleIcon = if (lastPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val toggleLabel = if (lastPlaying) getString(R.string.player_action_pause) else getString(R.string.player_action_play)
        val style = MediaNotif.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
        return NotificationCompat.Builder(this, CH_CTRL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_prev, getString(R.string.player_action_prev), cmdPi("prev", 11))
            .addAction(toggleIcon, toggleLabel, cmdPi("toggle", 12))
            .addAction(R.drawable.ic_next, getString(R.string.player_action_next), cmdPi("next", 13))
            .setStyle(style)
            .build()
    }

    companion object {
        const val SERVICE_ID = 43

        // 1.3.23: интервал пере-утверждения переднего плана (см. keepAlive)
        private const val KEEP_ALIVE_MS = 60000L

        // 1.3.21: новый канал IMPORTANCE_LOW — MediaStyle-уведомление с
        // кнопками видно в шторке и на локскрине, без звука и без баннеров.
        // Прежний vkb_playback (IMPORTANCE_MIN) прячет уведомление в низ
        // шторки без кнопок; старые установки видят новый канал после
        // обновления, старый просто остаётся в настройках системы.
        const val CH_CTRL = "vkb_playback_ctrl"
        private const val ACTION_CMD = "com.pikovk.browser.player.CMD"
        private const val EXTRA_CMD = "vkb_cmd"

        // Ссылка на текущий WebView (ставит MainActivity.buildWebView).
        @Volatile var linkedWeb: WebView? = null
            private set

        // 1.3.23: источник команд — вкладка «Музыка» (для тапа по карточке)
        @Volatile var fromMusicTab: Boolean = false
            private set

        @Volatile private var instance: PlaybackService? = null
        @Volatile private var lastTitle: String = ""
        @Volatile private var lastArtist: String = ""
        @Volatile private var lastPlaying: Boolean = false

        // 1.3.22: для упреждающего запуска из VkbBridge.playerState —
        // без повторного startForegroundService, если служба уже живёт.
        fun isRunning(): Boolean {
            val i = instance
            return i != null
        }

        // 1.3.23: играет ли сейчас музыка (по последнему репорту моста).
        // Нужен MainActivity.onStart: службу нельзя глушить, пока играет
        // вкладка «Музыка» — возврат браузера на экран не должен убивать фон.
        fun isPlaying(): Boolean {
            return lastPlaying
        }

        fun attach(wv: WebView?) {
            linkedWeb = wv
            fromMusicTab = false
        }

        // 1.3.23: источником команд стала вкладка «Музыка» (и карточка будет
        // открывать её, а не браузер поверх плеера)
        fun attachMusicTab(wv: WebView?) {
            linkedWeb = wv
            fromMusicTab = true
        }

        // Поток JS-моста: новые метаданные трека. Служба может не работать
        // (приложение на экране) — тогда просто запоминаем до её старта.
        fun updateMeta(title: String, artist: String, playing: Boolean) {
            lastTitle = title
            lastArtist = artist
            lastPlaying = playing
            val svc = instance
            if (svc != null) {
                // Поток JS-моста может прийти в момент onDestroy — глушим
                runCatching { svc.refreshMeta() }
            }
        }

        fun ensureChannel(ctx: Context) {
            val nmService: Any? = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
            if (nmService !is NotificationManager) return // службы уведомлений нет
            nmService.createNotificationChannel(
                NotificationChannel(
                    CH_CTRL,
                    ctx.getString(R.string.notif_channel_playback),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, PlaybackService::class.java)
            // minSdk 26 — startForegroundService доступен всегда
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }
}
