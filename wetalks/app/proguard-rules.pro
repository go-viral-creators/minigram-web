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
