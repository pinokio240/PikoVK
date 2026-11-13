package com.pikovk.browser.ext

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException

// Темы ВКонтакте, извлечённые из расширения VK Next (CRX 14.14.2,
// Chrome Web Store jephanpkonkmnkekmlkcijdjgniikppl) — 1.3.24.
//
// ИСТОЧНИК: во встроенном бандле VK Next темы лежат как JSON-конфиги
// (name/author/themeColor/variables). В пакете 4 уникальные темы
// (остальные повторяются между чанками; каталог пользовательских тем
// живёт на их сервере и в пакет не входит):
//   vk_light       «Светлая» (@vk)      — 71 токен VKUI;
//   vk_dark_native «Тёмная» (@vk)       — без переменных: это родная тёмная
//                                       схема VK (то, что PikoVK и раньше
//                                       включал куками remixdark_color_scheme);
//   vk_next_gray   «Тёмная» (@vknext)   — 300 переменных (серая #19191a);
//   vk_next_black  «Чёрная» (@vknext)   — 300 переменных (#000000).
//
// УСТРОЙСТВО ТЕМЫ: новая веб-морда VK построена на design-токенах (CSS
// custom properties). Карта переменных темы содержит ОДНОВРЕМЕННО старые
// переменные VK (--accent, --background_content, --dynamic_gray — так
// раскрашена мобильная версия m.vk.ru) и новые токены VKUI
// (--vkui--color_* — так раскрашена полная версия vk.ru). При генерации
// asset (scripts/gen_vk_themes.py) каждая старая переменная дополнительно
// зеркалится в свой VKUI-токен через alias-карту VK Next (69 пар,
// модуль 49032 XZ) — как это делает их нормализатор: тема, написанная
// «по-старому», красит и новые VKUI-компоненты.
//
// ПРИМЕНЕНИЕ: THEME_JS (см. ExtensionEngine) при document_start ставит
// все переменные ИНЛАЙН на <html> (documentElement.style.setProperty).
// Инлайн сильнее любого таблицного правила без !important, поэтому ничего
// не зависит от порядка <style>-тегов VK (в отличие от расширения, воюющего
// за порядок стилей). documentElement переживает SPA-переходы, поэтому
// повторно применяется только на DOMContentLoaded (на случай замены <html>
// при полной навигации). Для тёмных тем принудительно выставляются
// VKUI-классы vkui--vkBase/vkCom/vkIOS--dark: там, где наши переменные не
// покрывают токен, VK подставит значения собственной тёмной схемы, а не
// светлой.
//
// ГЕЙТИНГ (важно): выбранная тема перекрывает тумблер «Тёмная тема».
//   - «Не менять» и «Тёмная (как в VK)» — прежнее поведение: куки темы
//     (VkThemeCookies) + CSS-инверсия как запасной механизм;
//   - цветные темы VK Next — куки и инверсия НЕ применяются (чтобы VK
//     не рисовал свою тёмную схему поверх нашей карты цветов).
// Выбор владельца хранится в Prefs.vkThemeId, применяется после возврата
// в браузер (Prefs.markDirty → пересборка WebView).
object VkThemes {

    private const val TAG = "PikoVK-Theme"

    const val THEME_NONE = ""                 // «Не менять» — как раньше
    const val THEME_LIGHT = "vk_light"
    const val THEME_DARK_NATIVE = "vk_dark_native"
    const val THEME_GRAY = "vk_next_gray"
    const val THEME_BLACK = "vk_next_black"

    // Порядок пунктов диалога настроек (первый — текущий нейтральный выбор).
    val CHOICE_IDS = listOf(
        THEME_NONE, THEME_LIGHT, THEME_DARK_NATIVE, THEME_GRAY, THEME_BLACK
    )

    private const val ASSET = "vk_themes.json"

    // Кэш: id → (nativeTheme dark?, карта переменных). Лениво из assets,
    // читается один раз за процесс.
    private var cache: Map<String, Pair<Boolean, JSONObject>>? = null

    // Подпись пункта настроек по id (неизвестный id — «Не менять»).
    fun choiceLabel(id: String): String {
        return when (id) {
            THEME_LIGHT -> "Светлая (VK)"
            THEME_DARK_NATIVE -> "Тёмная (как в VK)"
            THEME_GRAY -> "Тёмная серая (VK Next)"
            THEME_BLACK -> "Чёрная (VK Next)"
            else -> "Не менять"
        }
    }

    // Тема заменяет собой куки тёмной схемы и CSS-инверсию?
    fun isColorTheme(id: String): Boolean {
        return id == THEME_LIGHT || id == THEME_GRAY || id == THEME_BLACK
    }

    // JS-чанк темы для ExtensionEngine.buildScript. Пустая строка — чанк не
    // нужен («Не менять», «Тёмная (как в VK)», неизвестный id, сбой чтения).
    fun buildThemeJs(ctx: Context, id: String): String {
        if (!isColorTheme(id)) return ""
        val theme = load(ctx)[id]
        if (theme == null) {
            Log.w(TAG, "тема $id не найдена в $ASSET — применяется «Не менять»")
            return ""
        }
        val dark = theme.first
        val vars = theme.second
        // JSONObject.toString — компактный JSON; он же валидный литерал JS
        // (значения — hex-цвета, кавычек и скобок внутри нет).
        val varsJson = vars.toString()
        return buildString {
            append("try{var TV=")
            append(varsJson)
            append(",TD=").append(dark)
            append(
                ";function vkbThemeApply(){" +
                    "var el=document.documentElement;" +
                    "if(!el)return;" +
                    "for(var k in TV){try{el.style.setProperty(k,TV[k]);}catch(e){}}" +
                    "try{var dk=['vkui--vkBase--dark','vkui--vkCom--dark','vkui--vkIOS--dark'];" +
                    "var lt=['vkui--vkBase--light','vkui--vkCom--light','vkui--vkIOS--light'];" +
                    "for(var i=0;i<3;i++){el.classList.remove(dk[i]);el.classList.remove(lt[i]);}" +
                    "var cls=TD?dk:lt;" +
                    "for(var j=0;j<3;j++){el.classList.add(cls[j]);}}catch(e){}" +
                "}" +
                "vkbThemeApply();" +
                "document.addEventListener('DOMContentLoaded',vkbThemeApply,{once:true});"
            )
            append("}catch(e){}")
        }
    }

    // Прочитать темы из assets. Сбой чтения/парсинга — пустая карта
    // (все темы считаются «не найденными», поведение прежнее).
    private fun load(ctx: Context): Map<String, Pair<Boolean, JSONObject>> {
        val cached = cache
        if (cached != null) return cached
        val result = HashMap<String, Pair<Boolean, JSONObject>>()
        try {
            val raw = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(raw)
            val arr = root.getJSONArray("themes")
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val id: String? = t.optString("id", null)
                if (id == null) continue
                result[id] = Pair(
                    t.optString("nativeTheme", "light") == "dark",
                    t.getJSONObject("variables")
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "не удалось прочитать $ASSET", e)
        } catch (e: Exception) {
            Log.e(TAG, "битый $ASSET", e)
        }
        cache = result
        return result
    }
}
