# Keep WebView JS bridge methods
-keepclassmembers class it.filettino.app.MainActivity$NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
