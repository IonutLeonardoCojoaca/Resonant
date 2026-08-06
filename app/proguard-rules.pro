# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep useful production stack traces while allowing R8 to optimize the app.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*

# Gson is still used for network, Room converters and persisted playback state.
# These models intentionally use a mix of annotated and field-name based JSON.
-keep class com.example.resonant.data.models.** { *; }
-keep class com.example.resonant.data.network.** { *; }
-keep class com.example.resonant.playback.OfflineDownloadMetadata { *; }
-keep class com.example.resonant.playback.PlaybackStateStore$** { *; }
-keep class com.example.resonant.playback.PersistedPlaybackState { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
