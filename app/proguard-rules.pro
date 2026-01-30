# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Synapse ProGuard Rules

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.synapse.**$$serializer { *; }
-keepclassmembers class com.synapse.** {
    *** Companion;
}
-keepclasseswithmembers class com.synapse.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor (not used - suppress warnings only)
-dontwarn io.ktor.**
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# SLF4J
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder

# OkHttp - keep public API and connection internals
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Response { *; }
-keep class okhttp3.CertificatePinner { *; }
-keep class okhttp3.CertificatePinner$Builder { *; }
-keep interface okhttp3.Interceptor { *; }
-keepclassmembers class okhttp3.internal.** { volatile <fields>; }

# Coil - keep image loader entry points
-keep class coil.ImageLoader { *; }
-keep class coil.compose.** { *; }

# Koin - keep DI module declarations and injection
-keep class org.koin.core.module.Module { *; }
-keep class org.koin.core.KoinApplication { *; }
-keepclassmembers class * { @org.koin.core.annotation.* <methods>; }

# Strip verbose/debug/info logs in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
