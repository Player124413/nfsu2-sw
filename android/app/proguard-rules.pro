# The native entry point is discovered by SDLActivity via JNI; keep the
# launcher methods even in a future minified release build.
-keep class com.nfsu2x.** { *; }
-keep class org.libsdl.app.** { *; }
