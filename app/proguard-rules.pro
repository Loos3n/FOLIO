# The reader page calls these methods from JavaScript.
-keepclassmembers class com.folio.reader.ui.reader.ReaderBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# The speech library's native code reads these classes' fields and methods by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
