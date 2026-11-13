package com.pikovk.browser.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.pikovk.browser.R
import com.pikovk.browser.browser.UserAgents
import com.pikovk.browser.browser.VkThemeCookies
import com.pikovk.browser.ext.VkThemes
import com.pikovk.browser.media.PlaybackService
import com.pikovk.browser.media.PlaybackWakeLock
import com.pikovk.browser.notify.NotifyService
import com.pikovk.browser.profile.ProfileManager
import com.pikovk.browser.util.Prefs

// Экран настроек. Всё переключается на живую: изменения, влияющие на
// WebView (расширения, UA, полная версия), помечают флаг dirty —
// MainActivity пересоберёт WebView при возврате.
class SettingsActivity : AppCompatActivity() {

    // Кнопка «Без оптимизации батареи» (1.3.14): подпись обновляется в onResume,
    // чтобы после возврата из системного диалога было видно актуальное состояние.
    private var batteryBtn: Button? = null

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && (Prefs.notifyEnabled || Prefs.imNotifyEnabled)) {
                NotifyService.start(this)
            } else if (!granted) {
                Prefs.notifyEnabled = false
                Prefs.imNotifyEnabled = false
                Toast.makeText(this, "Без разрешения уведомления не покажутся", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        setContentView(ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        fun header(text: String) {
            root.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(pad(16), pad(16), pad(16), pad(4))
            })
        }

        fun hint(text: String) {
            root.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                setPadding(pad(16), 0, pad(16), pad(8))
            })
        }

        fun switchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
            root.addView(SwitchCompat(this).apply {
                this.text = label
                textSize = 16f
                setPadding(pad(16), pad(8), pad(16), pad(8))
                isChecked = checked
                setOnCheckedChangeListener { _, isCheckedNow -> onToggle(isCheckedNow) }
            })
        }

        fun buttonRow(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                this.text = label
                setPadding(pad(16), pad(4), pad(16), pad(4))
                setOnClickListener { onClick() }
            })
        }

        // ---------------- Интерфейс ----------------
        header("Интерфейс")
        switchRow("Адресная строка сверху (полный режим)", Prefs.uiMode == Prefs.UI_FULL) { on ->
            Prefs.uiMode = if (on) Prefs.UI_FULL else Prefs.UI_MINIMAL
        }
        hint("По умолчанию — минимализм: только нижняя панель.")

        // ---------------- Расширения ----------------
        header("Расширения")
        switchRow("Блокировка рекламы (селекторы VK + счётчики)", Prefs.extAdBlock) {
            Prefs.extAdBlock = it; Prefs.markDirty()
        }
        hint("Включено по умолчанию (с 1.3.16 — прямое решение владельца после жалоб на рекламу в ленте). Уровни: селекторы контейнеров, которые VK сам помечает (левый баннер, promoted-посты, промо Музыки/сторис); карточки ленты со ссылками через рекламные редиректоры rv.mail.ru/adfox.ru (в т.ч. закодированные в away.php); лид-формы VK Рекламы (1.3.16): карточка с прямой ссылкой или встроенным iframe m.vk.ru/app…#?type=leadform прячется целиком, документ общего рендерера (апп 51478948) блокируется в сети (HTTP 451), переход по рекламной ссылке отменяется с уведомлением; 1.3.17: штатные флаги VK noAds/no_ads/hide_ad — страница сама не создаёт рекламные блоки; карточки ловятся по маркерам внутри (плашка .ads_mark, пункт меню ad_info.php, adsite_/post_ad_data в onclick, атрибут видеорекламы data-mt-video-hls); в сети режутся рекламный CDN mradx.net и эндпоинты выдачи рекламы VK (ads_rotate, al_feed.php?ads_section, al_video.php?act=ads_stat); сторонние счётчики (Mail.ru, Яндекс, Mediascope) режутся; рекламный SDK плеера глушится — видео стартует без пре-ролла; с 1.3.19 эта заглушка работает только на видеостраницах (vkvideo.ru, /video, /clip) — на аудио она задерживала старт следующего трека. Видео, аудио, картинки и обычные Mini Apps не затрагивает: признак не совпал — реклама просто остаётся. Выключение тумблером возвращает всё как было.")
        switchRow("Тёмная тема", Prefs.extDark) { on ->
            Prefs.extDark = on
            // 1.3.7: повторное включение — выставить родные куки темы VK
            // заново (флаг слота сбрасывается, чтобы применить принудительно).
            if (on) VkThemeCookies.reapply()
            Prefs.markDirty()
        }
        hint("Всегда тёмная, независимо от темы системы. Включена по умолчанию. 1.3.7: перед загрузкой выставляются родные куки темы VK (remixdark_color_scheme / remixcolor_scheme_mode из политики vk.ru/privacy/cookies) — VK сам рисует свою тёмную тему; CSS-инверсия остаётся запасным механизмом. Повторное включение тумблера применяет куки заново; после этого переключатель темы внутри VK остаётся главным. Применение — после возврата в браузер.")
        // 1.3.24: темы ВКонтакте из VK Next — выбор из извлечённого набора.
        buttonRow("Тема ВКонтакте: ${VkThemes.choiceLabel(Prefs.vkThemeId)}") { showThemeDialog() }
        hint("Темы извлечены из расширения VK Next 14.14.2 (в пакете 4 уникальные: «Светлая» — родные VKUI-токены; «Тёмная серая» и «Чёрная» — полные карты из 300 переменных: старые переменные VK для мобильной версии и токены VKUI для полной, с зеркалированием по alias-карте VK Next). Цветная тема применяется к обеим версиям сайта: переменные ставятся инлайном на <html> до загрузки страницы и перекрывают стили VK; для тёмных дополнительно включается класс тёмной VKUI-схемы. Активная цветная тема заменяет собой тумблер «Тёмная тема»: куки тёмной схемы снимаются (в том числе выставленные самим VK — иначе его тёмная тема рисовалась поверх карты, 1.3.25), CSS-инверсия отключается; карта применяется с повторами при перестройке страницы VK. «Не менять» и «Тёмная (как в VK)» — прежнее поведение. Применение — после возврата в браузер.")
        switchRow("Скачивание медиа (фото, видео, файлы)", Prefs.extMedia) {
            Prefs.extMedia = it; Prefs.markDirty()
        }
        switchRow("Твики ленты: без стори, компактные посты", Prefs.extTweaks) {
            Prefs.extTweaks = it; Prefs.markDirty()
        }
        hint("Применение расширений — после возврата в браузер.")

        // ---------------- Версия VK ----------------
        header("Версия VK")
        switchRow("Всегда полная версия vk.ru", Prefs.autoDesktop) {
            Prefs.autoDesktop = it
            // Кнопка следует за настройкой: UA и хост всегда согласованы.
            // После запуска кнопку всё ещё можно переключить на мобильную.
            Prefs.desktopMode = it
            Prefs.markDirty()
        }
        hint("Каждый запуск открывается в полной версии — там работают создание и сохранение плейлистов в Музыке и другие функции, которых нет в мобильной веб-версии. Кнопка на нижней панели может переключить на мобильную — её выбор действует до следующего запуска и больше не перебивается.")

        // ---------------- User-Agent ----------------
        header("User-Agent")
        buttonRow("Выбрать User-Agent") { showUaDialog() }
        hint("Пресет действует в мобильном режиме; кнопка «полная версия» всегда использует десктопный UA.")

        // ---------------- Медиа ----------------
        header("Медиа")
        switchRow("Фоновое воспроизведение аудио и видео", Prefs.backgroundPlayback) { on ->
            Prefs.backgroundPlayback = on
            if (!on) {
                // 1.3.14: фоновое прослушивание выключено — wake-lock не держим
                PlaybackWakeLock.release()
                PlaybackService.stop(this)
            }
        }
        hint("Включено по умолчанию. Когда приложение свёрнуто или погас экран, работают четыре механизма подряд: служба переднего плана держит процесс (1.3.10), приоритет рендерера WebView не даёт системе приглушить страницу (1.3.14), wake-lock не даёт процессору уснуть в паузе между треками (1.3.14 — именно она рвала переходы: пока звук молчит, будить его некому), страница считает себя видимой — Chromium не троттлит её таймеры и не замораживает (1.3.18 — именно троттлинг останавливал автопереход «трек за треком» в фоне). Если закрыть приложение из недавних — WebView уничтожается и звук прекратится, это ограничение WebView. Дополнительно отключите оптимизацию батареи кнопкой ниже: без неё Doze может приостановить сеть в момент подгрузки следующего трека.")
        val initialBatteryBtn = Button(this).apply {
            text = batteryLabel()
            setPadding(pad(16), pad(4), pad(16), pad(4))
            setOnClickListener {
                if (batteryIgnoring()) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Оптимизация батареи для PikoVK уже отключена",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Системный диалог «разрешить приложению работать без
                    // оптимизации батареи?» — решение принимает владелец
                    val request = Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    runCatching { startActivity(request) }
                }
            }
        }
        batteryBtn = initialBatteryBtn
        root.addView(initialBatteryBtn)
        hint("Кнопка относится и к фоновому воспроизведению, и к фоновым уведомлениям. Без этого разрешения Android в глубоком Doze (устройство лежит неподвижно с погашенным экраном) приостанавливает сеть — подгрузка следующего трека зависает, хотя процесс жив. Разрешение выключает для PikoVK только оптимизацию батареи и ничего не блокирует.")

        // ---------------- Уведомления ----------------
        header("Фоновые уведомления")
        switchRow("Реакции, комментарии, ответы", Prefs.notifyEnabled) { on ->
            Prefs.notifyEnabled = on
            applyNotifyService()
        }
        switchRow("Личные сообщения (мессенджер)", Prefs.imNotifyEnabled) { on ->
            Prefs.imNotifyEnabled = on
            applyNotifyService()
        }
        buttonRow("Интервал опроса: ${Prefs.notifyIntervalSec} с") { showIntervalDialog() }
        hint("Служба опрашивает страницы «Ответы» и «Сообщения» m.vk.ru с cookie вашей активной сессии; данные никуда не отправляются. Уведомления о сообщениях появляются в шторке и на экране блокировки; тап открывает мессенджер. Для стабильной работы разрешите приложению работать без оптимизации батареи.")

        // ---------------- Профили ----------------
        header("Профили")
        root.addView(TextView(this).apply {
            text = "Активен: ${ProfileManager.slotLabel(ProfileManager.activeSlot())}. Переключение — кнопка профиля на нижней панели (до 3 сессий без перелогина). Долгое нажатие — выход из активного аккаунта."
            textSize = 14f
            setPadding(pad(16), pad(4), pad(16), pad(8))
        })

        // ---------------- Диагностика ----------------
        header("Диагностика")
        switchRow("Снифер ссылок (logcat PikoVK-Link)", Prefs.snifferEnabled) {
            Prefs.snifferEnabled = it
        }
        hint("Каждый открытый адрес — в журнал logcat с разбором параметров: клики, запросы страниц (включая промежуточные серверные редиректы VK), спа-переходы без перезагрузки, скачивания. Особые параметры разворачиваются: _to (обёртка client_url_proxy) декодируется из base64. Смотреть: adb logcat -s PikoVK-Link. Лог остаётся на устройстве, но адреса могут содержать токены — при необходимости выключите.")

        // ---------------- О программе ----------------
        header("О программе")
        hint("PikoVK 1.3.24 — лёгкий браузер с домом на VK: расширения (включая темы ВКонтакте из VK Next), мультиаккаунт, фоновые уведомления (включая личные сообщения) и воспроизведение. Единственная блокировка — реклама (расширение «Блокировка рекламы», включено по умолчанию и выключается одним тумблером), всё остальное опционально.")
    }

    // Единая логика для обоих тумблеров уведомлений (1.3.20): службу держим
    // живой, пока включён ХОТЯ БЫ один тумблер; разрешение POST_NOTIFICATIONS
    // (Android 13+) запрашивается при первом включении любого из них.
    // Перезапуск stop+start обновляет текст служебного уведомления — он
    // честно перечисляет, за чем следит.
    private fun applyNotifyService() {
        val anyOn = Prefs.notifyEnabled || Prefs.imNotifyEnabled
        if (!anyOn) {
            NotifyService.stop(this)
            return
        }
        var granted = true
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotifyService.stop(this)
            NotifyService.start(this)
        }
    }

    // 1.3.14: включена ли для приложения оптимизация батареи (точнее —
    // выключено ли исключение из неё). Сбой системной службы — считаем,
    // что исключения нет (кнопка останется рабочей).
    private fun batteryIgnoring(): Boolean {
        val pmService: Any? = getSystemService(Context.POWER_SERVICE)
        if (pmService !is PowerManager) return false
        return pmService.isIgnoringBatteryOptimizations(packageName)
    }

    // Подпись кнопки по текущему состоянию исключения из оптимизации.
    private fun batteryLabel(): String {
        if (batteryIgnoring()) return "Оптимизация батареи: уже отключена — ок"
        return "Отключить оптимизацию батареи (важно для музыки в фоне)"
    }

    override fun onResume() {
        super.onResume()
        // Возврат из системного диалога исключения — обновляем подпись кнопки
        val btn = batteryBtn
        if (btn != null) {
            btn.text = batteryLabel()
        }
    }

    // 1.3.24: диалог выбора темы ВКонтакте (набор из VK Next).
    private fun showThemeDialog() {
        val ids = VkThemes.CHOICE_IDS
        val labels = ids.map { VkThemes.choiceLabel(it) }.toTypedArray()
        val idx = ids.indexOf(Prefs.vkThemeId)
        val current = if (idx < 0) 0 else idx
        AlertDialog.Builder(this)
            .setTitle("Тема ВКонтакте")
            .setSingleChoiceItems(labels, current) { d, which ->
                Prefs.vkThemeId = ids[which]
                d.dismiss()
                Prefs.markDirty()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showUaDialog() {
        val keys = UserAgents.PRESET_LABELS.keys.toList()
        val labels = UserAgents.PRESET_LABELS.values.toTypedArray()
        val current = keys.indexOf(Prefs.uaPreset).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("User-Agent")
            .setSingleChoiceItems(labels, current) { d, which ->
                Prefs.uaPreset = keys[which]
                if (keys[which] == UserAgents.PRESET_CUSTOM) askCustomUa()
                d.dismiss()
                Prefs.markDirty()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askCustomUa() {
        val input = android.widget.EditText(this)
        val currentUa: String? = Prefs.customUa
        if (currentUa == null) {
            input.setText(UserAgents.realAndroidUa())
        } else {
            input.setText(currentUa)
        }
        AlertDialog.Builder(this)
            .setTitle("Свой User-Agent")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                Prefs.customUa = input.text.toString().trim()
                Prefs.markDirty()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showIntervalDialog() {
        val values = intArrayOf(30, 60, 120, 300)
        val labels = values.map { "$it секунд" }.toTypedArray()
        val idx = values.indexOf(Prefs.notifyIntervalSec)
        val current = if (idx < 0) 1 else idx
        AlertDialog.Builder(this)
            .setTitle("Интервал опроса")
            .setSingleChoiceItems(labels, current) { d, which ->
                Prefs.notifyIntervalSec = values[which]
                d.dismiss()
                if (Prefs.notifyEnabled || Prefs.imNotifyEnabled) NotifyService.start(this)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
