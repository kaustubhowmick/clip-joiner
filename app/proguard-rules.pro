# Media3 Transformer/effect load some components reflectively (decoders, GL shaders,
# extension factories). Keeping the package costs a little size but avoids
# release-only crashes in the conversion path, which only the debug build exercises
# in CI.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
