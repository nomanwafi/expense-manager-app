# Keep WebView JavaScript interface methods (none used by default, but keep safe)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebViewClient / WebChromeClient callback classes
-keep class com.nomananik.expensemanager.** { *; }

# Standard AndroidX / Kotlin rules are handled by the default optimize file.
