# whisper.cpp / ONNX JNI bridges are reached from native code: keep them.
-keep class com.semavoice.core.speech.WhisperNative { *; }
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class * extends android.inputmethodservice.InputMethodService { *; }
-dontwarn org.slf4j.**
