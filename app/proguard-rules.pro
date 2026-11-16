# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI bridge
-keep class com.mambadoc.app.native.LlamaNative { *; }
-keep class com.mambadoc.app.native.** { *; }

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# SQLite / FTS
-keep class android.database.sqlite.** { *; }
