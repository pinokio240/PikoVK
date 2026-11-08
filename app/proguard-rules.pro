# JS-интерфейс для расширений браузера: не переименовывать
-keepattributes JavascriptInterface
-keepclassmembers class com.pikovk.browser.bridge.VkbBridge {
    public *;
}
# WebView-клиенты создаются рефлексией некоторых WebView-версий
-keep class com.pikovk.browser.browser.** { *; }
