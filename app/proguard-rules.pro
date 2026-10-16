# The reader page calls these methods from JavaScript.
-keepclassmembers class com.folio.reader.ui.reader.ReaderBridge {
    @android.webkit.JavascriptInterface <methods>;
}
