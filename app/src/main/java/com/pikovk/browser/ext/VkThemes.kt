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
// ПРИМЕНЕНИЕ: THEME_JS (см. ExtensionEngine) при document_start ставит все
// переменные ИНЛАЙН с 'important' на documentElement, body и корни VKUI
// (.vkui__root/.vkui__portal-root/.vkui__popout-root/.vkuiAppRoot): инлайн
// с important на элементе сильнее любых таблицных правил VK на нём же —
// в том числе их !important. Инлайн на <html> одного недостаточно: VKUI
// объявляет токены правилами на своих контейнерах, а объявление на
// контейнере бьёт унаследованное с <html>. ВАЖНО: VK гидратирует страницу
// (переписывает разметку и классы), поэтому применение ПОВТОРЯЕТСЯ: на
// DOMContentLoaded/load/pageshow, 8 отложенных свипов до 2 с, и дальше по
// MutationObserver (атрибуты class/style на <html>; появление корней в
// <body> — childList), оба через общий debounce 100 мс. ПРИМЕНЕНИЕ
// ИДЕМПОТЕНТНО (1.3.26): setProperty и смена классов/colorScheme — только
// когда значение реально отличается. Это обязательно: безусловное
// remove+add классов само порождало мутации, за которыми следит тот же
// наблюдатель, — цикл «применил → мутация → применил» вешал загрузку
// страницы (репорт владельца 1.3.25). Для тёмных тем принудительно
// выставляются VKUI-классы vkui--vkBase/vkCom/vkIOS--dark и
// style.colorScheme: там, где наши переменные не покрывают токен, VK
// подставит значения собственной тёмной схемы, а не светлой.
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

    // Шаблон JS-чанка темы. Плейсхолдеры: %TV% — компактный JSON карты
    // переменных (он же валидный литерал JS: значения — hex-цвета, кавычек
    // и скобок внутри нет), %TD% — true/false (тёмная ли тема). Тот же
    // шаблон прогоняет scripts/check_theme_apply.js на мини-DOM: проверка
    // синтаксиса и логики применения (html/body/корни, классы, повторы).
    private const val THEME_JS = """try{var TV=%TV%,TD=%TD%;
function vkbThemeApply(){
try{
var els=[document.documentElement,document.body];
try{
var rs=document.querySelectorAll('.vkui__root,.vkui__portal-root,.vkui__popout-root,.vkuiAppRoot');
for(var r=0;r<rs.length;r++)els.push(rs[r]);
}catch(e){}
for(var i=0;i<els.length;i++){
var el=els[i];if(!el||!el.style)continue;
for(var k in TV){try{if(el.style.getPropertyValue(k)!==TV[k])el.style.setProperty(k,TV[k],'important');}catch(e){}}
}
}catch(e){}
try{
var el=document.documentElement;if(!el)return;
var dk=['vkui--vkBase--dark','vkui--vkCom--dark','vkui--vkIOS--dark'];
var lt=['vkui--vkBase--light','vkui--vkCom--light','vkui--vkIOS--light'];
var want=TD?dk:lt,other=TD?lt:dk;
for(var i=0;i<3;i++){
if(el.classList.contains(other[i]))el.classList.remove(other[i]);
if(!el.classList.contains(want[i]))el.classList.add(want[i]);
}
if(el.style.colorScheme!==(TD?'dark':'light'))el.style.colorScheme=TD?'dark':'light';
}catch(e){}
}
vkbThemeApply();
document.addEventListener('DOMContentLoaded',vkbThemeApply);
window.addEventListener('load',vkbThemeApply);
window.addEventListener('pageshow',vkbThemeApply);
for(var n=1;n<=8;n++)setTimeout(vkbThemeApply,250*n);
var vkbT=0;
function vkbSoon(){try{clearTimeout(vkbT);vkbT=setTimeout(function(){try{vkbThemeApply();}catch(e){}},100);}catch(e){}}
try{new MutationObserver(vkbSoon).observe(document.documentElement,{attributes:true,attributeFilter:['class','style']});}catch(e){}
try{new MutationObserver(vkbSoon).observe(document.body||document.documentElement,{childList:true});}catch(e){}
}catch(e){}"""

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
        return THEME_JS
            .replace("%TV%", vars.toString())
            .replace("%TD%", dark.toString())
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
                // Fallback "" вместо null: null в fallback, который новые SDK
                // объявляют @NonNull, давал предупреждение компилятора
                // «Type mismatch: inferred type is Nothing? but String».
                // Пустой id в asset не встречается — проверка эквивалентна.
                val id: String = t.optString("id", "")
                if (id.isEmpty()) continue
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
