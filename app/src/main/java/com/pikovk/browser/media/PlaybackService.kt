package com.pikovk.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotif
import com.pikovk.browser.R
import com.pikovk.browser.music.MusicActivity
import com.pikovk.browser.ui.MainActivity
import org.json.JSONObject

// Foreground-служба фонового воспроизведения (1.3.10) + медиашторка (1.3.21)
// + НАТИВНЫЙ ПЛЕЕР (1.3.23).
//
// История: служба появилась, чтобы процесс с играющим WebView не замораживали
// (кэш-фризер Android 12+) и не чистили (фантомный киллер рендереров). Звук
// при этом оставался ВНУТРИ WebView вкладки — и на агрессивных прошивках
// владелец трижды ловил обрыв при блокировке/сворачивании: система убивает
// активити и её WebView, служба остаётся, а звука уже нет. Вывод: звук не
// должен зависеть от жизни WebView ВООБЩЕ.
//
// РЕШЕНИЕ (как у всех настоящих плееров): воспроизведение переехало в эту
// службу — нативный MediaPlayer. Вкладка «Музыка» стала пультом: выбрала
// трек — отдала его службе через мост (VkbMusic.nativePlay), служба играет
// сама. Служба переживает закрытие вкладки, уничтожение обеих активити и
// сворачивание: уведомление MediaStyle с кнопками и позицией работает
// всегда. Очередь/шаффл/повтор живут в странице (как у VK Music Player):
// конец трека служба репортит странице, страница решает, что дальше, и
// запускает следующий трек через nativePlay. Вкладка закрыта — трек просто
// доигрывает и встаёт на паузу.
//
// Остальные механизмы выживания на месте: FGS mediaPlayback (тип в
// манифесте + FOREGROUND_SERVICE_MEDIA_PLAYBACK), упреждающий запуск из
// видимого состояния, свой wake-lock, подмена видимости WebView,
// re-assert startForeground (keepAlive) против тихой демотации, аудиофокус
// и пауза при вынимании наушников (ACTION_AUDIO_BECOMING_NOISY).
class PlaybackService : Service() {

    private lateinit var session: MediaSessionCompat

    // 1.3.23: собственный частичный wake-lock службы. Процессор нужен не
    // только аудиотракту, но и JS страницы (переход трек-к-треку) — держим
    // ровно пока живёт служба, независимо от того, что с активити.
    private var cpuLock: PowerManager.WakeLock? = null

    // Нативный движок. Все касания — только с главного потока (handler):
    // MediaPlayer того требует, а команды приходят и из JS-моста.
    private var player: MediaPlayer? = null

    // Сколько секунд отложить после prepare (тап по треку из середины)
    private var pendingSeekSec: Int = 0

    // Аудиофокус: теряли из-за звонка/навигатора — запомним и вернёмся
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocus: Boolean = false

    // Наушники вынули — честная пауза
    private var noisyRegistered: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            handler.post {
                pauseNative()
            }
        }
    }

    // 1.3.23: страховка от тихой «демотации» службы. Полевые репорты 2025-2026:
    // часть прошивок через десятки минут сама снимает у FGS статус переднего
    // плана, ни о чём не сообщая — процесс падает в кэш, заморозка (Android
    // 12+) глушит звук, а повторный подъём из фона уже запрещён. Лечится
    // дёшево: пока играет музыка, раз в минуту ЗАНОВО утверждаем
    // startForeground — это не «старт из фона» (служба уже жива), система
    // такое разрешает. Fail-open: любая ошибка гасится — звук важнее строгости.
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

    // Секундный тикер нативного движка: позиция → MediaSession (ползунок
    // в шторке) и → страница (ползунок вкладки). Без него шторка показывает
    // мёртвое время, а вкладка не видит прогресс чужого для неё плеера.
    private val posTick: Runnable = object : Runnable {
        override fun run() {
            val self = instance
            if (self == null) return
            if (nativeActive) {
                // позиция читается только здесь, на main — и кэшируется в
                // lastPos для шторки/страницы/nativeState (JS-поток моста)
                lastPos = readPosition()
                updateSessionState()
                pushPosToPage()
            }
            handler.postDelayed(this, TICK_MS)
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
            // гарнитура/Bluetooth/локскрин: нативные команды работают,
            // даже если вкладка «Музыка» давно закрыта
            override fun onPlay() {
                resumeNative()
            }

            override fun onPause() {
                pauseNative()
            }

            override fun onSeekTo(pos: Long) {
                seekNative(pos / 1000.0)
            }

            override fun onSkipToNext() {
                // очередь живёт в странице — решает она
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
        handler.postDelayed(posTick, TICK_MS)
        // трек, выбранный в вкладке, пока службы ещё не было
        consumePending()
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
        // Кнопки уведомления: экстра cmd → плееру. Пустой intent (перезапуск
        // системой) — просто продолжаем держать foreground.
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
        // 1.3.23: гасим циклы страховки и тикер вместе со службой
        handler.removeCallbacks(keepAlive)
        handler.removeCallbacks(posTick)
        unregisterNoisy()
        resetEngine()
        abandonFocus()
        pendingPlay = null // брошенных треков не оставляем
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

    // ------------------------------------------------------------------
    // Нативный движок (звук живёт здесь, а не в WebView)
    // ------------------------------------------------------------------

    // Трек от вкладки: {url, title, artist, duration, rawId, seek}. JS-поток
    // моста приходит сюда через companion.nPlay — уже на главном потоке.
    fun consumePending() {
        val p = pendingPlay
        if (p == null) return
        pendingPlay = null
        startNative(p)
    }

    private fun startNative(payload: String) {
        val obj: JSONObject? = try {
            JSONObject(payload)
        } catch (_: Exception) {
            null
        }
        if (obj == null) return
        val url: String? = obj.optString("url", "")
        if (url == null || url.isEmpty()) return
        val title: String? = obj.optString("title", "")
        val artist: String? = obj.optString("artist", "")
        val rawId: String? = obj.optString("rawId", "")

        requestFocus()
        registerNoisy()
        resetEngine()
        // позиция старта — до prepareAsync, чтобы onPrepared её уже видел
        pendingSeekSec = obj.optInt("seek", 0)

        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener { prepared ->
                // длительность из потока честнее метаданных VK
                val d = prepared.duration.toLong()
                if (d > 0) lastDur = d / 1000L
                val seekSec = pendingSeekSec
                pendingSeekSec = 0
                if (seekSec > 0) {
                    try {
                        prepared.seekTo(seekSec * 1000)
                    } catch (_: Exception) {
                    }
                }
                try {
                    prepared.start()
                } catch (_: Exception) {
                }
                lastPlaying = true
                applyMeta()
                refreshNotification()
            }
            mp.setOnCompletionListener {
                // трек доиграл: страница ведёт очередь (повтор/следующий)
                lastPlaying = false
                updateSessionState()
                refreshNotification()
                pageJs("window.__vkbMusicEnded&&window.__vkbMusicEnded();")
            }
            mp.setOnErrorListener { _, _, _ ->
                // битая ссылка/сеть: честно ступаем на паузу и просим
                // страницу перейти дальше — как делает обычный плеер
                runCatching {
                    lastPlaying = false
                    resetEngine()
                    updateSessionState()
                    refreshNotification()
                    pageJs("window.__vkbMusicEnded&&window.__vkbMusicEnded();")
                }
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            runCatching { mp.release() }
            lastPlaying = false
            updateSessionState()
            refreshNotification()
            // битая ссылка упала СИНХРОННО — страница должна уйти к следующему
            pageJs("window.__vkbMusicEnded&&window.__vkbMusicEnded();")
            return
        }
        player = mp
        nativeActive = true
        lastTitle = if (title == null) "" else title
        lastArtist = if (artist == null) "" else artist
        lastRawId = if (rawId == null) "" else rawId
        val metaDur = obj.optLong("duration", 0)
        if (metaDur > 0) lastDur = metaDur
        lastPlaying = false // поднимется в onPrepared, когда звук реально пошёл
        applyMeta()
        refreshNotification()
    }

    private fun pauseNative() {
        val mp = player
        if (mp == null) return
        try {
            if (mp.isPlaying) mp.pause()
        } catch (_: Exception) {
        }
        lastPlaying = false
        updateSessionState()
        refreshNotification()
    }

    private fun resumeNative() {
        val mp = player
        if (mp == null) return
        requestFocus()
        var ok = true
        try {
            mp.start()
        } catch (_: Exception) {
            ok = false // плеер в мёртвом состоянии — честно оставляем паузу
        }
        lastPlaying = ok
        updateSessionState()
        refreshNotification()
    }

    private fun seekNative(sec: Double) {
        val mp = player
        if (mp == null) return
        var target = sec
        if (target < 0.0) target = 0.0
        try {
            mp.seekTo((target * 1000.0).toInt())
        } catch (_: Exception) {
        }
        updateSessionState()
    }

    // Полный сброс движка (новый трек или смерть службы). Фокус не отдаём —
    // при смене трека его тут же заберём снова.
    private fun resetEngine() {
        val mp = player
        if (mp != null) {
            runCatching {
                mp.setOnCompletionListener(null)
                mp.setOnErrorListener(null)
                mp.setOnPreparedListener(null)
                try {
                    if (mp.isPlaying) mp.stop()
                } catch (_: Exception) {
                }
                mp.release()
            }
            player = null
        }
        pendingSeekSec = 0
        nativeActive = false
    }

    // ------------------------------------------------------------------
    // Аудиофокус и наушники
    // ------------------------------------------------------------------

    private fun requestFocus() {
        val amService: Any? = getSystemService(Context.AUDIO_SERVICE)
        if (amService !is AudioManager) return // fail-open: играем без фокуса
        val fr = focusRequest
        if (fr == null) {
            val built = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build()
            focusRequest = built
        }
        val use = focusRequest
        if (use != null) {
            try {
                amService.requestAudioFocus(use)
            } catch (_: Exception) {
            }
        }
    }

    private fun abandonFocus() {
        val amService: Any? = getSystemService(Context.AUDIO_SERVICE)
        if (amService !is AudioManager) return
        val fr = focusRequest
        if (fr != null) {
            try {
                amService.abandonAudioFocusRequest(fr)
            } catch (_: Exception) {
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // слушатель сразу на нашем handler — поток уже главный
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            // фокус забрали надолго (звонок сменился другим плеером) — пауза
            resumeOnFocus = false
            pauseNative()
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            // короткий перехват (навигатор, ассистент) — пауза с возвратом
            resumeOnFocus = lastPlaying
            pauseNative()
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeOnFocus) {
                resumeOnFocus = false
                resumeNative()
            }
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        try {
            ContextCompat.registerReceiver(
                this, noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
        }
        noisyRegistered = false
    }

    // ------------------------------------------------------------------
    // Команды: кнопки шторки/гарнитуры и мост
    // ------------------------------------------------------------------

    // Команда от кнопок шторки. Нативное (пауза/плей/переключение) работает
    // даже без вкладки; next/prev — очередь в странице.
    private fun dispatch(cmd: String) {
        if (nativeActive) {
            if (cmd == "toggle") {
                if (lastPlaying) pauseNative() else resumeNative()
                return
            }
            if (cmd == "pause") {
                pauseNative()
                return
            }
            if (cmd == "play" || cmd == "resume") {
                resumeNative()
                return
            }
        }
        pageJs("window.__vkbPlayerCmd&&window.__vkbPlayerCmd('" + cmd + "');")
    }

    // Выполнить JS в привязанном WebView (вкладка или страница VK).
    // WebView мог быть уничтожен — молча выходим (fail-open).
    private fun pageJs(js: String) {
        val wv = linkedWeb
        if (wv == null) return
        wv.post {
            runCatching {
                wv.evaluateJavascript(js, null)
            }
        }
    }

    // ------------------------------------------------------------------
    // Метаданные, позиция, уведомление
    // ------------------------------------------------------------------

    // Метаданные → MediaSession. Состояние (позиция/скорость) — отдельно,
    // его обновляет тикер каждую секунду.
    private fun applyMeta() {
        val display = if (lastArtist.isBlank()) lastTitle else lastArtist + " — " + lastTitle
        val title = if (display.isBlank()) getString(R.string.player_notif_default_title) else display
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lastArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, lastDur * 1000L)
            .build()
        session.setMetadata(md)
        updateSessionState()
    }

    // main-поток: текущая позиция движка (0, если плеера нет). Единственная
    // точка касания MediaPlayer вне создания — плеер не потокобезопасен.
    private fun readPosition(): Long {
        val mp = player
        if (mp == null) return 0
        return try {
            mp.currentPosition.toLong()
        } catch (_: Exception) {
            0
        }
    }

    // Состояние воспроизведения с позицией: по нему шторка рисует ползунок
    // и показывает актуальное время. Вызывается с main (тикер/движок) и,
    // через refreshMeta, из JS-потока моста — поэтому позиция не читается
    // у плеера напрямую, а берётся из кэша lastPos, обновляемого тикером.
    private fun updateSessionState() {
        val state = if (lastPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val speed = if (lastPlaying) 1.0f else 0.0f
        val ps = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY
                    or PlaybackStateCompat.ACTION_PAUSE
                    or PlaybackStateCompat.ACTION_PLAY_PAUSE
                    or PlaybackStateCompat.ACTION_SEEK_TO
                    or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, lastPos, speed)
            .build()
        session.setPlaybackState(ps)
    }

    // Позиция → вкладка «Музыка» (ползунок и тайминги в UI страницы).
    private fun pushPosToPage() {
        if (!fromMusicTab) return // страница VK не понимает эти хуки
        val wv = linkedWeb
        if (wv == null) return
        val playFlag = if (lastPlaying) 1 else 0
        val js = "window.__vkbMusicPos&&window.__vkbMusicPos(" +
            lastPos + "," + lastDur + "," + playFlag + "," +
            (if (nativeActive) 1 else 0) + ");"
        wv.post {
            runCatching {
                wv.evaluateJavascript(js, null)
            }
        }
    }

    private fun refreshNotification() {
        val nmService: Any? = getSystemService(Context.NOTIFICATION_SERVICE)
        if (nmService !is NotificationManager) return
        runCatching {
            nmService.notify(SERVICE_ID, buildNotification())
        }
    }

    private fun refreshMeta() {
        applyMeta()
        // 1.3.23: если сервис после паузы «демотировали», возврат к игре —
        // момент немедленно вернуть себе передний план, не дожидаясь минуты
        if (lastPlaying) {
            assertForeground()
        }
        refreshNotification()
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
        // Тап по карточке: играет вкладка/нативный плеер — открываем вкладку
        // «Музыка» (она синхронизируется с службой), иначе — браузер.
        val targetCls = if (fromMusicTab || nativeActive) {
            MusicActivity::class.java
        } else {
            MainActivity::class.java
        }
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

        // 1.3.23: шаг тикера позиции
        private const val TICK_MS = 1000L

        // 1.3.21: новый канал IMPORTANCE_LOW — MediaStyle-уведомление с
        // кнопками видно в шторке и на локскрине, без звука и без баннеров.
        const val CH_CTRL = "vkb_playback_ctrl"
        private const val ACTION_CMD = "com.pikovk.browser.player.CMD"
        private const val EXTRA_CMD = "vkb_cmd"

        // Ссылка на текущий WebView (ставит MainActivity.buildWebView).
        @Volatile var linkedWeb: WebView? = null
            private set

        // 1.3.23: источник — вкладка «Музыка» (карточка открывает её)
        @Volatile var fromMusicTab: Boolean = false
            private set

        // 1.3.23: в нативном движке есть трек (играет или на паузе). Пока он
        // есть, вкладка может закрываться — звук не зависит от неё.
        @Volatile var nativeActive: Boolean = false
            private set

        @Volatile private var instance: PlaybackService? = null
        @Volatile private var lastTitle: String = ""
        @Volatile private var lastArtist: String = ""
        @Volatile private var lastPlaying: Boolean = false
        @Volatile private var lastDur: Long = 0
        @Volatile private var lastPos: Long = 0
        @Volatile private var lastRawId: String = ""
        @Volatile private var pendingPlay: String? = null

        // 1.3.22: без повторного startForegroundService, если служба живёт.
        fun isRunning(): Boolean {
            val i = instance
            return i != null
        }

        // 1.3.23: играет ли сейчас музыка (любой источник: вкладка или VK).
        fun isPlaying(): Boolean {
            return lastPlaying
        }

        fun attach(wv: WebView?) {
            linkedWeb = wv
            fromMusicTab = false
        }

        fun attachMusicTab(wv: WebView?) {
            linkedWeb = wv
            fromMusicTab = true
        }

        // 1.3.23: вкладка умирает, но нативный трек продолжает жить в службе —
        // источник «вкладка» сохраняем, чтобы карточка снова её открывала.
        fun detachMusicTab() {
            linkedWeb = null
            if (!nativeActive) {
                fromMusicTab = false
            }
        }

        // Поток JS-моста: новые метаданные трека (страница VK). Служба может
        // не работать (приложение на экране) — запоминаем до её старта.
        fun updateMeta(title: String, artist: String, playing: Boolean) {
            lastTitle = title
            lastArtist = artist
            lastPlaying = playing
            val svc = instance
            if (svc != null) {
                // MediaSession и плеер трогаем только с main-потока; в момент
                // onDestroy пост просто не исполнится — fail-open
                svc.handler.post {
                    val alive = instance
                    if (alive != null) runCatching { alive.refreshMeta() }
                }
            }
        }

        // 1.3.23: трек от вкладки «Музыка». Служба жива — играем сразу;
        // нет — запоминаем и поднимаем службу, она заберёт трек в onCreate.
        fun nPlay(ctx: Context, payload: String) {
            val svc = instance
            if (svc != null) {
                // прямой путь: трек идёт в лямбду, буфер не участвует
                pendingPlay = null
                svc.handler.post {
                    val alive = instance
                    if (alive != null) alive.startNative(payload)
                }
            } else {
                pendingPlay = payload
                try {
                    start(ctx)
                } catch (_: Exception) {
                    // систему не устроил старт из фона — не оставляем
                    // брошенный трек в буфере
                    pendingPlay = null
                }
            }
        }

        fun nPause() {
            val svc = instance
            if (svc != null) {
                svc.handler.post {
                    val alive = instance
                    if (alive != null) alive.pauseNative()
                }
            }
        }

        fun nResume() {
            val svc = instance
            if (svc != null) {
                svc.handler.post {
                    val alive = instance
                    if (alive != null) alive.resumeNative()
                }
            }
        }

        fun nSeek(sec: Double) {
            val svc = instance
            if (svc != null) {
                svc.handler.post {
                    val alive = instance
                    if (alive != null) alive.seekNative(sec)
                }
            }
        }

        // Снимок состояния нативного плеера для свежей вкладки (syncPlayer).
        fun nativeState(): String {
            val obj = JSONObject()
            try {
                obj.put("kind", "player")
                obj.put("has", if (nativeActive) 1 else 0)
                obj.put("title", lastTitle)
                obj.put("artist", lastArtist)
                obj.put("rawId", lastRawId)
                obj.put("playing", if (lastPlaying) 1 else 0)
                obj.put("dur", lastDur)
                // позицию берём из кэша тикера: MediaPlayer трогаем только
                // с main-потока, а syncPlayer приходит из JS-потока моста
                obj.put("pos", lastPos)
            } catch (_: Exception) {
                // JSON не собрался — вкладка просто не синхронизируется
            }
            return obj.toString()
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
