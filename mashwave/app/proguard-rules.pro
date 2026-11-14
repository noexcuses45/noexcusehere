# Ultimate Beats is a sideloaded personal app: we only want R8 to strip unused code
# (mainly the huge Material icon set), not to rename anything.
-dontobfuscate

# Player engine: extension decoders are loaded by reflection and called from native code.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# App code (models are saved to disk with kotlinx.serialization).
-keep class com.noexcusehere.mashwave.** { *; }

-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
