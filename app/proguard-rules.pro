# apksig используется на телефоне - не удаляем его внутренние классы
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# Kotlin metadata / Compose
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn org.jetbrains.annotations.**
