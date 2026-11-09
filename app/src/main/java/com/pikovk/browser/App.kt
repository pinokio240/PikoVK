package com.pikovk.browser

import android.app.Application
import com.pikovk.browser.media.PlaybackService
import com.pikovk.browser.notify.NotifyService
import com.pikovk.browser.profile.ProfileManager
import com.pikovk.browser.util.Prefs

// Точка входа приложения: инициализация настроек, профилей и каналов уведомлений.
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        ProfileManager.init(this)
        NotifyService.ensureChannels(this)
        PlaybackService.ensureChannel(this)
    }
}
