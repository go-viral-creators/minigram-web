-keepattributes Signature,*Annotation*
-keep class com.kafisir.wetalks.data.** { *; }
-keepclassmembers class com.kafisir.wetalks.data.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# sherpa-onnx text-to-speech (JNI + Kotlin API, Apache-2.0)
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# the voice engine calls its audio callback by name from native code: never rename or drop it
-keep class com.kafisir.wetalks.voice.PcmSink { *; }
-keepclassmembers class * implements kotlin.jvm.functions.Function1 { public java.lang.Integer invoke(float[]); }
-keep class kotlin.jvm.functions.Function1 { *; }
