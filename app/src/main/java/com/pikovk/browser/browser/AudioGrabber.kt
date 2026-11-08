package com.pikovk.browser.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.pikovk.browser.bridge.VkbBridge

// Перехватчик ссылок на аудиофайлы VK (1.3.21, идея — VK Music Saver).
//
// Кнопка «скачать» у каждого трека работает так: JS расставляет кнопки по
// строкам Музыки, при клике передаёт имя «Исполнитель - Название» и включает
// воспроизведение этой строки; WebView сам запрашивает файл у аудио-CDN
// (vkuseraudio.net / *.vk-cdn.net), VkWebViewClient в shouldInterceptRequest
// НЕ меняет ответ, а только сообщает сюда адрес, и файл уходит в системный
// DownloadManager под подготовленным именем. Так же делали классические
// загрузчики музыки VK: прямая ссылка живёт недолго и выдаётся только при
// воспроизведении, поэтому «сначала играем — потом качаем».
//
// Ограничения честно: если трек уже загружен в плеер (пауза на середине),
// новой ссылки VK не отдаёт — по таймауту показываем подсказку; повторный
// клик по кнопке этого же трека переключает воспроизведение, следующий клик
// снова включает трек и ссылка приходит.
object AudioGrabber {

    // Окно ожидания ссылки после клика по кнопке.
    private const val WINDOW_MS = 15000L

    // Максимум запомненных адресов (самые свежие).
    private const val MAX_RECENT = 32

    private val lock = Any()
    private val recent = LinkedHashMap<String, Long>()
    private var pendingName: String? = null
    private var pendingAt = 0L
    private var pendingSeq = 0

    @Volatile private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
    }

    // Аудио-CDN VK: mp3/m4a лежат на этих доменах, контент страниц — нет.
    fun isAudioUrl(url: String): Boolean {
        return url.contains("vkuseraudio.net") || url.contains(".vk-cdn.net")
    }

    // Из shouldInterceptRequest (сетевой поток WebView): адрес увиден.
    // Тяжёлый вызов DownloadManager — ЗА пределами synchronized, чтобы
    // не блокировать другие сетевые потоки WebView и поток JS-моста.
    fun see(url: String) {
        val ctx = appContext
        if (ctx == null) return
        var deliverName: String? = null
        synchronized(lock) {
            recent[url] = System.currentTimeMillis()
            while (recent.size > MAX_RECENT) {
                val oldest = recent.entries.iterator()
                oldest.next()
                oldest.remove()
            }
            val name = pendingName
            if (name == null) return
            if (System.currentTimeMillis() - pendingAt > WINDOW_MS) {
                pendingName = null
                return
            }
            pendingName = null
            pendingSeq += 1
            deliverName = name
        }
        val dn = deliverName
        if (dn == null) return
        runCatching { VkbBridge.enqueueNamed(ctx, url, dn + extOf(url)) }
            .onFailure { toast("Не удалось начать скачивание") }
    }

    // Из VkbBridge.downloadAudioTrack (поток JS-моста): готовим имя для
    // следующего пришедшего адреса аудио-CDN.
    fun requestFor(rawName: String) {
        synchronized(lock) {
            pendingSeq += 1
            val seq = pendingSeq
            pendingName = sanitize(rawName)
            pendingAt = System.currentTimeMillis()
            main.postDelayed({
                synchronized(lock) {
                    if (seq != pendingSeq) return@postDelayed
                    val left = pendingName
                    pendingName = null
                    if (left != null) {
                        toast("Ссылка не найдена: трек уже загружен? Нажмите кнопку ещё раз")
                    }
                }
            }, WINDOW_MS)
        }
    }

    // Расширение по адресу: VK Музыка отдаёт .mp3, архивы бывают .m4a.
    private fun extOf(url: String): String {
        val clean = url.substringBefore("?").lowercase()
        if (clean.endsWith(".m4a")) return ".m4a"
        return ".mp3"
    }

    // Имя файла: запрещённые в именах символы — в пробел, длина до 80.
    private fun sanitize(rawName: String): String {
        var s = rawName.replace(Regex("""[\\/:*?"<>|\n\r\t]+"""), " ")
        s = s.replace(Regex(" +"), " ").trim()
        if (s.length > 80) {
            // Режем аккуратно: не оставляем половину эмодзи (суррогатную пару)
            if (Character.isHighSurrogate(s[79])) {
                s = s.substring(0, 79)
            } else {
                s = s.substring(0, 80)
            }
        }
        if (s.isBlank()) s = "vk_track"
        return s
    }

    private fun toast(msg: String) {
        val ctx = appContext
        if (ctx == null) return
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }
}
