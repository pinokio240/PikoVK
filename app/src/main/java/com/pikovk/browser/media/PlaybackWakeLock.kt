package com.pikovk.browser.media

import android.content.Context
import android.os.PowerManager

// Частичный wake-lock фонового прослушивания (1.3.14).
//
// ЗАЧЕМ: 1.3.10 держал процесс живым foreground-службой, но обрыв между
// треками оставался. Причина — в паузе «трек закончился, JS запускает
// следующий» у звука нет своего источника пробуждения: пока играет трек,
// аудиотракт сам держит процессор бодрым, а в момент смены трека звук
// молчит — процессор засыпает прямо посреди перехода. JavaScript плеера
// замирает, подгрузка следующего трека откладывается до пробуждения:
// пауза на секунды, а при агрессивной экономии — до полной остановки
// плейлиста. Частичный wake-lock держит процессор бодрым ровно пока
// приложение не на экране и включено «Фоновое воспроизведение» (держит
// его MainActivity, см. onStart/onStop/onDestroy; включение и отключение
// синхронны с включением и отключением службы PlaybackService).
//
// Отдельный объект, а не поле службы: служба запускается не всегда
// (если уже работает опрос уведомлений, его foreground хватает процессу),
// а переход трек-к-треку ломается в обоих случаях — wake-lock нужен
// независимо от того, какая служба держит процесс.
//
// Безопасность: блокировка безотсчётная (setReferenceCounted(false)),
// повторный acquire не наслаивается, release без активной блокировки —
// безопасен; fail-open: если системной службы питания нет, просто ничего
// не делаем (звук будет играть, возможны прежние паузы на переходах).
object PlaybackWakeLock {

    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(ctx: Context) {
        val current = lock
        if (current != null) return // уже держим
        val pmService: Any? = ctx.getSystemService(Context.POWER_SERVICE)
        if (pmService !is PowerManager) return // службы питания нет — fail-open
        val wl = pmService.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PikoVK:playback")
        wl.setReferenceCounted(false)
        wl.acquire()
        lock = wl
    }

    @Synchronized
    fun release() {
        val current = lock
        if (current == null) return // держать нечего
        if (current.isHeld) {
            current.release()
        }
        lock = null
    }
}
