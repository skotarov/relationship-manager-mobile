# JavaScript interface methods are invoked reflectively by WebView and must survive R8.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
