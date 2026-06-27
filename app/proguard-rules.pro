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

# Phase 6: Media3 TTS keep rules (Oracle S10)
# Custom SimpleBasePlayer subclass referenced programmatically
-keep class com.epubreader.app.media.TtsPlayer { *; }
-keep class com.epubreader.app.media.TtsPlaybackService { *; }
-keep class com.epubreader.app.media.AndroidTtsEngine { *; }
-keep class com.epubreader.app.media.TtsControllerImpl { *; }
# TtsEngineState sealed class (reflection via sealed subtypes)
-keep class com.epubreader.app.core.tts.** { *; }

# Fragments referenced by android:name in layout XML (R8 does not scan layout XML).
# Without this, FragmentManager's Class.forName() fails with ClassNotFoundException.
-keep class com.epubreader.app.ui.reader.ReaderHostFragment { *; }

# Navigation 2.8+ type-safe routes (kotlinx.serialization @Serializable).
# The library ships consumer rules for $$serializer and Companion, but we add
# explicit keeps for the route classes themselves to prevent class renaming
# (Navigation looks up routes by KClass at runtime).
-keep class com.epubreader.app.navigation.ReaderRoute { *; }
-keep class com.epubreader.app.navigation.BookshelfRoute { *; }

# ViewBinding generated classes (referenced from Compose AndroidViewBinding).
-keep class com.epubreader.app.databinding.** { *; }

# flexmark (HTML→Markdown converter, Phase 5) — not thread-safe, uses reflection.
-keep class com.vladsch.flexmark.** { *; }
-dontwarn com.vladsch.flexmark.**
