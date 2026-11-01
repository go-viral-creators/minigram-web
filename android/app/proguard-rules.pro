# ---- Minigram R8 rules ----
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Gson with TypeToken<List<HashMap<...>>> (forward messages)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Firebase Realtime Database POJO mapping (inner model classes read with getValue(Class))
-keepclassmembers class com.silu.minigram.** { public <init>(); }
-keepclassmembers class com.silu.minigram.*$* { public <fields>; }

# Activities / receivers referenced from the manifest are kept by AAPT rules automatically.

# uCrop / Glide transformations (reflection in libraries is covered by their consumer rules)
-dontwarn com.yalantis.ucrop.**
-dontwarn jp.wasabeef.glide.transformations.**

# Strip chatty logging from release builds so message data can never leak through logcat
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Loaded by name from the theme (viewInflaterClass) - R8 cannot see that reference
-keep class com.silu.minigram.InkViewInflater { <init>(); }
-keep class com.silu.minigram.IconTextView { <init>(android.content.Context, android.util.AttributeSet); }
