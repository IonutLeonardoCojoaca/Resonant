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

# Logs de depuración fuera del release: 150+ Log.d/Log.v dejaban de costar formato de cadenas y E/S.
# Solo v/d: w/e/i siguen saliendo para diagnosticar en producción.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# slf4j-api (lo trae SignalR) busca un binding opcional que no existe en la app: R8 lo marca como clase ausente
# y rompe minifyReleaseWithR8. Sin binding slf4j usa su logger NOP, que es lo que queremos.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Otras clases que Gson serializa por reflexión fuera de data.models / data.network: sin esto R8 renombra sus
# campos y lo guardado (escuchas pendientes, metadatos de clips descargados) deja de leerse.
-keep class com.example.resonant.managers.PendingStream { *; }
-keep class com.example.resonant.playback.OfflineClipMetadata { *; }

# DTOs de Collab Finder (feature con su propia capa data/dto): mismo motivo que data.models / data.network. Sin
# esto R8 trataba las respuestas como clases que nunca se crean y la búsqueda fallaba en release ("Unknown error").
-keep class com.example.resonant.feature.collabfinder.data.dto.** { *; }

# ── Retrofit 2.9.0 ──
# Esta versión no trae las reglas para el modo completo de R8 (el de AGP 8): R8 quitaba la firma genérica de
# las funciones suspend de los servicios y Retrofit fallaba en release con
# "Class cannot be cast to ParameterizedType" (p. ej. el login con Google). Reglas oficiales de Retrofit 2.11.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# ── Gson 2.10.1 ──
# Mismo problema con los tipos genéricos de TypeToken (listas guardadas en preferencias, colas, etc.).
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
