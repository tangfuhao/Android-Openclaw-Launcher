# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.openclaw.android.**$$serializer { *; }
-keepclassmembers class com.openclaw.android.** { *** Companion; }
-keepclasseswithmembers class com.openclaw.android.** { kotlinx.serialization.KSerializer serializer(...); }
