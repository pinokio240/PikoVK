package com.pikovk.browser.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pikovk.browser.util.Prefs

// Сторож фоновой службы опроса (1.3.40, доработка-29).
//
// NotifyService — foreground-служба со START_STICKY, но её гарантии не
// покрывают всё: агрессивные прошивки прибивают процесс целиком (sticky-
// restart после этого не срабатывает, пока приложение снова не откроют),
// а свайп из «недавних» на части ROM гасит и foreground-службу.
//
// Сторож — будильник AlarmManager раз в 15 минут: если мониторинг включён
// тумблерами, дёргает NotifyService.start() (для живой службы это
// безвредный повторный onStartCommand, для убитой — возрождение) и
// переставляет себя на следующий тик; если оба тумблера выключены —
// снимает будильник. setAndAllowWhileIdle: тикает и в Doze (системный
// троттлинг — не чаще раза в 9 минут — для 15-минутного интервала не
// важен), точность времени не нужна. Сам будильник не переживает
// перезагрузку — после boot его восстанавливает BootReceiver.
//
// ЧЕСТНОЕ ОГРАНИЧЕНИЕ: на Android 12+ запуск foreground-службы из фона
// разрешён не всегда (зависит от состояния приложения). Если старт из
// будильника запрещён системой — исключение молча глотается, следующий
// тик пробует снова; гарантированное возрождение остаётся при открытии
// приложения. Надёжнее всего — отключённая оптимизация батареи
// (приложение само предлагает её отключить в настройках).
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.notifyEnabled || Prefs.imNotifyEnabled) {
            try {
                NotifyService.start(context)
            } catch (_: Exception) {
                // фоновый старт службы запрещён системой — пробуем на следующем тике
            }
            schedule(context)
        } else {
            cancel(context)
        }
    }

    companion object {
        private const val PERIOD_MS = 15L * 60L * 1000L
        private const val REQUEST_CODE = 1001

        // Переставить сторож: одноразовый будильник «сейчас + период»,
        // приёмник сам ставит следующий тик — схема «перезарядки».
        fun schedule(ctx: Context) {
            val amService: Any? = ctx.getSystemService(Context.ALARM_SERVICE)
            if (amService !is AlarmManager) return // службы будильников нет
            amService.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + PERIOD_MS,
                operation(ctx)
            )
        }

        fun cancel(ctx: Context) {
            val amService: Any? = ctx.getSystemService(Context.ALARM_SERVICE)
            if (amService !is AlarmManager) return
            amService.cancel(operation(ctx))
        }

        // Один и тот же PendingIntent (одинаковый код запроса и intent) —
        // повторный schedule ПЕРЕЗАПИСЫВАЕТ будильник, а не наслаивает.
        private fun operation(ctx: Context): PendingIntent {
            val i = Intent(ctx, WatchdogReceiver::class.java)
            return PendingIntent.getBroadcast(
                ctx, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
