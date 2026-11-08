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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pikovk.browser.R
import com.pikovk.browser.ui.MainActivity

// Foreground-служба фонового воспроизведения (1.3.10).
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
// и не чистится, и WebView продолжает воспроизведение. Сама служба ничего
// не делает со звуком и ничего не блокирует — только держит процесс живым.
//
// Жизненный цикл: запуск — MainActivity.onStop (приложение ушло с экрана),
// остановка — MainActivity.onStart / onDestroy. Если уже работает опрос
// уведомлений (NotifyService), его foreground-приоритет достаточно держит
// процесс, и отдельная служба не запускается (меньше уведомлений).
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ничего не делаем: смысл службы — сам факт foreground-приоритета
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.playback_notif_title))
            .setContentText(getString(R.string.playback_notif_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val SERVICE_ID = 43
        const val CH_PLAYBACK = "vkb_playback"

        fun ensureChannel(ctx: Context) {
            val nmService: Any? = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
            if (nmService !is NotificationManager) return // службы уведомлений нет
            nmService.createNotificationChannel(
                NotificationChannel(
                    CH_PLAYBACK,
                    ctx.getString(R.string.notif_channel_playback),
                    NotificationManager.IMPORTANCE_MIN
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
