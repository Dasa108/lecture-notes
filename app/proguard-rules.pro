# JNI entry points and the callback class used from native code.
-keep class com.lecturenotes.whisper.WhisperNative { *; }
-keep interface com.lecturenotes.whisper.WhisperNative$ProgressListener { *; }
-keep class com.lecturenotes.whisper.NativeSegment { *; }

# sherpa-onnx's native code reads its Kotlin config/result classes by field name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
