# JARVIS App ProGuard Rules

# Keep Room entities
-keep class com.jarvis.app.data.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Keep accessibility service
-keep class com.jarvis.app.service.JarvisAccessibilityService { *; }

# Keep broadcast receivers
-keep class com.jarvis.app.receiver.** { *; }
