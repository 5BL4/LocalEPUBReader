# R8 / ProGuard rules for EpubReader.
# JS Bridge classes arrive in Phase 4; the @Keep rule is pre-staged here so
# release builds never strip @JavascriptInterface methods (NEVER #23).

# Keep JS Bridge interfaces (annotated with @JavascriptInterface).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep any class annotated with @Keep (defensive against R8 stripping).
-keep,allowobfuscation @interface dagger.hilt.android.lifecycle.HiltViewModel
-keep class androidx.hilt.** { *; }

# Room (generated code is kept by Room's own rules; nothing extra needed).
# Hilt (generated code is kept by Hilt's own rules; nothing extra needed).

# Keep entity/DAO class names for reflection-free Room access.
-keep class com.epubreader.app.data.local.entity.** { *; }

# Readium v3 keep rules (Phase 3)
-keep class org.readium.r2.shared.** { *; }
-keep class org.readium.r2.streamer.** { *; }
-keep class org.readium.r2.navigator.** { *; }
-dontwarn org.readium.r2.**
-keep @androidx.annotation.Keep class * { *; }
